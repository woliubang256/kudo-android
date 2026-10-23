mod dns;
mod edge_pool;
mod edge_probe;
mod edge_selection;
mod jni_api;
mod socket;

#[derive(Debug)]
struct SafeCode(&'static str);
impl std::fmt::Display for SafeCode {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.0)
    }
}
impl std::error::Error for SafeCode {}
fn code_error(code: &'static str) -> std::io::Error {
    std::io::Error::other(SafeCode(code))
}

fn runtime() -> &'static tokio::runtime::Runtime {
    static RUNTIME: std::sync::OnceLock<tokio::runtime::Runtime> = std::sync::OnceLock::new();
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .expect("DoH runtime")
    })
}
