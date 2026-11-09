//! Public Cloudflare address ranges, never an authorization to skip TLS checks.
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

// https://www.cloudflare.com/ips/ — every address is still probed for the target zone.
const V4: &[(&str, u8)] = &[
    ("173.245.48.0", 20),
    ("103.21.244.0", 22),
    ("103.22.200.0", 22),
    ("103.31.4.0", 22),
    ("141.101.64.0", 18),
    ("108.162.192.0", 18),
    ("190.93.240.0", 20),
    ("188.114.96.0", 20),
    ("197.234.240.0", 22),
    ("198.41.128.0", 17),
    ("162.158.0.0", 15),
    ("104.16.0.0", 13),
    ("104.24.0.0", 14),
    ("172.64.0.0", 13),
    ("131.0.72.0", 22),
];
const V6: &[(&str, u8)] = &[
    ("2400:cb00::", 32),
    ("2606:4700::", 32),
    ("2803:f800::", 32),
    ("2405:b500::", 32),
    ("2405:8100::", 32),
    ("2a06:98c0::", 29),
    ("2c0f:f248::", 32),
];

pub fn allowed(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(ip) => V4.iter().any(|(base, prefix)| {
            let mask = u32::MAX << (32 - prefix);
            u32::from(ip) & mask == u32::from(base.parse::<Ipv4Addr>().unwrap()) & mask
        }),
        IpAddr::V6(ip) => V6.iter().any(|(base, prefix)| {
            let mask = u128::MAX << (128 - prefix);
            u128::from(ip) & mask == u128::from(base.parse::<Ipv6Addr>().unwrap()) & mask
        }),
    }
}

/// Hosts that share the winner: the forum and its static/upload domain (same Cloudflare ECH key).
const COVERED: &[&str] = &["linux.do", "ldstatic.com"];

pub fn covered(host: &str) -> bool {
    let host = host.trim_end_matches('.').to_ascii_lowercase();
    COVERED
        .iter()
        .any(|base| host == *base || host.ends_with(&format!(".{base}")))
}

/// Cloudflare serves proxied sites on IPv6 addresses that embed a shared-pool IPv4 address,
/// e.g. linux.do `2606:4700:10::6814:10ea` = 104.20.16.234. Random addresses elsewhere in the
/// announced /32s are mostly unrouted, so IPv6 samples stay on these /48s (third hextet).
const V6_POOLS: &[u16] = &[
    0x0000, 0x0010, 0x0020, 0x3030, 0x3031, 0x3032, 0x3033, 0x3034, 0x3035, 0x3036, 0x3037,
];

/// Twelve random IPv4 samples plus `v6` IPv6 samples from the serving pools.
pub fn samples(v6: usize) -> Vec<IpAddr> {
    let provider = rustls::crypto::aws_lc_rs::default_provider();
    let mut bytes = [0u8; 16];
    let mut result = Vec::new();
    for (base, prefix) in V4.iter().take(12) {
        if provider.secure_random.fill(&mut bytes).is_err() {
            break;
        }
        let mask = u32::MAX << (32 - prefix);
        let offset = u32::from_be_bytes(bytes[..4].try_into().unwrap()) & !mask;
        result.push(Ipv4Addr::from(u32::from(base.parse::<Ipv4Addr>().unwrap()) | offset).into());
    }
    let mut added = 0;
    for _ in 0..v6 * 4 {
        if added >= v6 || provider.secure_random.fill(&mut bytes).is_err() {
            break;
        }
        let pool = V6_POOLS[bytes[0] as usize % V6_POOLS.len()];
        // 104.18–104.27 or 172.64–172.71: the shared proxy pools, not Cloudflare's own assets.
        let high = if bytes[1] & 1 == 0 {
            0x6812 + (bytes[2] % 10) as u16
        } else {
            0xac40 + (bytes[2] % 8) as u16
        };
        let low = u16::from_be_bytes([bytes[3], bytes[4]]).max(1);
        let ip = IpAddr::V6(Ipv6Addr::new(0x2606, 0x4700, pool, 0, 0, 0, high, low));
        if !result.contains(&ip) {
            result.push(ip);
            added += 1;
        }
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn ranges_exclude_poison_private_and_boundary_neighbors() {
        for ip in [
            "104.16.0.0",
            "104.23.255.255",
            "104.24.0.1",
            "2606:4700::1111",
        ] {
            assert!(allowed(ip.parse().unwrap()), "{ip}");
        }
        for ip in [
            "127.0.0.1",
            "192.168.1.1",
            "8.8.8.8",
            "104.15.255.255",
            "104.28.0.0",
            "::1",
            "::ffff:104.16.0.1",
            "2606:4701::",
        ] {
            assert!(!allowed(ip.parse().unwrap()), "{ip}");
        }
        assert!(samples(4).iter().copied().all(allowed));
    }
    #[test]
    fn ipv6_samples_stay_on_serving_pools_with_embedded_shared_ipv4() {
        let ips = samples(8);
        let v6: Vec<_> = ips
            .iter()
            .filter_map(|ip| match ip {
                IpAddr::V6(v6) => Some(*v6),
                IpAddr::V4(_) => None,
            })
            .collect();
        assert_eq!(v6.len(), 8);
        assert_eq!(ips.len(), 20);
        for ip in v6 {
            let s = ip.segments();
            assert!(allowed(IpAddr::V6(ip)), "{ip}");
            assert_eq!(s[..2], [0x2606, 0x4700], "{ip}");
            assert!(V6_POOLS.contains(&s[2]), "{ip}");
            assert_eq!(s[3..6], [0, 0, 0], "{ip}");
            assert!((0x6812..=0x681b).contains(&s[6]) || (0xac40..=0xac47).contains(&s[6]), "{ip}");
            assert_ne!(s[7], 0, "{ip}");
        }
        assert!(samples(0).iter().all(|ip| ip.is_ipv4()));
    }
    #[test]
    fn only_target_and_subdomains_share_the_winner() {
        assert!(covered("LINUX.DO."));
        assert!(covered("connect.linux.do"));
        assert!(covered("ldstatic.com"));
        assert!(covered("CDN.ldstatic.com."));
        assert!(covered("linuxdo-uploads.s3.ldstatic.com"));
        assert!(!covered("linux.do.evil.test"));
        assert!(!covered("ldstatic.com.evil.test"));
        assert!(!covered("evilldstatic.com"));
        assert!(!covered("api.github.com"));
    }
}
