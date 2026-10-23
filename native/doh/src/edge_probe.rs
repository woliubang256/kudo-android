//! Bounded, credential-free HTTPS probes. ECH acceptance is mandatory.
use crate::dns::{self, Result};
use bytes::Bytes;
use http_body_util::{BodyExt, Empty};
use hyper_util::rt::TokioIo;
use rustls::{client::EchStatus, pki_types::ServerName};
use std::{
    io,
    net::{IpAddr, SocketAddr},
    sync::Arc,
    time::{Duration, Instant},
};
use tokio::net::TcpStream;
use tokio_rustls::TlsConnector;

pub fn retry_config(error: &io::Error) -> Option<Vec<u8>> {
    use rustls::internal::msgs::codec::Codec;
    let error = error.get_ref()?.downcast_ref::<rustls::Error>()?;
    if let rustls::Error::PeerIncompatible(
        rustls::PeerIncompatible::ServerRejectedEncryptedClientHello(Some(configs)),
    ) = error
    {
        let mut bytes = Vec::new();
        configs.encode(&mut bytes);
        if bytes.len() <= 4096 && dns::tls_config(Some(bytes.clone())).is_ok() {
            return Some(bytes);
        }
    }
    None
}

pub struct Reply {
    pub status: u16,
    pub body: Vec<u8>,
    pub ech: Vec<u8>,
}

pub async fn get(ip: IpAddr, host: &str, path: &str, key: &[u8], limit: usize) -> Result<Reply> {
    tokio::time::timeout(Duration::from_secs(3), async {
        let mut key = key.to_vec();
        for attempt in 0..2 {
            let mut config = dns::tls_config(Some(key.clone()))?;
            config.alpn_protocols = vec![b"http/1.1".to_vec()];
            let tcp = TcpStream::connect(SocketAddr::new(ip, 443))
                .await
                .map_err(|_| "probe_connect_failed")?;
            let _ = tcp.set_nodelay(true);
            let connector = TlsConnector::from(Arc::new(config));
            let name = ServerName::try_from(host.to_owned()).map_err(|_| "invalid_hostname")?;
            let tls = match connector.connect(name, tcp).await {
                Ok(tls) => tls,
                Err(error) => {
                    if attempt == 0 {
                        if let Some(fresh) = retry_config(&error) {
                            key = fresh;
                            continue;
                        }
                    }
                    return Err("probe_tls_failed");
                }
            };
            if tls.get_ref().1.ech_status() != EchStatus::Accepted {
                return Err("ech_rejected");
            }
            let (mut sender, connection) = hyper::client::conn::http1::handshake(TokioIo::new(tls))
                .await
                .map_err(|_| "probe_http_failed")?;
            // Dropping the request (timeout/cancellation) also aborts the connection driver.
            let driver = AbortOnDrop(tokio::spawn(async move {
                let _ = connection.await;
            }));
            let request = hyper::Request::builder()
                .uri(path)
                .header("Host", host)
                .header("User-Agent", "Kudo-Edge-Check/1")
                .header("Accept-Encoding", "identity")
                .body(Empty::<Bytes>::new())
                .map_err(|_| "probe_request_failed")?;
            let response = sender
                .send_request(request)
                .await
                .map_err(|_| "probe_http_failed")?;
            let status = response.status().as_u16();
            let mut source = response.into_body();
            let mut body = Vec::new();
            while let Some(frame) = source.frame().await {
                if let Ok(data) = frame.map_err(|_| "probe_body_failed")?.into_data() {
                    if body.len() + data.len() > limit {
                        return Err("probe_body_too_large");
                    }
                    body.extend_from_slice(&data);
                }
            }
            drop(driver);
            return Ok(Reply {
                status,
                body,
                ech: key,
            });
        }
        Err("ech_rejected")
    })
    .await
    .map_err(|_| "probe_timeout")?
}

struct AbortOnDrop(tokio::task::JoinHandle<()>);
impl Drop for AbortOnDrop {
    fn drop(&mut self) {
        self.0.abort();
    }
}

pub fn trace_matches(status: u16, body: &[u8]) -> bool {
    status == 200
        && std::str::from_utf8(body).is_ok_and(|text| {
            let hosts: Vec<_> = text
                .lines()
                .filter_map(|line| line.strip_prefix("h="))
                .collect();
            hosts == ["linux.do"]
        })
}

pub async fn probe(ip: IpAddr, key: &[u8]) -> Result<(u64, Vec<u8>)> {
    let start = Instant::now();
    let reply = get(ip, "linux.do", "/cdn-cgi/trace", key, 8192).await?;
    if !trace_matches(reply.status, &reply.body) {
        return Err("probe_wrong_site");
    }
    Ok((start.elapsed().as_millis() as u64, reply.ech))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn trace_requires_success_and_one_exact_host_line() {
        assert!(trace_matches(200, b"fl=123\nh=linux.do\r\nip=1.2.3.4\n"));
        for body in [
            b"h=linux.do.evil\n".as_slice(),
            b"<html>h=linux.do</html>",
            b"h=linux.do\nh=evil\n",
            b"h=linux.do\xff",
        ] {
            assert!(!trace_matches(200, body));
        }
        assert!(!trace_matches(403, b"h=linux.do\n"));
    }
    #[test]
    fn certificate_and_unstructured_errors_never_supply_retry_keys() {
        assert!(
            retry_config(&io::Error::other(rustls::Error::InvalidCertificate(
                rustls::CertificateError::UnknownIssuer
            )))
            .is_none()
        );
        assert!(retry_config(&io::Error::other("ServerRejectedEncryptedClientHello")).is_none());
        assert!(
            retry_config(&io::Error::other(rustls::Error::PeerIncompatible(
                rustls::PeerIncompatible::ServerRejectedEncryptedClientHello(Some(vec![])),
            )))
            .is_none()
        );
    }
    #[test]
    fn authenticated_rejection_preserves_the_complete_config_list() {
        use base64::Engine;
        use rustls::internal::msgs::{codec::Codec, handshake::EchConfigPayload};
        let wire = base64::engine::general_purpose::STANDARD.decode("AEX+DQBBvgAgACAnc+9IO/WWQSMj7W2mLvaFlY3UbvRlOsLeK+qbQ6pgVwAEAAEAAQASY2xvdWRmbGFyZS1lY2guY29tAAA=").unwrap();
        let configs = Vec::<EchConfigPayload>::read_bytes(&wire).unwrap();
        let error = io::Error::other(rustls::Error::PeerIncompatible(
            rustls::PeerIncompatible::ServerRejectedEncryptedClientHello(Some(configs)),
        ));
        assert_eq!(retry_config(&error), Some(wire));
    }
}
