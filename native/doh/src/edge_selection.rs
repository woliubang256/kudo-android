//! On-demand selection. One flight per profile; no timer or background reporting.
use crate::{
    dns::{Answer, Resolver},
    edge_pool, edge_probe,
};
use base64::{engine::general_purpose::STANDARD, Engine};
use serde::{Deserialize, Serialize};
use std::{
    collections::VecDeque,
    future::Future,
    net::IpAddr,
    pin::Pin,
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
/// One probe covers TCP + ECH TLS + the trace request.
const PROBE_BUDGET: Duration = Duration::from_secs(3);
/// A slow but working path (cold radio, ~1 s RTT) needs about 3.3 s; only timeouts get this.
const RELAXED_BUDGET: Duration = Duration::from_secs(8);
const RACE_WINDOW: Duration = Duration::from_secs(4);
const RECHECK_LIMIT: usize = 4;
const TIMEOUT: &str = "probe_timeout";
const COOLDOWN: Duration = Duration::from_secs(60);
const MAX_CANDIDATES: usize = 32;
const VENDORS: &[&str] = &[
    "www.visa.com.hk",
    "www.shopify.com",
    "openai.com",
    "icook.tw",
];
const PUBLIC_DNS: &[&str] = &["223.5.5.5", "1.12.12.12", "1.1.1.1", "8.8.8.8"];
const KEY_SOURCES: &[&str] = &["cloudflare-ech.com", "crypto.cloudflare.com"];

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

type ProbeResult = crate::dns::Result<(u64, Vec<u8>)>;
type BoxFuture<T> = Pin<Box<dyn Future<Output = T> + Send>>;
type ProbeFn = Arc<dyn Fn(IpAddr, Vec<u8>, Duration) -> BoxFuture<ProbeResult> + Send + Sync>;
type GatherFn = Arc<dyn Fn() -> BoxFuture<(Vec<IpAddr>, Vec<Vec<u8>>)> + Send + Sync>;

/// Network side of a selection; replaced only by offline tests.
pub(crate) struct Network {
    probe: ProbeFn,
    gather: GatherFn,
}
impl Network {
    fn live() -> Self {
        Self {
            probe: Arc::new(|ip, key, budget| {
                Box::pin(async move { edge_probe::probe(ip, &key, budget).await })
            }),
            gather: Arc::new(|| Box::pin(gather())),
        }
    }
}

#[derive(Default)]
struct State {
    winner: Option<Winner>,
    /// A saved winner that only timed out. Not a route, but verified first next time.
    retained: Option<Winner>,
    /// The saved winner is known bad and must leave persistent storage.
    forget: bool,
    verified: bool,
    cache_revalidated: bool,
    cache_error: Option<&'static str>,
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
            self.retained = None;
            self.forget = true;
            self.verified = false;
            self.phase = "fallback";
            self.failures.clear();
        }
    }
}

