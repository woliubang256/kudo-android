# Agent Note: 本地 Cloudflare 节点优选与 ECH 密钥恢复

Status: implemented

## Problem

原生访问目前依赖所选 DoH 返回 linux.do 的地址与 ECH 配置，第三方解析服务不可用时缺少独立候选来源；ECH 被拒后只清缓存，不能及时使用已认证的服务端新配置。用户批准参考 cfluxdo 1.1 的机制，保留正式版并用独立开发版验收。

## Decision

[慢网络保留赢家](2026-11-09-edge-selection-slow-network-and-coverage.md)细化失败处理：只超时的缓存赢家与候选获得一次放宽复核，竞速失败不再删除只是超时的持久化赢家；其余边界不变。

在独立编写的 Rust DNS/TLS 桥上新增可选本地优选。原有开关、节点和 VPN 策略不自动迁移；模式关闭不发优选请求。公共 DoH 通过固定 IP 的 HTTPS 端点查询非目标域名；候选只接受 Cloudflare 官方网段，配合有限随机样本和可选 atlas 公共表。所有候选必须通过原域名证书、ECH Accepted、HTTP 200 与完整 `h=linux.do` 行验证，才可作为主站及其子域的唯一目标。只固定目标节点和地址族，不宣称能控制运营商出口 IP。

优选最多 32 候选、8 个并发探测，收集及竞速均有总超时；成功后缓存赢家及公开 ECH 配置，冷启动先重新验证。网络变化取消旧代任务；连续 TCP/TLS 建连失败解除赢家并在下次需求时重选，取消和 HTTP 403/429 不计为节点失败。自动重选有冷却，手动操作防连点，没有定时后台测速。失败继续现有 DoH/ECH，不静默降为明文 SNI 或系统 DNS。检测流量没有 Cookie、令牌和上报；atlas 默认关闭，开启只拉公开表。

地址来源与 ECH 来源各保留一个查询槽，总计最多六个并行 DNS 请求。ECH 查询使用 `cloudflare-ech.com` 与 `crypto.cloudflare.com` 的公开 HTTPS 记录；每组先遍历不同域名再更换端点，避免一个域名占满整轮预算。2026-10-23 真机和公共 DNS 查询确认 visa、icook、japan 等地址来源不一定发布 ECH，不能把“属于 CF”当作“有密钥”。先用挂起地址查询的离线用例复现密钥查询饥饿，再修正调度；不内置过期密钥，也不因失败偷偷开启 atlas。

原生状态的 `cache_revalidated` 仅用于确认当前实例确实完成缓存探测，`cache_error` 仅含受控错误码，均不落入持久化赢家，也不展示给用户。在线恢复用例同时要求该标记、原 IP 与密钥更新，防止重新竞速恰好选回相同 IP 被误判为 retry_configs 恢复。真实网络超时可以触发正常重选，在线用例必须记录此类失败，不能当作稳定性保证。

ECH 拒绝只在 TLS 完成服务端身份验证后采纳 retry_configs，限定一次同地址重新建连握手；不重放 HTTP 请求，不接受证书错误中的配置，不能通过忽略证书恢复。状态、缓存和配置分别存储，避免保存测速结果触发无限重建。配置切换和 VPN 暂停必须关闭旧连接并阻止旧结果覆盖新状态。

候选/探测核心位于 `edge_pool.rs`、`edge_probe.rs`、`edge_selection.rs`，由原 Resolver 管理生命周期；JNI 提供安全状态快照与建连失败上报。rustls 锁定升级至 0.23.46，随包许可同步生成。包名隔离由既有构建笔记管理。

## Related decisions

检索活跃 notes 中 DoH、ECH、Cloudflare、签名、优选：与[DoH 接入](../../implemented/feature/2026-10-12-doh-network.md)部分重叠，保留凭据隔离、HTTP/2、取消与 WebView 系统网络边界；与[地址恢复](../../implemented/bug-fix/2026-10-14-doh-image-address-recovery.md)部分重叠，延续不重放写请求，增加跨子域赢家；与[过盾流程](../../implemented/bug-fix/2026-10-11-cloudflare-verification-flow.md)部分重叠，原生请求成功才视为恢复；与[构建身份](../../implemented/process/2026-10-08-signing-and-tag-releases.md)分工互补。不存在完全吸收或需要归档的旧提案。

