use crate::{dns::Resolver, socket::Socket};
use jni::{
    objects::{JByteArray, JClass, JString},
    sys::{jint, jlong, jstring},
    JNIEnv,
};
use std::{
    collections::HashMap,
    io,
    sync::{
        atomic::{AtomicI64, Ordering},
        Arc, Mutex, OnceLock,
    },
};

static IDS: AtomicI64 = AtomicI64::new(1);
fn profiles() -> &'static Mutex<HashMap<i64, Arc<Resolver>>> {
    static VALUE: OnceLock<Mutex<HashMap<i64, Arc<Resolver>>>> = OnceLock::new();
    VALUE.get_or_init(|| Mutex::new(HashMap::new()))
}
fn sockets() -> &'static Mutex<HashMap<i64, Arc<Socket>>> {
    static VALUE: OnceLock<Mutex<HashMap<i64, Arc<Socket>>>> = OnceLock::new();
    VALUE.get_or_init(|| Mutex::new(HashMap::new()))
}
fn error(code: &'static str) -> io::Error {
    crate::code_error(code)
}
fn profile(id: i64) -> io::Result<Arc<Resolver>> {
    profiles()
        .lock()
        .map_err(|_| error("profile_lock"))?
        .get(&id)
        .cloned()
        .ok_or(error("profile_closed"))
}
fn socket(id: i64) -> io::Result<Arc<Socket>> {
    sockets()
        .lock()
        .map_err(|_| error("socket_lock"))?
        .get(&id)
        .cloned()
        .ok_or(error("socket_closed"))
}
fn string(env: &mut JNIEnv, input: &JString) -> io::Result<String> {
    env.get_string(input)
        .map(Into::into)
        .map_err(|_| error("jni_string"))
}
fn output(env: &mut JNIEnv, value: String) -> io::Result<jstring> {
    env.new_string(value)
        .map(|v| v.into_raw())
        .map_err(|_| error("jni_allocation"))
}

fn java_exception<'a>(class: &'a str, error: &io::Error) -> (&'a str, String) {
    let safe = error
        .get_ref()
        .and_then(|e| e.downcast_ref::<crate::SafeCode>())
        .map(|c| c.0);
    // Only controlled codes cross JNI; raw platform details can contain private parameters.
    let (class, code) = match error.kind() {
        io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock => (
            "java/net/SocketTimeoutException",
            safe.unwrap_or("socket_timeout"),
        ),
        _ => (class, safe.unwrap_or("connection_failed")),
    };
    (class, format!("DoH: {code}"))
}

