//! On-demand selection. One flight per profile; no timer or background reporting.
use crate::{
    dns::{Answer, Resolver},
    edge_pool, edge_probe,
};
use base64::{engine::general_purpose::STANDARD, Engine};
use serde::{Deserialize, Serialize};
use std::{
    collections::VecDeque,
    net::IpAddr,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use tokio::{
    sync::{Notify, Semaphore},
    task::JoinSet,
};

const KEY_AGE: u64 = 48 * 3600;
const COOLDOWN: Duration = Duration::from_secs(60);
const MAX_CANDIDATES: usize = 32;
const VENDORS: &[&str] = &[
    "www.visa.com.hk",
    "www.shopify.com",
    "openai.com",
    "icook.tw",
];
const PUBLIC_DNS: &[&str] = &["223.5.5.5", "1.12.12.12", "1.1.1.1", "8.8.8.8"];

#[derive(Clone, Serialize, Deserialize)]
pub struct Winner {
    pub ip: IpAddr,
    pub ech: String,
    pub latency_ms: u64,
    pub checked_at: u64,
}
impl Winner {
    fn key(&self) -> Option<Vec<u8>> {
        let age = now().checked_sub(self.checked_at)?;
        if age > KEY_AGE || !edge_pool::allowed(self.ip) || self.ech.len() > 5500 {
            return None;
        }
        let key = STANDARD.decode(&self.ech).ok()?;
        crate::dns::tls_config(Some(key.clone())).ok()?;
        Some(key)
    }
    fn answer(&self) -> Option<Answer> {
        Some(Answer {
            ips: vec![self.ip],
            ech: self.key()?,
            expires: Instant::now() + Duration::from_secs(60),
        })
    }
}

#[derive(Default)]
struct State {
    winner: Option<Winner>,
    verified: bool,
    phase: &'static str,
    races: VecDeque<Instant>,
    failures: VecDeque<Instant>,
}
impl State {
    fn permit(&mut self, at: Instant) -> bool {
        self.races
            .retain(|time| at.duration_since(*time) < Duration::from_secs(3600));
        if self.races.len() >= 4
            || self
                .races
                .back()
                .is_some_and(|time| at.duration_since(*time) < COOLDOWN)
        {
            return false;
        }
        self.races.push_back(at);
        true
    }
    fn failure(&mut self, ip: IpAddr, at: Instant) {
        if !self.verified || self.winner.as_ref().is_none_or(|winner| winner.ip != ip) {
            return;
        }
        self.failures
            .retain(|time| at.duration_since(*time) < Duration::from_secs(60));
        self.failures.push_back(at);
        if self.failures.len() >= 3 {
            self.winner = None;
            self.verified = false;
            self.phase = "fallback";
            self.failures.clear();
        }
    }
}

pub struct Selector {
    atlas: bool,
    state: Mutex<State>,
    flight: tokio::sync::Mutex<()>,
    closed: AtomicBool,
    stop: Notify,
}
impl Selector {
    pub fn new(atlas: bool, cached: Option<Winner>) -> Self {
        Self {
            atlas,
            state: Mutex::new(State {
                winner: cached.filter(|winner| winner.key().is_some()),
                phase: "idle",
                ..State::default()
            }),
            flight: tokio::sync::Mutex::new(()),
            closed: AtomicBool::new(false),
            stop: Notify::new(),
        }
    }
    pub fn close(&self) {
        self.closed.store(true, Ordering::SeqCst);
        self.stop.notify_waiters();
    }
    pub fn status(&self) -> serde_json::Value {
        let state = self.state.lock().unwrap();
        serde_json::json!({"phase": state.phase, "winner": state.winner})
    }
    pub fn failed(&self, ip: IpAddr) {
        self.state.lock().unwrap().failure(ip, Instant::now());
    }
    pub fn connected(&self, ip: IpAddr) {
        let mut state = self.state.lock().unwrap();
        if state.winner.as_ref().is_some_and(|winner| winner.ip == ip) {
            state.failures.clear();
        }
    }
    pub fn adopt(&self, key: &[u8]) {
        if let Some(winner) = self.state.lock().unwrap().winner.as_mut() {
            winner.ech = STANDARD.encode(key);
        }
    }
    fn ready(&self) -> Option<Answer> {
        let state = self.state.lock().unwrap();
        if state.verified {
            state.winner.as_ref()?.answer()
        } else {
            None
        }
    }
    pub async fn answer(&self) -> Option<Answer> {
        if self.closed.load(Ordering::SeqCst) {
            return None;
        }
        if let Some(answer) = self.ready() {
            return Some(answer);
        }
        let notified = self.stop.notified();
        tokio::pin!(notified);
        notified.as_mut().enable();
        if self.closed.load(Ordering::SeqCst) {
            return None;
        }
        tokio::select! {
            _ = notified => None,
            result = self.select() => result,
        }
    }
    async fn select(&self) -> Option<Answer> {
        let _flight = self.flight.lock().await;
        if let Some(answer) = self.ready() {
            return Some(answer);
        }
        if self.closed.load(Ordering::SeqCst) {
            return None;
        }
        let saved = {
            let mut state = self.state.lock().unwrap();
            if !state.permit(Instant::now()) {
                return None;
            }
            state.phase = "verifying";
            state.winner.clone()
        };
        if let Some(saved) = saved {
            if let Some(key) = saved.key() {
                if let Ok((latency, key)) = edge_probe::probe(saved.ip, &key).await {
                    return self.install(saved.ip, latency, key);
                }
            }
        }
        {
            let mut state = self.state.lock().unwrap();
            state.winner = None;
            state.verified = false;
            state.phase = "racing";
        }
        let (mut ips, mut keys) = gather().await;
        if self.atlas && !ips.is_empty() && !keys.is_empty() {
            // Atlas is optional and authenticated as its own hostname. No cookies or redirects.
            if let Ok(reply) = edge_probe::get(
                ips[0],
                "atlas.1molchuan.top",
                "/v1/routes",
                &keys[0],
                256 * 1024,
            )
            .await
            {
                if reply.status == 200 {
                    let (extra, key) = atlas_candidates(&reply.body);
                    for ip in extra {
                        if !ips.contains(&ip) {
                            ips.insert(0, ip);
                        }
                    }
                    if let Some(key) = key {
                        if !keys.contains(&key) {
                            keys.insert(0, key);
                        }
                    }
                }
            }
        }
        for ip in edge_pool::samples() {
            if !ips.contains(&ip) {
                ips.push(ip);
            }
        }
        ips.truncate(MAX_CANDIDATES);
        keys.truncate(3);
        if let Some((ip, latency, key)) = race(ips, keys).await {
            return self.install(ip, latency, key);
        }
        self.state.lock().unwrap().phase = "fallback";
        None
    }
    fn install(&self, ip: IpAddr, latency: u64, key: Vec<u8>) -> Option<Answer> {
        if self.closed.load(Ordering::SeqCst) {
            return None;
        }
        let winner = Winner {
            ip,
            ech: STANDARD.encode(key),
            latency_ms: latency,
            checked_at: now(),
        };
        let answer = winner.answer();
        let mut state = self.state.lock().unwrap();
        state.winner = Some(winner);
        state.verified = true;
        state.phase = "ready";
        state.failures.clear();
        answer
    }
}
fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

async fn gather() -> (Vec<IpAddr>, Vec<Vec<u8>>) {
    let mut tasks = JoinSet::new();
    // Each DNS lookup does A/AAAA/HTTPS in parallel: two lookups = six HTTP requests.
    let semaphore = Arc::new(Semaphore::new(2));
    for host in VENDORS {
        for endpoint in PUBLIC_DNS {
            let semaphore = semaphore.clone();
            tasks.spawn(async move {
                let _permit = semaphore.acquire().await.ok()?;
                let config = serde_json::json!({"url":format!("https://{endpoint}/dns-query"),"bootstrap":[endpoint]});
                let resolver = Resolver::new(&config.to_string()).ok()?;
                tokio::time::timeout(Duration::from_millis(1600), resolver.resolve_dns(host)).await.ok()?.ok()
            });
        }
    }
    let deadline = tokio::time::Instant::now() + Duration::from_millis(4500);
    let mut ips = Vec::new();
    let mut keys = Vec::new();
    while let Ok(Some(result)) = tokio::time::timeout_at(deadline, tasks.join_next()).await {
        if let Ok(Some(answer)) = result {
            for ip in answer
                .ips
                .into_iter()
                .filter(|ip| edge_pool::allowed(*ip))
                .take(4)
            {
                if !ips.contains(&ip) {
                    ips.push(ip);
                }
            }
            if !answer.ech.is_empty()
                && answer.ech.len() <= 4096
                && !keys.contains(&answer.ech)
                && crate::dns::tls_config(Some(answer.ech.clone())).is_ok()
            {
                keys.push(answer.ech);
            }
            if ips.len() >= 10 && !keys.is_empty() {
                break;
            }
        }
    }
    tasks.abort_all();
    ips.truncate(16);
    keys.truncate(3);
    (ips, keys)
}

async fn race(ips: Vec<IpAddr>, keys: Vec<Vec<u8>>) -> Option<(IpAddr, u64, Vec<u8>)> {
    if keys.is_empty() {
        return None;
    }
    let mut tasks = JoinSet::new();
    let semaphore = Arc::new(Semaphore::new(8));
    let keys = Arc::new(keys);
    for ip in ips {
        let semaphore = semaphore.clone();
        let keys = keys.clone();
        tasks.spawn(async move {
            let _permit = semaphore.acquire().await.ok()?;
            for key in keys.iter() {
                if let Ok((latency, key)) = edge_probe::probe(ip, key).await {
                    return Some((ip, latency, key));
                }
            }
            None
        });
    }
    let mut deadline = tokio::time::Instant::now() + Duration::from_secs(4);
    let mut best: Option<(IpAddr, u64, Vec<u8>)> = None;
    let mut passes = 0;
    while let Ok(Some(result)) = tokio::time::timeout_at(deadline, tasks.join_next()).await {
        if let Ok(Some(candidate)) = result {
            if best.is_none() {
                deadline = deadline.min(tokio::time::Instant::now() + Duration::from_millis(400));
            }
            if best
                .as_ref()
                .is_none_or(|(_, latency, _)| candidate.1 < *latency)
            {
                best = Some(candidate);
            }
            passes += 1;
            if passes >= 4 {
                break;
            }
        }
    }
    tasks.abort_all();
    best
}

fn atlas_candidates(body: &[u8]) -> (Vec<IpAddr>, Option<Vec<u8>>) {
    let mut ips = Vec::new();
    let mut key = None;
    if let Ok(json) = serde_json::from_slice::<serde_json::Value>(body) {
        for route in json
            .get("routes")
            .and_then(|v| v.as_array())
            .into_iter()
            .flatten()
            .take(64)
        {
            for family in ["v4", "v6"] {
                for ip in route
                    .get(family)
                    .and_then(|v| v.as_array())
                    .into_iter()
                    .flatten()
                    .take(32)
                {
                    if let Some(ip) = ip
                        .as_str()
                        .and_then(|s| s.parse().ok())
                        .filter(|ip| edge_pool::allowed(*ip))
                    {
                        if ips.len() < 10 && !ips.contains(&ip) {
                            ips.push(ip);
                        }
                    }
                }
            }
            if route.get("name").and_then(|v| v.as_str()) == Some("linux.do") {
                key = route
                    .get("ech")
                    .and_then(|v| v.as_str())
                    .filter(|s| s.len() <= 5500)
                    .and_then(|s| STANDARD.decode(s).ok())
                    .filter(|bytes| crate::dns::tls_config(Some(bytes.clone())).is_ok());
            }
        }
    }
    (ips, key)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn race_budget_recovers_after_cooldown_and_hour_limit() {
        let start = Instant::now();
        let mut state = State::default();
        for index in 0..4 {
            assert!(state.permit(start + Duration::from_secs(index * 60)));
            assert!(!state.permit(start + Duration::from_secs(index * 60 + 59)));
        }
        assert!(!state.permit(start + Duration::from_secs(240)));
        assert!(state.permit(start + Duration::from_secs(3600)));
    }
    #[test]
    fn only_repeated_current_winner_failures_in_window_release_pin() {
        let ip = "104.16.0.1".parse().unwrap();
        let mut state = State {
            winner: Some(Winner {
                ip,
                ech: String::new(),
                latency_ms: 1,
                checked_at: now(),
            }),
            verified: true,
            ..State::default()
        };
        let at = Instant::now();
        state.failure("104.16.0.2".parse().unwrap(), at);
        assert!(state.failures.is_empty());
        state.failure(ip, at);
        state.failure(ip, at);
        state.failure(ip, at + Duration::from_secs(61));
        assert!(state.verified);
        state.failure(ip, at + Duration::from_secs(62));
        state.failure(ip, at + Duration::from_secs(63));
        assert!(!state.verified);
        assert!(state.winner.is_none());
    }
    #[test]
    fn invalid_persisted_state_is_not_a_route_and_atlas_cannot_inject_addresses() {
        for (ip, checked_at) in [
            ("127.0.0.1", now()),
            ("104.16.0.1", 0),
            ("104.16.0.1", now() + 3600),
        ] {
            let selector = Selector::new(
                false,
                Some(Winner {
                    ip: ip.parse().unwrap(),
                    ech: "invalid".into(),
                    latency_ms: 1,
                    checked_at,
                }),
            );
            assert!(selector.status()["winner"].is_null());
            assert!(selector.ready().is_none());
        }
        let (ips, key) = atlas_candidates(br#"{"routes":[{"name":"linux.do","v4":["127.0.0.1","104.16.0.1","104.16.0.1"],"v6":["::1"],"ech":"invalid"}]}"#);
        assert_eq!(ips, vec!["104.16.0.1".parse::<IpAddr>().unwrap()]);
        assert!(key.is_none());
    }
    #[tokio::test]
    async fn closed_selector_never_starts_a_race() {
        let selector = Selector::new(false, None);
        selector.close();
        assert!(selector.answer().await.is_none());
        assert!(selector.state.lock().unwrap().races.is_empty());
    }
    #[tokio::test]
    async fn closing_wakes_a_lookup_queued_behind_the_shared_flight() {
        let selector = Arc::new(Selector::new(false, None));
        let guard = selector.flight.lock().await;
        let copy = selector.clone();
        let task = tokio::spawn(async move { copy.answer().await });
        tokio::task::yield_now().await;
        selector.close();
        assert!(tokio::time::timeout(Duration::from_millis(200), task)
            .await
            .unwrap()
            .unwrap()
            .is_none());
        assert!(selector.state.lock().unwrap().races.is_empty());
        drop(guard);
    }
    #[test]
    fn a_valid_saved_winner_must_be_reverified_and_success_clears_failure_streak() {
        let ip = "104.16.0.1".parse().unwrap();
        let saved = Winner { ip, ech: "AEX+DQBBvgAgACAnc+9IO/WWQSMj7W2mLvaFlY3UbvRlOsLeK+qbQ6pgVwAEAAEAAQASY2xvdWRmbGFyZS1lY2guY29tAAA=".into(), latency_ms: 80, checked_at: now() };
        let selector = Selector::new(false, Some(saved));
        assert!(!selector.status()["winner"].is_null());
        assert!(selector.ready().is_none());
        selector.state.lock().unwrap().verified = true;
        assert_eq!(selector.ready().unwrap().ips, [ip]);
        selector.failed(ip);
        selector.failed(ip);
        selector.connected(ip);
        selector.failed(ip);
        assert!(selector.ready().is_some());
    }
}
