# Agent Note: DoH 图片建连失败后的地址恢复与分阶段诊断

Status: implemented

## Problem

用户使用社区 DoH、Wi-Fi、未启用 VPN 时偶发图片失败，界面显示 `connection_failed`。JNI 把未包装的 IO 故障统一映射成这个码，无法判断发生在建连还是读写阶段，不能据此断定节点解析失败。

桌面回环对照使用现有 ImageHttpPolicy：DNS 返回首个不可达、第二个可达的 IP，关闭 OkHttp 自动恢复后，一次手动重试仍重新选择首个地址，两次建连均失败。启用自动恢复的对照只发出一个 HTTP 请求即可成功，但不能因此开启所有客户端的隐式重放。

## Decision

DoH 通道记忆纯 TCP 建连的临时故障，按主机/IP 短暂降序，保持 DNS 返回的候选全集、原始 URL、证书验证和 ECH。现有图片 GET/HEAD 只再试一次，新尝试优先使用未失败的地址；共享通道后续请求也可以避开已确认不可达的地址，不增加写请求重放。地址记忆仅属于当前 transport，10 秒过期、最多 256 条，网络或节点切换即销毁。

RustSocket 仅在 NativeDoh.connect 抛出明确临时 IO 异常时记录失败，取消或关闭不记录，握手/读写/配置错误不改变地址顺序。排序只应用于本次解析候选，不固定 IP、不延长 TTL。DohTransport 的可选 Dns 注入只供内部回环夹具使用，默认仍由 NativeDoh.lookup 解析；原生 TCP 与其失败回调和生产一致。

原生 Socket 用受控码区分 connect/read/write 及 refused、reset、unreachable、timeout、eof、broken_pipe，未知故障保留阶段为 failed。错误保留原始 ErrorKind，使 JNI 超时继续抛 SocketTimeoutException，但保留阶段码；已有配置、ECH、TLS 与关闭的 SafeCode 原样保留。不暴露原始系统错误、URL、Cookie 或节点参数。

图片策略只把明确的临时 IO 码及 DNS 查询超时纳入现有一次恢复；read_failed/write_failed 等未知读写故障不猜成临时错误。原生 DoH 码先匹配允许集合，再判断普通 Java 网络异常，防止 profile_closed 因 SocketException/UnknownHostException 类型被误重试。已交付响应正文后的读取失败仍只释放许可证并显示信息，不自动重新下载。ImageLoadError 显示阶段及中文原因，严格安全格式继续生效。

## Existing note audit

与[请求限流及图片退避](../../implemented/bug-fix/2026-10-14-request-rate-cooldown.md)部分重叠：保留并发、冷却、一次恢复及正文不重下的决定，细化原生 IO 码。与[DoH 接入](../../implemented/feature/2026-10-12-doh-network.md)部分重叠：补足地址优先级而不翻转禁用隐式重放、ECH 和证书契约。与[图片错误展示](../simplification/2026-10-10-feed-image-opens-topic.md)部分重叠：细化受控原因，保持原文屏蔽。与[真机探测](../../implemented/testing/2026-10-12-doh-ech-device-probe.md)边界相关：测试采用回环与无 Cookie 的夹具，不代替真实网络结论。正文尺寸、图片解码、剧透及图库笔记无关，没有完全吸收或过时提案。

## Alternatives considered

- 开启 OkHttp 自动恢复：成熟路由器能在同一请求中尝试后续地址并恢复坏连接，但会改变所有请求的重放语义，包括 HTTP follow-up；保留现有显式的一次图片恢复，只调整下一次选择。
- 每次失败更换 DoH 节点或退回系统 DNS：可能快速恢复，然而会替用户改变网络选择、ECH 和隐私边界；仅使用当前节点返回的候选。
- 图片无限重试或自动重下部分正文：可以隐藏短时线路抖动，但增加请求、流量和等待，可能放大限流；保留原有次数和正文交付边界。

## Testing

ImageNetworkDeviceTest 先在 RMX2202 / Android13 的真实 Rust TCP 与 Coil 路径复现两次均尝试 127.0.0.2、图片失败（artifacts/doh-image-address-repro.txt）。夹具只监听 127.0.0.1，无 Cookie，不调用外部节点。修复后同一用例依次尝试 127.0.0.2、127.0.0.1，解码成功且服务端仅收到一个 HTTP 请求；另两项既有原生 Coil 冷却及并行用例同批通过（artifacts/doh-image-address-fixed-device.txt）。

DohAddressOrderTest 的 5 项用例覆盖地址过期、主机/transport 隔离、候选保留与更新、容量及配置/TLS 错误不影响优先级。与 ImageHttpPolicyTest、DohRequestPolicyTest 的定向单测同批通过（artifacts/doh-address-fixed-build.txt），保留一次恢复、取消、写入不重放、HTTP 错误不重放及并发许可证契约。覆盖安装前核对原签名和 2005 / 0.2.3，无卸载或数据清除。

分阶段诊断先复现原生 TCP 错误没有 SafeCode（artifacts/doh-io-native-repro.txt）；Windows 的回环拒绝可能报告 TimedOut，夹具按 ErrorKind 验证对应阶段而不假设桌面平台。ImageLoadErrorTest 先复现阶段码只能显示通用原因（artifacts/doh-io-display-repro.txt）。修复后 14 项 Rust 测试通过（artifacts/doh-io-native-fixed.txt），包含实际 TCP 故障、13 种 IO 映射、SafeCode 保留、超时 JNI 类/阶段及未知原文屏蔽。

closedNativeProfilesDoNotRetryThroughSocketOrDnsExceptionTypes 先复现 profile_closed 被 Java 类型误判而尝试两次（artifacts/doh-closed-profile-repro.txt），修复优先匹配原生受控码。

最终 26 项定向 JVM 用例与 Debug / AndroidTest 构建、Lint 通过，Lint 为 0 errors / 45 warnings / 1 hint（artifacts/doh-update-final-build.txt）。真机批次 32 项中 31 项通过、1 项线上 opt-in 用例跳过（artifacts/doh-update-final-device.txt），包含真实原生连接失败的阶段显示、所有地址失败时 GET 最多两次建连 / POST 一次、取消、证书拒绝、HTTP/2、连接复用、并发及更新 UI；未启用真实站点探测或更改用户节点配置。

## Consequences

共享地址记忆可能短暂改变其他请求的候选顺序，但不丢弃候选、不延长 DNS TTL、不增加 HTTP 重放。10 秒内线路恢复的地址可能稍晚使用。回环测试证明已知策略缺口被修复，不能证明用户每一次偶发失败都是同一根因，线上仍依赖新的分阶段错误码定位。
