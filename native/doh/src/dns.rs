//! DNS service has its own unauthenticated HTTPS channel and never sees site headers.
use bytes::Bytes;
use hickory_proto::{
    op::{Message, MessageType, OpCode, Query, ResponseCode},
    rr::{rdata::svcb::SvcParamValue, DNSClass, Name, RData, RecordType},
    serialize::binary::{BinEncodable, BinEncoder},
};
use http_body_util::{BodyExt, Full};
use hyper::Request;
use hyper_util::rt::{TokioExecutor, TokioIo};
use rustls::{
    client::{EchConfig, EchMode},
    pki_types::{EchConfigListBytes, ServerName},
    ClientConfig, RootCertStore,
};
use serde::Deserialize;
use std::{
    collections::{HashMap, HashSet},
    net::{IpAddr, SocketAddr},
    sync::{
        atomic::{AtomicBool, AtomicU16, Ordering},
        Arc, Mutex,
    },
    time::{Duration, Instant},
};
use tokio::{
    net::{lookup_host, TcpStream},
    time::timeout,
};
use tokio_rustls::TlsConnector;
use url::Url;

pub type Result<T> = std::result::Result<T, &'static str>;
static DNS_ID: AtomicU16 = AtomicU16::new(2048);

#[derive(Deserialize)]
pub struct Configuration {
    pub url: String,
    #[serde(default)]
    pub bootstrap: Vec<IpAddr>,
    #[serde(default)]
    pub local_selection: bool,
    #[serde(default)]
    pub atlas: bool,
    #[serde(default)]
    pub cached_winner: Option<serde_json::Value>,
}

#[derive(Clone)]
pub struct Answer {
    pub ips: Vec<IpAddr>,
    pub ech: Vec<u8>,
    pub expires: Instant,
}

type Entry = Arc<tokio::sync::Mutex<Option<Answer>>>;
pub struct Resolver {
    server: Url,
    bootstrap: Vec<IpAddr>,
    entries: Mutex<HashMap<String, Entry>>,
    selector: Option<crate::edge_selection::Selector>,
    ech_updates: Mutex<HashMap<String, (Vec<u8>, Instant)>>,
    closed: AtomicBool,
    stop: tokio::sync::Notify,
}

impl Resolver {
    pub fn invalidate(&self, host: &str) {
        if let Ok(mut entries) = self.entries.lock() {
            entries.remove(&host.trim_end_matches('.').to_ascii_lowercase());
        }
    }
    pub fn new(input: &str) -> Result<Self> {
        let config: Configuration =
            serde_json::from_str(input).map_err(|_| "invalid_configuration")?;
        let server = Url::parse(&config.url).map_err(|_| "invalid_server")?;
        if server.scheme() != "https"
            || server.host_str().is_none()
            || !server.username().is_empty()
            || server.password().is_some()
            || server.fragment().is_some()
            || config.bootstrap.len() > 8
        {
            return Err("invalid_server");
        }
        Ok(Self {
            server,
            bootstrap: config.bootstrap,
            entries: Mutex::new(HashMap::new()),
            selector: config.local_selection.then(|| {
                crate::edge_selection::Selector::new(
                    config.atlas,
                    config
                        .cached_winner
                        .and_then(|value| serde_json::from_value(value).ok()),
                )
            }),
            ech_updates: Mutex::new(HashMap::new()),
            closed: AtomicBool::new(false),
            stop: tokio::sync::Notify::new(),
        })
    }

