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
pub struct Socket {
    resolver: Arc<Resolver>,
    state: Mutex<State>,
    // Shutdown must never wait for the mutex held by a blocking read/write.
    shutdown: Mutex<Option<Arc<TcpStream>>>,
    closed: Arc<AtomicBool>,
    read_timeout: Arc<AtomicU64>,
    read_lock: Mutex<()>,
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
            read_lock: Mutex::new(()),
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
        let _reader = self.read_lock.lock().map_err(|_| other("socket_lock"))?;
        let mut tcp = self.tcp()?;
        loop {
            {
                let mut state = self.state.lock().map_err(|_| other("socket_lock"))?;
                match &mut *state {
                    State::Plain(_) => {
                        drop(state);
                        return tcp.read(buf);
                    }
                    State::Tls(conn) => match conn.reader().read(buf) {
                        Err(e) if e.kind() == io::ErrorKind::WouldBlock => {}
                        result => return result,
                    },
                    State::Empty => return Err(other("socket_not_connected")),
                }
            }
            // Network I/O must never hold the TLS state mutex: HTTP/2 has independent readers/writers.
            let mut encrypted = [0u8; 16384];
            let count = tcp.read(&mut encrypted)?;
            {
                let mut state = self.state.lock().map_err(|_| other("socket_lock"))?;
                if let State::Tls(conn) = &mut *state {
                    let mut input = io::Cursor::new(&encrypted[..count]);
                    if count == 0 {
                        conn.read_tls(&mut input)?;
                    }
                    while (input.position() as usize) < count {
                        if conn.read_tls(&mut input)? == 0 {
                            return Err(other("tls_record_incomplete"));
                        }
                        conn.process_new_packets()
                            .map_err(|_| other("tls_record_invalid"))?;
                    }
                }
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
    fn closed_socket_cannot_start_a_connection() {
        let socket = Socket::new(Arc::new(
            Resolver::new(r#"{"url":"https://dns.test/query"}"#).unwrap(),
        ));
        socket.close();
        assert!(socket.connect("127.0.0.1", 9, 100).is_err());
    }
}