fn guard<T: Default>(
    env: &mut JNIEnv,
    class: &str,
    action: impl FnOnce(&mut JNIEnv) -> io::Result<T>,
) -> T {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| action(env)))
        .unwrap_or_else(|_| Err(error("native_failure")));
    match result {
        Ok(value) => value,
        Err(e) => {
            let (class, message) = java_exception(class, &e);
            let _ = env.throw_new(class, message);
            T::default()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kudo_android_network_NativeDoh_createProfile(
    mut env: JNIEnv,
    _: JClass,
    config: JString,
) -> jlong {
    guard(&mut env, "java/io/IOException", |env| {
        let raw = string(env, &config)?;
        if raw.len() > 8192 {
            return Err(error("invalid_configuration"));
        }
        let resolver = Resolver::new(&raw).map_err(error)?;
        let mut profiles = profiles().lock().map_err(|_| error("profile_lock"))?;
        if profiles.len() >= 32 {
            return Err(error("too_many_profiles"));
        }
        let id = IDS.fetch_add(1, Ordering::Relaxed);
        profiles.insert(id, Arc::new(resolver));
        Ok(id)
    })
}
#[no_mangle]
pub extern "system" fn Java_com_kudo_android_network_NativeDoh_destroyProfile(
    _env: JNIEnv,
    _: JClass,
    id: jlong,
) {
    if let Ok(mut profiles) = profiles().lock() {
        profiles.remove(&id);
    }
}
#[no_mangle]
pub extern "system" fn Java_com_kudo_android_network_NativeDoh_lookup(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
    host: JString,
) -> jstring {
    guard(&mut env, "java/net/UnknownHostException", |env| {
        let host = string(env, &host)?;
        if host.len() > 253 {
            return Err(error("invalid_hostname"));
        }
        let answer = crate::runtime()
            .block_on(profile(id)?.resolve(&host))
            .map_err(error)?;
        output(env, serde_json::json!({"ips":answer.ips.iter().map(ToString::to_string).collect::<Vec<_>>(),"hasEch":!answer.ech.is_empty()}).to_string())
    })
}
#[no_mangle]
pub extern "system" fn Java_com_kudo_android_network_NativeDoh_openSocket(
    mut env: JNIEnv,
    _: JClass,
    profile_id: jlong,
) -> jlong {
    guard(&mut env, "java/io/IOException", |_| {
        let socket = Arc::new(Socket::new(profile(profile_id)?));
        let mut sockets = sockets().lock().map_err(|_| error("socket_lock"))?;
        if sockets.len() >= 1024 {
            return Err(error("too_many_sockets"));
        }
        let id = IDS.fetch_add(1, Ordering::Relaxed);
        sockets.insert(id, socket);
        Ok(id)
    })
}
#[no_mangle]
pub extern "system" fn Java_com_kudo_android_network_NativeDoh_connect(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
    ip: JString,
    port: jint,
    millis: jint,
) {
    guard(&mut env, "java/io/IOException", |env| {
        if !(1..=65535).contains(&port) || millis < 0 {
            return Err(error("invalid_socket_address"));
        }
        socket(id)?.connect(
            &string(env, &ip)?,
            port as u16,
            if millis == 0 { 30_000 } else { millis as u64 },
        )
    });
}
#[no_mangle]
pub extern "system" fn Java_com_kudo_android_network_NativeDoh_handshake(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
    host: JString,
    alpn: JString,
) -> jstring {
    guard(&mut env, "javax/net/ssl/SSLHandshakeException", |env| {
        let metadata = socket(id)?.handshake(&string(env, &host)?, &string(env, &alpn)?)?;
        output(env, metadata.to_string())
    })
}
fn bounds(env: &JNIEnv, bytes: &JByteArray, offset: jint, length: jint) -> io::Result<()> {
    let count = env
        .get_array_length(bytes)
        .map_err(|_| error("jni_array"))?;
    if offset < 0 || length < 0 || offset > count || length > count - offset || length > 65536 {
        return Err(error("invalid_buffer_bounds"));
    }
    Ok(())
}
#[no_mangle]
pub extern "system" fn Java_com_kudo_android_network_NativeDoh_read(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
    bytes: JByteArray,
    offset: jint,
    length: jint,
) -> jint {
    guard(&mut env, "java/io/IOException", |env| {
        bounds(env, &bytes, offset, length)?;
        if length == 0 {
            return Ok(0);
        }
        let mut buffer = vec![0; length as usize];
        let count = socket(id)?.read(&mut buffer)?;
        if count == 0 {
            return Ok(-1);
        }
        let data: Vec<i8> = buffer[..count].iter().map(|b| *b as i8).collect();
        env.set_byte_array_region(&bytes, offset, &data)
            .map_err(|_| error("jni_array"))?;
        Ok(count as jint)
    })
}
#[no_mangle]
pub extern "system" fn Java_com_kudo_android_network_NativeDoh_write(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
    bytes: JByteArray,
    offset: jint,
    length: jint,
) {
    guard(&mut env, "java/io/IOException", |env| {
        bounds(env, &bytes, offset, length)?;
        let mut buffer = vec![0i8; length as usize];
        env.get_byte_array_region(&bytes, offset, &mut buffer)
            .map_err(|_| error("jni_array"))?;
        let buffer: Vec<u8> = buffer.into_iter().map(|b| b as u8).collect();
        socket(id)?.write(&buffer)
    });
}
#[no_mangle]
pub extern "system" fn Java_com_kudo_android_network_NativeDoh_timeout(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
    millis: jint,
) {
    guard(&mut env, "java/io/IOException", |_| {
        if millis < 0 {
            return Err(error("invalid_timeout"));
        }
        socket(id)?.timeout(millis as u64)
    });
}
#[no_mangle]
pub extern "system" fn Java_com_kudo_android_network_NativeDoh_close(
    _env: JNIEnv,
    _: JClass,
    id: jlong,
) {
    if let Ok(mut sockets) = sockets().lock() {
        if let Some(socket) = sockets.remove(&id) {
            socket.close();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn timeout_stage_survives_jni_and_unknown_errors_hide_platform_details() {
        let timeout = io::Error::new(io::ErrorKind::TimedOut, crate::SafeCode("read_timeout"));
        assert_eq!(
            java_exception("java/io/IOException", &timeout),
            (
                "java/net/SocketTimeoutException",
                "DoH: read_timeout".to_owned()
            )
        );
        let unknown = io::Error::other("https://private.node/path?token=secret Cookie: secret");
        assert_eq!(
            java_exception("java/io/IOException", &unknown),
            ("java/io/IOException", "DoH: connection_failed".to_owned())
        );
        assert_eq!(
            java_exception(
                "javax/net/ssl/SSLHandshakeException",
                &error("ech_rejected")
            ),
            (
                "javax/net/ssl/SSLHandshakeException",
                "DoH: ech_rejected".to_owned()
            )
        );
    }
}