    pub async fn resolve(&self, host: &str) -> Result<Answer> {
        let stopped = self.stop.notified();
        tokio::pin!(stopped);
        stopped.as_mut().enable();
        if self.closed.load(Ordering::SeqCst) {
            return Err("profile_closed");
        }
        tokio::select! {
            _ = stopped => Err("profile_closed"),
            result = self.resolve_route(host) => result,
        }
    }
    async fn resolve_route(&self, host: &str) -> Result<Answer> {
        let host = host.trim_end_matches('.').to_ascii_lowercase();
        let selected = if crate::edge_pool::covered(&host) {
            if let Some(selector) = &self.selector {
                selector.answer().await
            } else {
                None
            }
        } else {
            None
        };
        let mut answer = match selected {
            Some(answer) => answer,
            None => self.resolve_dns(&host).await?,
        };
        if let Some((key, until)) = self
            .ech_updates
            .lock()
            .map_err(|_| "resolver_lock")?
            .get(&host)
        {
            if *until > Instant::now() {
                answer.ech = key.clone();
            }
        }
        Ok(answer)
    }
    pub fn selection_status(&self) -> serde_json::Value {
        self.selector
            .as_ref()
            .map(|s| s.status())
            .unwrap_or_else(|| serde_json::json!({"phase":"off"}))
    }
    pub fn failed(&self, host: &str, ip: IpAddr) {
        if crate::edge_pool::covered(host) {
            if let Some(selector) = &self.selector {
                selector.failed(ip);
            }
        }
    }
    pub fn connected(&self, host: &str, ip: IpAddr) {
        if crate::edge_pool::covered(host) {
            if let Some(selector) = &self.selector {
                selector.connected(ip);
            }
        }
    }
    pub fn adopt_ech(&self, host: &str, key: Vec<u8>) {
        let host = host.trim_end_matches('.').to_ascii_lowercase();
        if crate::edge_pool::covered(&host) {
            if let Some(selector) = &self.selector {
                selector.adopt(&key);
            }
        }
        if let Ok(mut updates) = self.ech_updates.lock() {
            updates.retain(|_, (_, expiry)| *expiry > Instant::now());
            if updates.len() >= 256 {
                updates.clear();
            }
            updates.insert(host, (key, Instant::now() + Duration::from_secs(3600)));
        }
    }
    pub fn close(&self) {
        self.closed.store(true, Ordering::SeqCst);
        if let Some(selector) = &self.selector {
            selector.close();
        }
        self.stop.notify_waiters();
    }
    pub async fn resolve_dns(&self, host: &str) -> Result<Answer> {
        let host = host.trim_end_matches('.').to_ascii_lowercase();
        if let Ok(ip) = host.parse::<IpAddr>() {
            return Ok(Answer {
                ips: vec![ip],
                ech: vec![],
                expires: Instant::now() + Duration::from_secs(30),
            });
        }
        Name::from_ascii(&host).map_err(|_| "invalid_hostname")?;
        let entry = {
            let mut entries = self.entries.lock().map_err(|_| "resolver_lock")?;
            // Bounding the cache never invalidates entries already used by concurrent callers.
            if entries.len() >= 256 && !entries.contains_key(&host) {
                entries.clear();
            }
            entries
                .entry(host.clone())
                .or_insert_with(|| Arc::new(tokio::sync::Mutex::new(None)))
                .clone()
        };
        let mut cached = entry.lock().await;
        if let Some(answer) = cached.as_ref().filter(|a| a.expires > Instant::now()) {
            return Ok(answer.clone());
        }
        let answer = timeout(Duration::from_secs(15), self.lookup(&host))
            .await
            .map_err(|_| "dns_timeout")??;
        *cached = Some(answer.clone());
        Ok(answer)
    }

    async fn lookup(&self, host: &str) -> Result<Answer> {
        let port = self
            .server
            .port_or_known_default()
            .ok_or("invalid_server")?;
        let bootstrap = if self.bootstrap.is_empty() {
            lookup_host((self.server.host_str().ok_or("invalid_server")?, port))
                .await
                .map_err(|_| "bootstrap_lookup_failed")?
                .map(|s| s.ip())
                .collect::<Vec<_>>()
        } else {
            self.bootstrap.clone()
        };
        let mut name = host.to_owned();
        let mut visited = HashSet::new();
        let mut records = Vec::new();
        for _ in 0..8 {
            if !visited.insert(name.clone()) {
                return Err("dns_alias_loop");
            }
            let (a, aaaa, https) = tokio::join!(
                self.query(&name, RecordType::A, &bootstrap),
                self.query(&name, RecordType::AAAA, &bootstrap),
                self.query(&name, RecordType::HTTPS, &bootstrap)
            );
            // Never mistake a failed HTTPS query for proof that ECH is unavailable.
            let https = https?;
            if a.is_err() && aaaa.is_err() {
                return Err("dns_address_queries_failed");
            }
            records.extend(
                a.into_iter()
                    .chain(aaaa)
                    .chain(std::iter::once(https))
                    .flat_map(|m| m.answers().to_vec()),
            );
            let parsed = parse_records(host, &records)?;
            if let Some(next) = parsed.alias {
                name = next;
                continue;
            }
            if parsed.ips.is_empty() {
                return Err("dns_no_addresses");
            }
            return Ok(Answer {
                ips: parsed.ips,
                ech: parsed.ech,
                expires: Instant::now() + Duration::from_secs(parsed.ttl.min(3600) as u64),
            });
        }
        Err("dns_alias_limit")
    }