pub struct Selector {
    atlas: bool,
    network: Network,
    state: Mutex<State>,
    flight: tokio::sync::Mutex<()>,
    closed: AtomicBool,
    stop: Notify,
}
impl Selector {
    pub fn new(atlas: bool, cached: Option<Winner>) -> Self {
        Self::with_network(atlas, cached, Network::live())
    }
    pub(crate) fn with_network(atlas: bool, cached: Option<Winner>, network: Network) -> Self {
        Self {
            atlas,
            network,
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
        serde_json::json!({"phase": state.phase, "winner": state.winner, "forget": state.forget, "cache_revalidated": state.cache_revalidated, "cache_error": state.cache_error})
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
            state.forget = false;
            state.winner.clone().or_else(|| state.retained.clone())
        };
        let mut saved_only_timed_out = false;
        if let Some(saved) = &saved {
            if let Some(key) = saved.key() {
                match self.revalidate(saved.ip, key).await {
                    Ok((latency, key)) => {
                        self.state.lock().unwrap().cache_revalidated = true;
                        return self.install(saved.ip, latency, key);
                    }
                    Err(reason) => {
                        saved_only_timed_out = reason == TIMEOUT;
                        self.state.lock().unwrap().cache_error = Some(reason);
                    }
                }
            }
        }
        {
            let mut state = self.state.lock().unwrap();
            state.winner = None;
            state.verified = false;
            state.phase = "racing";
        }
        let (mut ips, mut keys) = (self.network.gather)().await;
        if self.atlas && !ips.is_empty() && !keys.is_empty() {
            // Atlas is optional and authenticated as its own hostname. No cookies or redirects.
            if let Ok(reply) = edge_probe::get(
                ips[0],
                "atlas.1molchuan.top",
                "/v1/routes",
                &keys[0],
                256 * 1024,
                PROBE_BUDGET,
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
        let probe = self.network.probe.clone();
        let outcome = race(ips, keys.clone(), probe.clone(), PROBE_BUDGET, RACE_WINDOW).await;
        if let Some((ip, latency, key)) = outcome.best {
            return self.install(ip, latency, key);
        }
        // Only candidates that merely ran out of time are worth a slower second look.
        let mut slow = outcome.timed_out;
        slow.truncate(RECHECK_LIMIT);
        if !slow.is_empty() {
            let window = RELAXED_BUDGET + Duration::from_secs(1);
            if let Some((ip, latency, key)) =
                race(slow, keys, probe, RELAXED_BUDGET, window).await.best
            {
                return self.install(ip, latency, key);
            }
        }
        let mut state = self.state.lock().unwrap();
        state.phase = "fallback";
        // A failed race says nothing about a winner that was only slow; keep it for next time.
        state.forget = saved.is_some() && !saved_only_timed_out;
        state.retained = if saved_only_timed_out { saved } else { None };
        None
    }
    async fn revalidate(&self, ip: IpAddr, key: Vec<u8>) -> ProbeResult {
        match (self.network.probe)(ip, key.clone(), PROBE_BUDGET).await {
            Err(TIMEOUT) => (self.network.probe)(ip, key, RELAXED_BUDGET).await,
            other => other,
        }
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
        state.retained = None;
        state.forget = false;
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
    gather_with(|host, endpoint| async move {
        let config = serde_json::json!({"url":format!("https://{endpoint}/dns-query"),"bootstrap":[endpoint]});
        let resolver = Resolver::new(&config.to_string()).ok()?;
        tokio::time::timeout(Duration::from_millis(1600), resolver.resolve_dns(host)).await.ok()?.ok()
    }, Duration::from_millis(4500)).await
}

async fn gather_with<F, Fut>(lookup: F, budget: Duration) -> (Vec<IpAddr>, Vec<Vec<u8>>)
where
    F: Fn(&'static str, &'static str) -> Fut + Send + Sync + 'static,
    Fut: std::future::Future<Output = Option<Answer>> + Send,
{
    let mut tasks = JoinSet::new();
    let lookup = Arc::new(lookup);
    // Reserve one lookup per lane so unreachable address sources cannot starve keys.
    // Each lookup does A/AAAA/HTTPS in parallel: two lanes = six HTTP requests.
    for hosts in [VENDORS, KEY_SOURCES] {
        let semaphore = Arc::new(Semaphore::new(1));
        for endpoint in PUBLIC_DNS {
            for host in hosts {
                let semaphore = semaphore.clone();
                let lookup = lookup.clone();
                tasks.spawn(async move {
                    let _permit = semaphore.acquire().await.ok()?;
                    lookup(host, endpoint).await
                });
            }
        }
    }
    let deadline = tokio::time::Instant::now() + budget;
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

#[derive(Default)]
struct RaceOutcome {
    best: Option<(IpAddr, u64, Vec<u8>)>,
    /// Candidates whose probe ran out of time, in completion order.
    timed_out: Vec<IpAddr>,
}

async fn race(
    ips: Vec<IpAddr>,
    keys: Vec<Vec<u8>>,
    probe: ProbeFn,
    budget: Duration,
    window: Duration,
) -> RaceOutcome {
    let mut outcome = RaceOutcome::default();
    if keys.is_empty() {
        return outcome;
    }
    let mut tasks = JoinSet::new();
    let semaphore = Arc::new(Semaphore::new(8));
    let keys = Arc::new(keys);
    for ip in ips {
        let semaphore = semaphore.clone();
        let keys = keys.clone();
        let probe = probe.clone();
        tasks.spawn(async move {
            let Ok(_permit) = semaphore.acquire().await else {
                return (ip, Err("race_closed"));
            };
            let mut last = Err("probe_no_key");
            for key in keys.iter() {
                last = probe(ip, key.clone(), budget).await;
                // A timeout is the path, not the key: another key would only wait again.
                if matches!(last, Ok(_) | Err(TIMEOUT)) {
                    break;
                }
            }
            (ip, last)
        });
    }
    let mut deadline = tokio::time::Instant::now() + window;
    let mut passes = 0;
    while let Ok(Some(result)) = tokio::time::timeout_at(deadline, tasks.join_next()).await {
        match result {
            Ok((ip, Ok((latency, key)))) => {
                if outcome.best.is_none() {
                    deadline =
                        deadline.min(tokio::time::Instant::now() + Duration::from_millis(400));
                }
                if outcome
                    .best
                    .as_ref()
                    .is_none_or(|(_, best, _)| latency < *best)
                {
                    outcome.best = Some((ip, latency, key));
                }
                passes += 1;
                if passes >= 4 {
                    break;
                }
            }
            Ok((ip, Err(TIMEOUT))) => outcome.timed_out.push(ip),
            _ => {}
        }
    }
    tasks.abort_all();
    outcome
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
    #[tokio::test]
    async fn unreachable_address_sources_do_not_starve_the_ech_key_source() {
        let (_, keys) = gather_with(|host, _| async move {
            if !KEY_SOURCES.contains(&host) { std::future::pending::<()>().await; }
            Some(Answer {
                ips: vec!["104.16.0.1".parse().unwrap()],
                ech: STANDARD.decode("AEX+DQBBvgAgACAnc+9IO/WWQSMj7W2mLvaFlY3UbvRlOsLeK+qbQ6pgVwAEAAEAAQASY2xvdWRmbGFyZS1lY2guY29tAAA=").unwrap(),
                expires: Instant::now() + Duration::from_secs(60),
            })
        }, Duration::from_millis(80)).await;
        assert!(
            !keys.is_empty(),
            "Key discovery must get a slot before the shared deadline"
        );
    }
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
    const KEY: &str = "AEX+DQBBvgAgACAnc+9IO/WWQSMj7W2mLvaFlY3UbvRlOsLeK+qbQ6pgVwAEAAEAAQASY2xvdWRmbGFyZS1lY2guY29tAAA=";
    type Calls = Arc<Mutex<Vec<(IpAddr, Duration)>>>;
    /// Answers instantly: `rule` decides from the address and the budget the caller offered.
    fn fake(
        gathered: Vec<IpAddr>,
        rule: impl Fn(IpAddr, Duration) -> ProbeResult + Send + Sync + 'static,
    ) -> (Network, Calls, Arc<Mutex<usize>>) {
        let calls: Calls = Arc::default();
        let gathers = Arc::new(Mutex::new(0));
        let (log, count, rule) = (calls.clone(), gathers.clone(), Arc::new(rule));
        let network = Network {
            probe: Arc::new(move |ip, key, budget| {
                log.lock().unwrap().push((ip, budget));
                let result = rule(ip, budget).map(|(latency, _)| (latency, key));
                Box::pin(async move { result })
            }),
            gather: Arc::new(move || {
                *count.lock().unwrap() += 1;
                let ips = gathered.clone();
                Box::pin(async move { (ips, vec![STANDARD.decode(KEY).unwrap()]) })
            }),
        };
        (network, calls, gathers)
    }
    fn saved(ip: IpAddr) -> Winner {
        Winner { ip, ech: KEY.into(), latency_ms: 80, checked_at: now() }
    }
    fn ip(text: &str) -> IpAddr {
        text.parse().unwrap()
    }
    #[tokio::test]
    async fn a_slow_saved_winner_gets_one_relaxed_check_before_any_race() {
        let a = ip("104.16.0.1");
        let (network, calls, gathers) = fake(vec![], move |ip, budget| {
            if ip == a && budget == RELAXED_BUDGET { Ok((3300, vec![])) } else { Err(TIMEOUT) }
        });
        let selector = Selector::with_network(false, Some(saved(a)), network);
        assert_eq!(selector.answer().await.unwrap().ips, [a]);
        assert_eq!(*calls.lock().unwrap(), [(a, PROBE_BUDGET), (a, RELAXED_BUDGET)]);
        assert_eq!(*gathers.lock().unwrap(), 0);
        let status = selector.status();
        assert_eq!(status["phase"], "ready");
        assert_eq!(status["cache_revalidated"], true);
    }
    #[tokio::test]
    async fn definite_failures_are_not_given_a_relaxed_second_chance() {
        let a = ip("104.16.0.1");
        let (network, calls, _) = fake(vec![], |_, _| Err("probe_ech_rejected"));
        let selector = Selector::with_network(false, Some(saved(a)), network);
        assert!(selector.answer().await.is_none());
        assert!(!calls.lock().unwrap().iter().any(|call| call.1 == RELAXED_BUDGET));
        let status = selector.status();
        assert_eq!(status["phase"], "fallback");
        assert_eq!(status["forget"], true, "a winner that really failed must leave storage");
        assert!(selector.state.lock().unwrap().retained.is_none());
    }
    #[tokio::test]
    async fn a_race_that_only_timed_out_rechecks_probed_candidates_slowly() {
        let (b, c) = (ip("104.16.0.2"), ip("104.16.0.3"));
        let (network, calls, _) = fake(vec![b, c], move |ip, budget| {
            if ip == c && budget == RELAXED_BUDGET { Ok((3100, vec![])) } else { Err(TIMEOUT) }
        });
        let selector = Selector::with_network(false, None, network);
        assert_eq!(selector.answer().await.unwrap().ips, [c]);
        let relaxed = calls.lock().unwrap().iter().filter(|call| call.1 == RELAXED_BUDGET).count();
        assert!((1..=RECHECK_LIMIT).contains(&relaxed), "{relaxed} relaxed probes");
    }
    #[tokio::test]
    async fn a_failed_race_keeps_a_merely_slow_winner_for_storage_and_the_next_selection() {
        let a = ip("104.16.0.1");
        let fast = Arc::new(AtomicBool::new(false));
        let switch = fast.clone();
        let (network, calls, _) = fake(vec![ip("104.16.0.2")], move |ip, _| {
            if ip == a && switch.load(Ordering::SeqCst) { Ok((90, vec![])) } else { Err(TIMEOUT) }
        });
        let selector = Selector::with_network(false, Some(saved(a)), network);
        assert!(selector.answer().await.is_none());
        let status = selector.status();
        assert_eq!(status["phase"], "fallback");
        assert_eq!(status["forget"], false, "a slow network must not erase the stored winner");
        assert_eq!(selector.state.lock().unwrap().retained.as_ref().map(|w| w.ip), Some(a));
        // Next permitted selection (after the cooldown) verifies the kept winner first.
        selector.state.lock().unwrap().races.clear();
        calls.lock().unwrap().clear();
        fast.store(true, Ordering::SeqCst);
        assert_eq!(selector.answer().await.unwrap().ips, [a]);
        assert_eq!(calls.lock().unwrap()[0], (a, PROBE_BUDGET));
        assert!(selector.state.lock().unwrap().retained.is_none());
    }
    #[test]
    fn traffic_failures_that_release_the_pin_also_forget_the_stored_winner() {
        let a = ip("104.16.0.1");
        let mut state = State { winner: Some(saved(a)), verified: true, ..State::default() };
        let at = Instant::now();
        for _ in 0..3 {
            state.failure(a, at);
        }
        assert!(state.forget);
        assert!(state.retained.is_none());
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
