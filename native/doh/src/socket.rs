use crate::dns::{self, Resolver};
use base64::{engine::general_purpose::STANDARD, Engine};
use rustls::{client::EchStatus, pki_types::ServerName, ClientConnection, StreamOwned};
use serde_json::{json, Value};
use std::{
    io::{self, Read, Write},
    net::{IpAddr, Shutdown, SocketAddr, TcpStream},
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        Arc, Mutex,
    },
    time::{Duration, Instant},
};

#[derive(Clone)]
struct SharedTcp {
    tcp: Arc<TcpStream>,
    closed: Arc<AtomicBool>,
    read_timeout: Arc<AtomicU64>,
}
impl Read for SharedTcp {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let millis = self.read_timeout.load(Ordering::SeqCst);
        self.tcp
            .set_read_timeout(Some(Duration::from_millis(if millis == 0 {
                200
            } else {
                millis.min(200)
            })))?;
        let start = Instant::now();
        loop {
            if self.closed.load(Ordering::SeqCst) {
                return Err(other("socket_closed"));
            }
            match (&*self.tcp).read(buf) {
                Err(e)
                    if matches!(
                        e.kind(),
                        io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock
                    ) =>
                {
                    if millis > 0 && start.elapsed() >= Duration::from_millis(millis) {
                        return Err(e);
                    }
                }
                result => return result,
            }
        }
    }
}
impl Write for SharedTcp {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        let start = Instant::now();
        loop {
            if self.closed.load(Ordering::SeqCst) {
                return Err(other("socket_closed"));
            }
            match (&*self.tcp).write(buf) {
                Err(e)
                    if matches!(
                        e.kind(),
                        io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock
                    ) && start.elapsed() < Duration::from_secs(30) => {}
                result => return result,
            }
        }
    }
    fn flush(&mut self) -> io::Result<()> {
        (&*self.tcp).flush()
    }
}
enum State {
    Empty,
    Plain(SharedTcp),
    Tls(Box<ClientConnection>),
}
struct EncryptedInput {
    bytes: [u8; 16384],
    filled: usize,
    consumed: usize,
}
pub struct Socket {
    resolver: Arc<Resolver>,
    state: Mutex<State>,
    // Shutdown must never wait for the mutex held by a blocking read/write.
    shutdown: Mutex<Option<Arc<TcpStream>>>,
    closed: Arc<AtomicBool>,
    read_timeout: Arc<AtomicU64>,
    read_lock: Mutex<EncryptedInput>,
    write_lock: Mutex<()>,
}

fn other(code: &'static str) -> io::Error {
    crate::code_error(code)
}

#[derive(Clone, Copy)]
enum IoStage {
    Connect,
    Read,
    Write,
}

fn io_failure(stage: IoStage, error: io::Error) -> io::Error {
    if error
        .get_ref()
        .and_then(|e| e.downcast_ref::<crate::SafeCode>())
        .is_some()
    {
        return error;
    }
    use io::ErrorKind::*;
    let code = match (stage, error.kind()) {
        (IoStage::Connect, TimedOut | WouldBlock) => "connect_timeout",
        (IoStage::Connect, ConnectionRefused) => "connect_refused",
        (IoStage::Connect, ConnectionReset | ConnectionAborted) => "connect_reset",
        (IoStage::Connect, NetworkUnreachable | HostUnreachable | AddrNotAvailable) => {
            "connect_unreachable"
        }
        (IoStage::Connect, _) => "connect_failed",
        (IoStage::Read, TimedOut | WouldBlock) => "read_timeout",
        (IoStage::Read, ConnectionReset | ConnectionAborted) => "read_reset",
        (IoStage::Read, UnexpectedEof) => "read_eof",
        (IoStage::Read, _) => "read_failed",
        (IoStage::Write, TimedOut | WouldBlock) => "write_timeout",
        (IoStage::Write, ConnectionReset | ConnectionAborted) => "write_reset",
        (IoStage::Write, BrokenPipe | NotConnected) => "write_broken_pipe",
        (IoStage::Write, _) => "write_failed",
    };
    io::Error::new(error.kind(), crate::SafeCode(code))
}