    async fn query(&self, host: &str, kind: RecordType, bootstrap: &[IpAddr]) -> Result<Message> {
        let query = query(host, kind)?;
        let body = query.to_vec().map_err(|_| "dns_encode_failed")?;
        let host = self.server.host_str().ok_or("invalid_server")?;
        let port = self
            .server
            .port_or_known_default()
            .ok_or("invalid_server")?;
        let tls = connect(host, port, bootstrap, tls_config(None)?).await?;
        let alpn = tls
            .get_ref()
            .1
            .alpn_protocol()
            .unwrap_or(b"http/1.1")
            .to_vec();
        let request = Request::builder()
            .method("POST")
            .uri(if alpn == b"h2" {
                self.server.as_str().to_owned()
            } else {
                origin_form(&self.server)
            })
            .header("Host", self.server.authority())
            .header("User-Agent", "Kudo-DoH/1")
            .header("Content-Type", "application/dns-message")
            .header("Accept", "application/dns-message")
            .header("Accept-Encoding", "identity")
            .body(Full::new(Bytes::from(body)))
            .map_err(|_| "doh_request_failed")?;
        let response = if alpn == b"h2" {
            let (mut sender, connection) =
                hyper::client::conn::http2::handshake(TokioExecutor::new(), TokioIo::new(tls))
                    .await
                    .map_err(|_| "doh_http_failed")?;
            tokio::spawn(async move {
                let _ = connection.await;
            });
            sender
                .send_request(request)
                .await
                .map_err(|_| "doh_http_failed")?
        } else {
            let (mut sender, connection) = hyper::client::conn::http1::handshake(TokioIo::new(tls))
                .await
                .map_err(|_| "doh_http_failed")?;
            tokio::spawn(async move {
                let _ = connection.await;
            });
            sender
                .send_request(request)
                .await
                .map_err(|_| "doh_http_failed")?
        };
        if response.status() != 200
            || response
                .headers()
                .get("content-type")
                .and_then(|h| h.to_str().ok())
                .unwrap_or("")
                .split(';')
                .next()
                .unwrap_or("")
                .trim()
                != "application/dns-message"
        {
            return Err("doh_status_or_content_type");
        }
        let mut body = response.into_body();
        let mut bytes = Vec::new();
        while let Some(frame) = body.frame().await {
            if let Ok(data) = frame.map_err(|_| "doh_body_failed")?.into_data() {
                if bytes.len() + data.len() > 65535 {
                    return Err("dns_response_too_large");
                }
                bytes.extend_from_slice(&data);
            }
        }
        validate_reply(&query, &bytes)
    }
}

pub fn tls_config(ech: Option<Vec<u8>>) -> Result<ClientConfig> {
    let provider = Arc::new(rustls::crypto::aws_lc_rs::default_provider());
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let builder = ClientConfig::builder_with_provider(provider);
    let builder = if let Some(bytes) = ech {
        let config = EchConfig::new(
            EchConfigListBytes::from(bytes),
            rustls::crypto::aws_lc_rs::hpke::ALL_SUPPORTED_SUITES,
        )
        .map_err(|_| "ech_config_invalid")?;
        builder
            .with_ech(EchMode::Enable(config))
            .map_err(|_| "ech_config_unsupported")?
    } else {
        builder
            .with_safe_default_protocol_versions()
            .map_err(|_| "tls_configuration_failed")?
    };
    let mut config = builder.with_root_certificates(roots).with_no_client_auth();
    config.alpn_protocols = vec![b"h2".to_vec(), b"http/1.1".to_vec()];
    Ok(config)
}

async fn connect(
    host: &str,
    port: u16,
    ips: &[IpAddr],
    config: ClientConfig,
) -> Result<tokio_rustls::client::TlsStream<TcpStream>> {
    let connector = TlsConnector::from(Arc::new(config));
    let name = ServerName::try_from(host.to_owned()).map_err(|_| "invalid_hostname")?;
    // DNS requests are read-only. Try addresses only before transmitting HTTP.
    for ip in ips.iter().take(8) {
        if let Ok(Ok(tcp)) = timeout(
            Duration::from_secs(2),
            TcpStream::connect(SocketAddr::new(*ip, port)),
        )
        .await
        {
            if let Ok(Ok(tls)) =
                timeout(Duration::from_secs(3), connector.connect(name.clone(), tcp)).await
            {
                return Ok(tls);
            }
        }
    }
    Err("doh_connection_or_certificate_failed")
}