参考 [帖子](https://linux.do/t/topic/3027109)、fluxdo `3e5f61b3`、其固定子模块 `03e07573` 与后续 edge_pin 设计。只借鉴机制，保持现有 Apache-2.0 独立实现，不复制 GPL Flutter 或许可不完整的 Rust 模块。

## Alternatives considered

- 继续完全依赖社区 DoH：实现成熟且维护量小，但无法满足第三方解析服务失效时仍可发现节点的目标，因此保留为明确兜底。
- 直接接入参考仓库的完整本地代理：可以同时接管 WebView，但涉及本地 CA、网页 TLS 信任和许可证，超出此次已批准的原生阶段；同通道网页另行评估。
- 失败后自动切系统 DNS：可在普通网络快速恢复，却改变用户选择的 ECH 和隐私边界，因此只允许用户关闭功能显式选择系统通道。

## Testing

31 项 Rust 离线测试通过，覆盖非 CF/私网地址、trace 假阳性、认证的 ECH retry_configs 与证书错误区分、赢家缓存校验、冷却、取消等待、连续失败、查询饥饿与域名边界，以及既有 TLS 流式读写。`cargo test --locked --manifest-path native/doh/Cargo.toml --target-dir app/build/edge-host`；许可生成仍为 118 组件，原生 arm64 构建通过 16KB 对齐检查。

Android 设置继续使用兼容的 version 1 JSON，新增 `localSelection` / `atlas` 默认 false。赢家单独存入私有 SharedPreferences，不发配置 Flow 更新；状态只展示 IP、延迟、时间与受控原因。`DohNetwork` 在网络变化或用户重新优选时关闭旧代并拒收迟到回调，旧空闲连接池随赢家变化释放。手动优选只做无凭据探测，10 秒防连点，VPN 暂停期间不能开始。网络管理使用既有黄色开关与按钮；关于页同步隐私说明。

28 项 Android 定向单测通过，覆盖旧配置、非法配置不隐式启用、状态脱敏、原地址恢复、VPN 策略、写入不重放与更新 ViewModel；Debug、独立测试 APK 与 Lint 通过。RMX2202 / Android 13 真机离线批次 20 项通过、3 项显式在线用例跳过（20.349 秒），覆盖设置、新卡片、缓存独立保存、流式传输、取消、HTTP/2、图片和写请求不重放，日志为 `artifacts/edge-selection/device-offline.txt`。

2026-10-23 真机直连在线用例最终通过（6.973 秒，`device-live-cache-diagnostic.txt`）：主站与 Connect 同节点/同地址族、双方 ECH Accepted、话题列表 HTTP 200 / 30 条；人为修改 ECH config_id 后，`cache_revalidated=true`、IP 不变、密钥更新，确认认证恢复路径。早期查询饥饿与无密钥的失败记录保留；修复后仍观察到缓存探测失败转重选，以及一次列表 403。这是线路与站点策略的真实限制，最终通过不代表每次请求都成功，也不证明登录过盾效果。报告保存在 `device-live-result-final.json`，无 Cookie 或密钥内容。

生产设置入口手动优选得到 `104.18.20.69 · 860 ms`，`settings-ready.png` 已目视确认中文、黄色开关与节点时间可读；`settings-paused.png` 验证系统代理存在时自动暂停和按钮禁用。为测试临时关闭的开发版自动切换已恢复，DoH / 本地优选恢复原关闭状态，atlas 始终关闭；系统网络设置未变。开发版 `com.kudo.android.debug` 为 `0.3.1-dev / 3002`，使用原开发证书覆盖安装；正式版仍为 `0.3.1 / 3002`，更新时间保持 `2026-10-21 01:33:40`，未卸载或清数据。

## Consequences

公共 DNS 和候选节点会看到来源 IP；其他域名的 ECH 配置不保证永久适用于目标站，实际握手必须接受。首次收集候选增加等待和流量，全部失败仍可能无法联网。WebView 仍走系统网络，不能承诺独立登录或免过盾。缓存损坏/网络切换不应使用未经重新验证的赢家。