impl Socket {
    pub fn new(resolver: Arc<Resolver>) -> Self {
        Self {
            resolver,
            state: Mutex::new(State::Empty),
            shutdown: Mutex::new(None),
            closed: Arc::new(AtomicBool::new(false)),
            read_timeout: Arc::new(AtomicU64::new(0)),
            read_lock: Mutex::new(EncryptedInput {
                bytes: [0; 16384],
                filled: 0,
                consumed: 0,
            }),
            write_lock: Mutex::new(()),
        }
    }
    pub fn connect(&self, ip: &str, port: u16, millis: u64) -> io::Result<()> {
        self.connect_inner(ip, port, millis)
            .map_err(|e| io_failure(IoStage::Connect, e))
    }
    fn connect_inner(&self, ip: &str, port: u16, millis: u64) -> io::Result<()> {
        if self.closed.load(Ordering::SeqCst) {
            return Err(other("socket_closed"));
        }
        let ip = ip.parse::<IpAddr>().map_err(|_| other("invalid_ip"))?;
        let tcp = TcpStream::connect_timeout(
            &SocketAddr::new(ip, port),
            Duration::from_millis(millis.max(1)),
        )?;
        tcp.set_nodelay(true)?;
        tcp.set_write_timeout(Some(Duration::from_millis(200)))?;
        let mut shutdown = self.shutdown.lock().map_err(|_| other("socket_lock"))?;
        if self.closed.load(Ordering::SeqCst) {
            let _ = tcp.shutdown(Shutdown::Both);
            return Err(other("socket_closed"));
        }
        let tcp = Arc::new(tcp);
        *shutdown = Some(tcp.clone());
        *self.state.lock().map_err(|_| other("socket_lock"))? = State::Plain(SharedTcp {
            tcp,
            closed: self.closed.clone(),
            read_timeout: self.read_timeout.clone(),
        });
        Ok(())
    }
    pub fn handshake(&self, host: &str, alpn: &str) -> io::Result<Value> {
        let answer = crate::runtime()
            .block_on(self.resolver.resolve(host))
            .map_err(other)?;
        let use_ech = !answer.ech.is_empty();
        if dns::requires_ech(host) && !use_ech {
            return Err(other("ech_config_missing"));
        }
        let mut config =
            dns::tls_config(if use_ech { Some(answer.ech) } else { None }).map_err(other)?;
        config.alpn_protocols = alpn
            .split(',')
            .map(|value| {
                if !["h2", "http/1.1"].contains(&value) {
                    return Err(other("invalid_alpn"));
                }
                Ok(value.as_bytes().to_vec())
            })
            .collect::<io::Result<_>>()?;
        self.finish_handshake(host, config, use_ech)
    }
    fn finish_handshake(
        &self,
        host: &str,
        config: rustls::ClientConfig,
        use_ech: bool,
    ) -> io::Result<Value> {
        let name = ServerName::try_from(host.to_owned()).map_err(|_| other("invalid_hostname"))?;
        let mut state = self.state.lock().map_err(|_| other("socket_lock"))?;
        let tcp = match std::mem::replace(&mut *state, State::Empty) {
            State::Plain(tcp) => tcp,
            other_state => {
                *state = other_state;
                return Err(other("socket_not_plain"));
            }
        };
        let conn = ClientConnection::new(Arc::new(config), name)
            .map_err(|_| other("tls_configuration_failed"))?;
        let mut stream = StreamOwned::new(conn, tcp);
        while stream.conn.is_handshaking() {
            if let Err(e) = stream.conn.complete_io(&mut stream.sock) {
                if stream.conn.ech_status() == EchStatus::Rejected {
                    self.resolver.invalidate(host);
                    return Err(other("ech_rejected"));
                }
                if matches!(
                    e.kind(),
                    io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock
                ) {
                    return Err(e);
                }
                return Err(other("tls_handshake_or_certificate_failed"));
            }
        }
        if use_ech && stream.conn.ech_status() != EchStatus::Accepted {
            return Err(other("ech_rejected"));
        }
        let certs: Vec<_> = stream
            .conn
            .peer_certificates()
            .ok_or(other("tls_certificate_missing"))?
            .iter()
            .map(|c| STANDARD.encode(c.as_ref()))
            .collect();
        let protocol = match stream.conn.protocol_version() {
            Some(rustls::ProtocolVersion::TLSv1_3) => "TLSv1.3",
            Some(rustls::ProtocolVersion::TLSv1_2) => "TLSv1.2",
            _ => return Err(other("tls_version_invalid")),
        };
        let cipher = format!(
            "{:?}",
            stream
                .conn
                .negotiated_cipher_suite()
                .ok_or(other("tls_cipher_missing"))?
                .suite()
        )
        .replace("TLS13_", "TLS_");
        let selected = String::from_utf8_lossy(stream.conn.alpn_protocol().unwrap_or(b"http/1.1"))
            .into_owned();
        let metadata = json!({"protocol":protocol,"cipher":cipher,"certs":certs,"alpn":selected,"ech":if use_ech {"Accepted"} else {"NotOffered"}});
        *state = State::Tls(Box::new(stream.conn));
        Ok(metadata)
    }
    pub fn read(&self, buf: &mut [u8]) -> io::Result<usize> {
        self.read_inner(buf)
            .map_err(|e| io_failure(IoStage::Read, e))
    }
    fn read_inner(&self, buf: &mut [u8]) -> io::Result<usize> {
        let mut input = self.read_lock.lock().map_err(|_| other("socket_lock"))?;
        let mut tcp = self.tcp()?;
        loop {
            let needs_network = {
                let mut state = self.state.lock().map_err(|_| other("socket_lock"))?;
                match &mut *state {
                    State::Plain(_) => {
                        drop(state);
                        return tcp.read(buf);
                    }
                    State::Tls(conn) => {
                        match conn.reader().read(buf) {
                            Err(e) if e.kind() == io::ErrorKind::WouldBlock => {}
                            result => return result,
                        }
                        // Drain plaintext before feeding more records. TCP may combine
                        // a large record's tail with small records, filling rustls's
                        // plaintext buffer before this batch of ciphertext is consumed.
                        if input.consumed < input.filled {
                            let mut encrypted =
                                io::Cursor::new(&input.bytes[input.consumed..input.filled]);
                            let count = conn.read_tls(&mut encrypted)?;
                            if count == 0 {
                                return Err(other("tls_record_incomplete"));
                            }
                            input.consumed += count;
                            conn.process_new_packets()
                                .map_err(|_| other("tls_record_invalid"))?;
                            false
                        } else {
                            true
                        }
                    }
                    State::Empty => return Err(other("socket_not_connected")),
                }
            };
            if needs_network {
                // Network I/O must never hold the TLS state mutex: HTTP/2 has independent readers/writers.
                input.filled = tcp.read(&mut input.bytes)?;
                input.consumed = 0;
                if input.filled == 0 {
                    let mut state = self.state.lock().map_err(|_| other("socket_lock"))?;
                    if let State::Tls(conn) = &mut *state {
                        conn.read_tls(&mut io::empty())?;
                    }
                }
                continue;
            }
            // TLS key updates/session tickets may produce outbound records, ordered with application writes.
            let _writer = self.write_lock.lock().map_err(|_| other("socket_lock"))?;
            tcp.write_all(&self.drain_tls()?)?;
        }
    }
    pub fn write(&self, buf: &[u8]) -> io::Result<()> {
        self.write_inner(buf)
            .map_err(|e| io_failure(IoStage::Write, e))
    }
    fn write_inner(&self, buf: &[u8]) -> io::Result<()> {
        let _writer = self.write_lock.lock().map_err(|_| other("socket_lock"))?;
        let mut tcp = self.tcp()?;
        {
            let mut state = self.state.lock().map_err(|_| other("socket_lock"))?;
            match &mut *state {
                State::Plain(_) => {
                    drop(state);
                    return tcp.write_all(buf);
                }
                State::Tls(conn) => conn.writer().write_all(buf)?,
                State::Empty => return Err(other("socket_not_connected")),
            }
        }
        tcp.write_all(&self.drain_tls()?)
    }
    fn drain_tls(&self) -> io::Result<Vec<u8>> {
        let mut output = Vec::new();
        let mut state = self.state.lock().map_err(|_| other("socket_lock"))?;
        if let State::Tls(conn) = &mut *state {
            while conn.wants_write() {
                conn.write_tls(&mut output)?;
            }
        }
        Ok(output)
    }
    fn tcp(&self) -> io::Result<SharedTcp> {
        let tcp = self
            .shutdown
            .lock()
            .map_err(|_| other("socket_lock"))?
            .clone()
            .ok_or(other("socket_closed"))?;
        Ok(SharedTcp {
            tcp,
            closed: self.closed.clone(),
            read_timeout: self.read_timeout.clone(),
        })
    }
    pub fn timeout(&self, millis: u64) -> io::Result<()> {
        self.read_timeout.store(millis, Ordering::SeqCst);
        Ok(())
    }
    pub fn close(&self) {
        self.closed.store(true, Ordering::SeqCst);
        if let Ok(mut tcp) = self.shutdown.lock() {
            if let Some(tcp) = tcp.take() {
                let _ = tcp.shutdown(Shutdown::Both);
            }
        }
    }
}
impl Drop for Socket {
    fn drop(&mut self) {
        self.close();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{net::TcpListener, thread, time::Instant};
    #[test]
    fn io_codes_preserve_stages_kinds_and_controlled_failures() {
        use io::ErrorKind::*;
        let cases = [
            (IoStage::Connect, ConnectionRefused, "connect_refused"),
            (IoStage::Connect, ConnectionReset, "connect_reset"),
            (IoStage::Connect, NetworkUnreachable, "connect_unreachable"),
            (IoStage::Connect, TimedOut, "connect_timeout"),
            (IoStage::Connect, Other, "connect_failed"),
            (IoStage::Read, ConnectionReset, "read_reset"),
            (IoStage::Read, WouldBlock, "read_timeout"),
            (IoStage::Read, UnexpectedEof, "read_eof"),
            (IoStage::Read, Other, "read_failed"),
            (IoStage::Write, ConnectionReset, "write_reset"),
            (IoStage::Write, BrokenPipe, "write_broken_pipe"),
            (IoStage::Write, TimedOut, "write_timeout"),
            (IoStage::Write, Other, "write_failed"),
        ];
        for (stage, kind, code) in cases {
            let error = io_failure(
                stage,
                io::Error::new(kind, "private URL / Cookie / node parameter"),
            );
            assert_eq!(error.kind(), kind);
            assert_eq!(error.to_string(), code);
        }
        for stage in [IoStage::Connect, IoStage::Read, IoStage::Write] {
            assert_eq!(
                io_failure(stage, other("socket_closed")).to_string(),
                "socket_closed"
            );
            assert_eq!(
                io_failure(stage, other("tls_record_invalid")).to_string(),
                "tls_record_invalid"
            );
        }
    }
    fn connected() -> (Arc<Socket>, TcpStream) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let socket = Arc::new(Socket::new(Arc::new(
            Resolver::new(r#"{"url":"https://dns.test/query"}"#).unwrap(),
        )));
        socket
            .connect("127.0.0.1", listener.local_addr().unwrap().port(), 1000)
            .unwrap();
        let peer = listener.accept().unwrap().0;
        (socket, peer)
    }
    #[test]
    fn connect_failure_has_a_safe_stage_and_reason() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        drop(listener);
        let socket = Socket::new(Arc::new(
            Resolver::new(r#"{"url":"https://dns.test/query"}"#).unwrap(),
        ));
        let error = socket.connect("127.0.0.1", port, 1000).unwrap_err();
        // Windows can report a refused local connect as a timeout.
        let expected = match error.kind() {
            io::ErrorKind::ConnectionRefused => "connect_refused",
            io::ErrorKind::TimedOut => "connect_timeout",
            kind => panic!("unexpected local connect error: {kind:?}"),
        };
        assert_eq!(
            error
                .get_ref()
                .and_then(|e| e.downcast_ref::<crate::SafeCode>())
                .map(|c| c.0),
            Some(expected)
        );
    }
    #[test]
    fn close_interrupts_a_blocking_read_without_waiting_for_state_lock() {
        let (socket, _peer) = connected();
        let reader = socket.clone();
        let (sender, result) = std::sync::mpsc::channel();
        thread::spawn(move || {
            let _ = sender.send(reader.read(&mut [0; 8]));
        });
        thread::sleep(Duration::from_millis(30));
        let start = Instant::now();
        socket.close();
        assert!(result
            .recv_timeout(Duration::from_secs(1))
            .expect("close must interrupt read")
            .map(|n| n == 0)
            .unwrap_or(true));
        assert!(start.elapsed() < Duration::from_secs(1));
    }
    #[test]
    fn streams_bytes_and_read_timeout_is_not_a_reconnect() {
        let (socket, mut peer) = connected();
        socket.write(b"one request").unwrap();
        let mut bytes = [0; 11];
        peer.read_exact(&mut bytes).unwrap();
        assert_eq!(&bytes, b"one request");
        socket.timeout(30).unwrap();
        assert!(matches!(
            socket.read(&mut [0; 1]).unwrap_err().kind(),
            io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock
        ));
        peer.write_all(b"ok").unwrap();
        assert_eq!(socket.read(&mut bytes).unwrap(), 2);
    }
    #[test]
    fn read_does_not_block_independent_writes() {
        let (socket, mut peer) = connected();
        socket.timeout(2000).unwrap();
        let reader = socket.clone();
        std::thread::spawn(move || {
            let _ = reader.read(&mut [0; 1]);
        });
        std::thread::sleep(Duration::from_millis(30));
        let writer = socket.clone();
        let (tx, rx) = std::sync::mpsc::channel();
        std::thread::spawn(move || {
            let _ = tx.send(writer.write(b"request"));
        });
        let result = rx.recv_timeout(Duration::from_millis(500));
        socket.close();
        assert!(
            result.is_ok(),
            "HTTP/2 must write while its reader waits for data"
        );
        result.unwrap().unwrap();
        peer.set_read_timeout(Some(Duration::from_secs(1))).unwrap();
        let mut data = [0; 7];
        peer.read_exact(&mut data).unwrap();
        assert_eq!(&data, b"request");
    }
    #[test]
    fn large_tls_response_consumes_every_encrypted_byte() {
        // Public, test-only self-signed key/certificate. Not included in production or its trust store.
        use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
        let cert = CertificateDer::from(include_bytes!("test-cert.der").to_vec());
        let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(
            include_bytes!("test-key.der").to_vec(),
        ));
        let server_config = rustls::ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(vec![cert.clone()], key)
            .unwrap();
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let payload: Vec<u8> = (0..512 * 1024).map(|i| (i % 251) as u8).collect();
        let expected = payload.clone();
        let server = thread::spawn(move || {
            let tcp = listener.accept().unwrap().0;
            tcp.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
            tcp.set_write_timeout(Some(Duration::from_secs(3))).unwrap();
            let conn = rustls::ServerConnection::new(Arc::new(server_config)).unwrap();
            let mut stream = StreamOwned::new(conn, tcp);
            let _ = stream.write_all(&payload);
            let _ = stream.flush();
        });
        let socket = Socket::new(Arc::new(
            Resolver::new(r#"{"url":"https://dns.test/"}"#).unwrap(),
        ));
        socket.connect("127.0.0.1", port, 1000).unwrap();
        socket.timeout(2000).unwrap();
        let mut roots = rustls::RootCertStore::empty();
        roots.add(cert).unwrap();
        let config = rustls::ClientConfig::builder()
            .with_root_certificates(roots)
            .with_no_client_auth();
        socket.finish_handshake("localhost", config, false).unwrap();
        let mut received = Vec::new();
        let mut block = [0; 8192];
        while received.len() < expected.len() {
            let count = socket.read(&mut block).unwrap();
            assert!(count > 0, "TLS response must not truncate");
            received.extend_from_slice(&block[..count]);
        }
        socket.close();
        server.join().unwrap();
        assert_eq!(expected, received);
    }
    #[test]
    fn mixed_tls_records_after_a_fragment_do_not_fail_with_read_failed() {
        for versions in [vec![&rustls::version::TLS13], vec![&rustls::version::TLS12]] {
            let (mut client, mut server) = tls_pair(&versions);
            let first = vec![17; 16384];
            server.writer().write_all(&first).unwrap();
            let first_record = encoded(&mut server);
            // Leave the final 128 encrypted bytes for the next TCP read. That read
            // also contains small records and the prefix of another large record.
            let prefix = first_record.len() - 128;
            let mut input = io::Cursor::new(&first_record[..prefix]);
            while (input.position() as usize) < prefix {
                assert!(client.read_tls(&mut input).unwrap() > 0);
                client.process_new_packets().unwrap();
            }
            assert_eq!(
                client.reader().read(&mut [0; 1]).unwrap_err().kind(),
                io::ErrorKind::WouldBlock
            );
            let mut expected = first;
            let mut encrypted = first_record[prefix..].to_vec();
            for (index, size) in [512, 1024, 16384, 37, 8192].into_iter().enumerate() {
                let plaintext = vec![index as u8; size];
                server.writer().write_all(&plaintext).unwrap();
                expected.extend_from_slice(&plaintext);
                encrypted.extend(encoded(&mut server));
            }
            server.send_close_notify();
            encrypted.extend(encoded(&mut server));
            let (socket, mut peer) = connected();
            *socket.state.lock().unwrap() = State::Tls(Box::new(client));
            socket.timeout(2000).unwrap();
            peer.write_all(&encrypted).unwrap();
            let mut actual = Vec::new();
            let mut block = [0; 257];
            loop {
                let count = socket
                    .read(&mut block)
                    .expect("valid mixed TLS records must be readable");
                if count == 0 {
                    break;
                }
                actual.extend_from_slice(&block[..count]);
            }
            assert_eq!(expected, actual);
        }
    }

    #[test]
    fn repeated_tls_responses_keep_every_byte_with_varying_read_sizes() {
        let (client, mut server) = tls_pair(&[&rustls::version::TLS13]);
        let mut expected = Vec::new();
        let mut encrypted = Vec::new();
        // One TLS connection, repeated header-sized and image-sized records.
        for round in 0..32 {
            for size in [79, 16384, 513, 8192, 9] {
                let bytes = vec![round; size];
                server.writer().write_all(&bytes).unwrap();
                expected.extend_from_slice(&bytes);
                encrypted.extend(encoded(&mut server));
            }
        }
        server.send_close_notify();
        encrypted.extend(encoded(&mut server));
        let (socket, mut peer) = connected();
        *socket.state.lock().unwrap() = State::Tls(Box::new(client));
        socket.timeout(2000).unwrap();
        let writer = thread::spawn(move || peer.write_all(&encrypted).unwrap());
        let mut received = Vec::new();
        let mut block = [0; 32768];
        let mut iteration = 0;
        loop {
            let size = [1, 257, 4096, 32768][iteration % 4];
            let count = socket.read(&mut block[..size]).unwrap();
            if count == 0 {
                break;
            }
            received.extend_from_slice(&block[..count]);
            assert!(received.len() <= expected.len());
            iteration += 1;
        }
        writer.join().unwrap();
        assert_eq!(expected, received);
    }

    #[test]
    fn tls_record_header_and_payload_fragments_preserve_bytes() {
        for version in [&rustls::version::TLS13, &rustls::version::TLS12] {
            for prefix in [1, 4, 5, 17, 4095, 4096, 8191, 16383] {
                let (mut client, mut server) = tls_pair(&[version]);
                let expected = vec![37; 16384];
                server.writer().write_all(&expected).unwrap();
                let encrypted = encoded(&mut server);
                let mut input = io::Cursor::new(&encrypted[..prefix]);
                while (input.position() as usize) < prefix {
                    assert!(client.read_tls(&mut input).unwrap() > 0);
                    client.process_new_packets().unwrap();
                }
                let (socket, mut peer) = connected();
                *socket.state.lock().unwrap() = State::Tls(Box::new(client));
                socket.timeout(2000).unwrap();
                peer.write_all(&encrypted[prefix..]).unwrap();
                peer.write_all(&{
                    server.send_close_notify();
                    encoded(&mut server)
                })
                .unwrap();
                let mut actual = Vec::new();
                let mut block = [0; 127];
                loop {
                    let count = socket.read(&mut block).unwrap();
                    if count == 0 {
                        break;
                    }
                    actual.extend_from_slice(&block[..count]);
                }
                assert_eq!(expected, actual, "fragment prefix {prefix}");
            }
        }
    }

    #[test]
    fn truncated_and_corrupt_tls_records_still_fail() {
        for corrupt in [false, true] {
            let (client, mut server) = tls_pair(&[&rustls::version::TLS13]);
            server.writer().write_all(&vec![19; 8192]).unwrap();
            let mut encrypted = encoded(&mut server);
            if corrupt {
                *encrypted.last_mut().unwrap() ^= 1;
            } else {
                encrypted.pop();
            }
            let (socket, mut peer) = connected();
            *socket.state.lock().unwrap() = State::Tls(Box::new(client));
            socket.timeout(2000).unwrap();
            peer.write_all(&encrypted).unwrap();
            peer.shutdown(Shutdown::Write).unwrap();
            let error = socket.read(&mut [0; 8192]).unwrap_err();
            assert_eq!(
                error.to_string(),
                if corrupt {
                    "tls_record_invalid"
                } else {
                    "read_eof"
                }
            );
        }
    }

    #[test]
    fn a_waiting_tls_reader_does_not_block_application_writes() {
        let (client, mut server) = tls_pair(&[&rustls::version::TLS13]);
        let (socket, mut peer) = connected();
        *socket.state.lock().unwrap() = State::Tls(Box::new(client));
        socket.timeout(2000).unwrap();
        peer.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        let reader = socket.clone();
        let reading = thread::spawn(move || reader.read(&mut [0; 1]));
        thread::sleep(Duration::from_millis(30));
        let writer = socket.clone();
        let (tx, rx) = std::sync::mpsc::channel();
        let writing = thread::spawn(move || tx.send(writer.write(b"next stream")).unwrap());
        let result = rx.recv_timeout(Duration::from_millis(500));
        socket.close();
        assert!(
            result.is_ok(),
            "TLS reader must release the connection state lock while waiting"
        );
        result.unwrap().unwrap();
        let mut plaintext = [0; 11];
        loop {
            server.read_tls(&mut peer).unwrap();
            server.process_new_packets().unwrap();
            if server.reader().read(&mut plaintext).is_ok() {
                break;
            }
        }
        assert_eq!(&plaintext, b"next stream");
        writing.join().unwrap();
        let _ = reading.join().unwrap();
    }

    fn tls_pair(
        versions: &[&'static rustls::SupportedProtocolVersion],
    ) -> (ClientConnection, rustls::ServerConnection) {
        use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
        let cert = CertificateDer::from(include_bytes!("test-cert.der").to_vec());
        let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(
            include_bytes!("test-key.der").to_vec(),
        ));
        let server_config = rustls::ServerConfig::builder_with_protocol_versions(versions)
            .with_no_client_auth()
            .with_single_cert(vec![cert.clone()], key)
            .unwrap();
        let mut roots = rustls::RootCertStore::empty();
        roots.add(cert).unwrap();
        let client_config = rustls::ClientConfig::builder_with_protocol_versions(versions)
            .with_root_certificates(roots)
            .with_no_client_auth();
        let mut client = ClientConnection::new(
            Arc::new(client_config),
            ServerName::try_from("localhost").unwrap(),
        )
        .unwrap();
        let mut server = rustls::ServerConnection::new(Arc::new(server_config)).unwrap();
        for _ in 0..16 {
            let mut outbound = Vec::new();
            while client.wants_write() {
                client.write_tls(&mut outbound).unwrap();
            }
            let mut input = io::Cursor::new(&outbound);
            while (input.position() as usize) < outbound.len() {
                assert!(server.read_tls(&mut input).unwrap() > 0);
                server.process_new_packets().unwrap();
            }
            let outbound = encoded(&mut server);
            let mut input = io::Cursor::new(&outbound);
            while (input.position() as usize) < outbound.len() {
                assert!(client.read_tls(&mut input).unwrap() > 0);
                client.process_new_packets().unwrap();
            }
            if !client.is_handshaking()
                && !server.is_handshaking()
                && !client.wants_write()
                && !server.wants_write()
            {
                return (client, server);
            }
        }
        panic!("test TLS handshake did not finish");
    }

    fn encoded(server: &mut rustls::ServerConnection) -> Vec<u8> {
        let mut bytes = Vec::new();
        while server.wants_write() {
            server.write_tls(&mut bytes).unwrap();
        }
        bytes
    }

    #[test]
    fn closed_socket_cannot_start_a_connection() {
        let socket = Socket::new(Arc::new(
            Resolver::new(r#"{"url":"https://dns.test/query"}"#).unwrap(),
        ));
        socket.close();
        assert!(socket.connect("127.0.0.1", 9, 100).is_err());
    }
}