pub fn origin_form(url: &Url) -> String {
    let mut path = url.path().to_owned();
    if let Some(query) = url.query() {
        path.push('?');
        path.push_str(query);
    }
    path
}

fn query(host: &str, kind: RecordType) -> Result<Message> {
    let mut message = Message::new();
    message
        .set_id(DNS_ID.fetch_add(1, Ordering::Relaxed))
        .set_recursion_desired(true)
        .add_query(Query::query(
            Name::from_ascii(host).map_err(|_| "invalid_hostname")?,
            kind,
        ));
    Ok(message)
}

fn validate_reply(query: &Message, bytes: &[u8]) -> Result<Message> {
    let reply = Message::from_vec(bytes).map_err(|_| "dns_malformed_reply")?;
    if reply.id() != query.id()
        || reply.message_type() != MessageType::Response
        || reply.op_code() != OpCode::Query
        || reply.truncated()
        || reply.queries() != query.queries()
        || reply.response_code() != ResponseCode::NoError
    {
        return Err("dns_failed_reply");
    }
    Ok(reply)
}

struct Parsed {
    ips: Vec<IpAddr>,
    ech: Vec<u8>,
    ttl: u32,
    alias: Option<String>,
}
fn parse_records(host: &str, records: &[hickory_proto::rr::Record]) -> Result<Parsed> {
    let mut name = Name::from_ascii(host).map_err(|_| "invalid_hostname")?;
    let mut seen = HashSet::new();
    let mut ttl = 60;
    let mut first = true;
    for _ in 0..8 {
        if !seen.insert(name.clone()) {
            return Err("dns_alias_loop");
        }
        let matching: Vec<_> = records
            .iter()
            .filter(|r| r.name() == &name && r.dns_class() == DNSClass::IN)
            .collect();
        for r in &matching {
            ttl = if first { r.ttl() } else { ttl.min(r.ttl()) };
            first = false;
        }
        let next = matching.iter().find_map(|r| match r.data() {
            Some(RData::CNAME(v)) => Some(v.0.clone()),
            Some(RData::HTTPS(v)) if v.svc_priority() == 0 => Some(v.target_name().clone()),
            _ => None,
        });
        if let Some(next) = next {
            if next.is_root() {
                return Err("dns_invalid_alias");
            }
            if !records.iter().any(|r| r.name() == &next) {
                return Ok(Parsed {
                    ips: vec![],
                    ech: vec![],
                    ttl,
                    alias: Some(next.to_ascii().trim_end_matches('.').to_owned()),
                });
            }
            name = next;
            continue;
        }
        let mut ips = Vec::new();
        let mut ech = Vec::new();
        // Lowest-priority service binding is selected; never mix ECH keys from different services.
        let service = matching
            .iter()
            .filter_map(|r| match r.data() {
                Some(RData::HTTPS(v)) if v.svc_priority() > 0 => Some(v),
                _ => None,
            })
            .min_by_key(|v| v.svc_priority());
        if let Some(service) = service {
            if !service.target_name().is_root() && service.target_name() != &name {
                return Err("dns_alternate_service_unsupported");
            }
            for (_, param) in service.svc_params() {
                if let SvcParamValue::EchConfig(value) = param {
                    value
                        .emit(&mut BinEncoder::new(&mut ech))
                        .map_err(|_| "ech_config_invalid")?;
                }
            }
        }
        for r in matching {
            match r.data() {
                Some(RData::A(v)) => ips.push(IpAddr::V4(v.0)),
                Some(RData::AAAA(v)) => ips.push(IpAddr::V6(v.0)),
                _ => {}
            }
        }
        ips.sort();
        ips.dedup();
        return Ok(Parsed {
            ips,
            ech,
            ttl,
            alias: None,
        });
    }
    Err("dns_alias_limit")
}

pub fn requires_ech(host: &str) -> bool {
    let host = host.trim_end_matches('.').to_ascii_lowercase();
    ["linux.do", "ldstatic.com"]
        .iter()
        .any(|base| host == *base || host.ends_with(&format!(".{base}")))
}

#[cfg(test)]
mod tests {
    use super::*;
    use base64::{engine::general_purpose::STANDARD, Engine};
    use hickory_proto::rr::{
        rdata::{
            svcb::{EchConfig as DnsEch, SvcParamKey},
            A, CNAME, HTTPS, SVCB,
        },
        Record,
    };
    #[test]
    fn configuration_rejects_credentials_insecure_and_fragments() {
        for url in [
            "http://dns.test/",
            "https://user:secret@dns.test/",
            "https://dns.test/#private",
        ] {
            assert!(Resolver::new(&serde_json::json!({"url":url}).to_string()).is_err());
        }
        assert!(Resolver::new(
            r#"{"url":"https://dns.test/query?private=1","bootstrap":["1.1.1.1"]}"#
        )
        .is_ok());
        assert!(Resolver::new(r#"{"url":"https://dns.test/","bootstrap":["host.test"]}"#).is_err());
    }
    #[test]
    fn ech_domain_boundary_is_exact() {
        assert!(requires_ech("LINUX.DO."));
        assert!(requires_ech("connect.linux.do"));
        assert!(requires_ech("ping.ldstatic.com"));
        assert!(!requires_ech("linux.do.evil.test"));
        assert!(!requires_ech("api.github.com"));
    }
    #[test]
    fn validates_dns_id_question_type_status_and_truncation() {
        let q = query("linux.do", RecordType::A).unwrap();
        let mut r = q.clone();
        r.set_message_type(MessageType::Response);
        assert!(validate_reply(&q, &r.to_vec().unwrap()).is_ok());
        r.set_truncated(true);
        assert!(validate_reply(&q, &r.to_vec().unwrap()).is_err());
        r.set_truncated(false).set_id(q.id().wrapping_add(1));
        assert!(validate_reply(&q, &r.to_vec().unwrap()).is_err());
        assert!(validate_reply(&q, &[0, 1]).is_err());
    }
    #[test]
    fn preserves_ech_list_and_minimum_ttl_ignores_unrelated_records() {
        let wire = STANDARD.decode("AEX+DQBBvgAgACAnc+9IO/WWQSMj7W2mLvaFlY3UbvRlOsLeK+qbQ6pgVwAEAAEAAQASY2xvdWRmbGFyZS1lY2guY29tAAA=").unwrap();
        let name = Name::from_ascii("linux.do").unwrap();
        let records = vec![
            Record::from_rdata(name.clone(), 14, RData::A(A::new(192, 0, 2, 1))),
            Record::from_rdata(
                name,
                10,
                RData::HTTPS(HTTPS(SVCB::new(
                    1,
                    Name::root(),
                    vec![(
                        SvcParamKey::EchConfig,
                        SvcParamValue::EchConfig(DnsEch(wire[2..].to_vec())),
                    )],
                ))),
            ),
            Record::from_rdata(
                Name::from_ascii("unrelated.test").unwrap(),
                1,
                RData::A(A::new(192, 0, 2, 2)),
            ),
        ];
        let answer = parse_records("linux.do", &records).unwrap();
        assert_eq!(answer.ips, vec!["192.0.2.1".parse::<IpAddr>().unwrap()]);
        assert_eq!(answer.ech, wire);
        assert_eq!(answer.ttl, 10);
        assert!(tls_config(Some(answer.ech)).is_ok());
    }
    #[test]
    fn follows_cname_and_bounds_alias_loops() {
        let a = Name::from_ascii("linux.do").unwrap();
        let b = Name::from_ascii("edge.test").unwrap();
        let first = Record::from_rdata(a.clone(), 3, RData::CNAME(CNAME(b.clone())));
        let answer = parse_records("linux.do", &[first.clone()]).unwrap();
        assert_eq!(answer.alias.as_deref(), Some("edge.test"));
        let records = [
            first.clone(),
            Record::from_rdata(b.clone(), 30, RData::A(A::new(192, 0, 2, 1))),
        ];
        assert_eq!(parse_records("linux.do", &records).unwrap().ttl, 3);
        assert!(parse_records(
            "linux.do",
            &[first, Record::from_rdata(b, 30, RData::CNAME(CNAME(a)))]
        )
        .is_err());
    }
    #[test]
    fn h1_origin_form_preserves_query_without_disclosing_userinfo() {
        assert_eq!(
            origin_form(&Url::parse("https://dns.test:8443/query?key=1").unwrap()),
            "/query?key=1"
        );
    }
}
