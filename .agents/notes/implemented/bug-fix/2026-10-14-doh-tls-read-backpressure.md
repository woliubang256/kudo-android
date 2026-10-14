# Agent Note: 持续加载图片时的 TLS 读取背压

Status: implemented

## Problem

用户在已覆盖上一轮修复的开发版持续刷帖时仍遇到 `DoH read_failed`。这个码来自原生读取阶段的未知 IO 错误，不能据此断定社区 DoH、Wi-Fi 或图片格式有问题。现有大响应测试只使用均匀的大 TLS 记录，没有覆盖长连接上大小记录混合、分片和合并的情况。

原读取循环把一次 TCP 读取的密文全部送入 rustls，完成后才向调用者返回明文。rustls 0.23.45 的接收明文上限为 16 KiB；触发背压时 `read_tls` 返回 `ErrorKind::Other`，映射为 `read_failed`。一个未完成的大记录与后续小记录合并时，明文缓冲可能已超过上限，本批密文却仍有未消费字节。有效 TLS 记录的回环夹具确认了这个错误路径。

## Decision

`Socket::read_inner` 优先交付 rustls 已有明文，再继续喂入密文。读锁拥有每连接固定 16 KiB 的密文缓冲及消费位置；一次 `read_tls/process_new_packets` 后先检查并交付明文，未消费密文保留到后续读取，只有当前缓冲用尽后才读取 TCP。既不丢字节，也不扩大 rustls 明文上限。

TCP 读取继续释放 TLS 状态锁，TLS 响应记录的发送仍沿现有写锁顺序；读写锁职责不合并。TCP EOF 仍通知 rustls，未经 `close_notify` 的截断报 `read_eof`，无效 TLS 记录仍报 `tls_record_invalid`。未知读取错误的重试策略、地址选择、HTTP 请求数、证书校验和 ECH 均沿用现状。测试证书仅供原生测试，不加入生产信任库。

## Existing note audit

与[地址恢复和分阶段诊断](../../implemented/bug-fix/2026-10-14-doh-image-address-recovery.md)部分重叠：保留地址选择、错误映射及未知故障不自动重试的决定，只检查 TLS 内部读取。与[图片退避](../../implemented/bug-fix/2026-10-14-request-rate-cooldown.md)部分重叠：继续遵守正文交付后不自动重新下载、并发许可证和冷却。与[DoH 接入](../../implemented/feature/2026-10-12-doh-network.md)部分重叠：保持 HTTP/2 双向 IO、取消、证书校验、ECH 与不重放写入。与[真机探测](../../implemented/testing/2026-10-12-doh-ech-device-probe.md)边界相关：回环测试不能替代用户长时间使用的线上结论。没有完全吸收或过时提案。

## Alternatives considered

- 自动重试所有 `read_failed`：能够掩盖部分短时故障，但混淆 TLS 内部背压与网络故障，正文读取失败还会增加流量和限流风险；先验证读取逻辑，继续保留现有重试边界。
- 扩大或取消 rustls 缓冲上限：实现简单，可能降低触发频率，但没有解决先交付明文的时序，还扩大每条连接的内存占用；使用有界密文和背压处理。
- 关闭 HTTP/2 或连接复用：减少多流合并场景，但已有生产验证依赖 HTTP/2，而且 TLS 分片本身仍存在；修复传输层并验证复用场景。

## Testing

`mixed_tls_records_after_a_fragment_do_not_fail_with_read_failed` 在测试证书的 TLS 会话中预置大记录的前缀，再用真实回环 TCP 交付其末尾、多个小记录及新大记录。修复前稳定返回 `SafeCode("read_failed")`，记录为 `artifacts/doh-tls-backpressure-repro.txt`；修复后 TLS 1.2/1.3 均逐字节一致，单个连接内没有重连。

`repeated_tls_responses_keep_every_byte_with_varying_read_sizes` 在同一 TLS 会话连续读取 32 组大小记录，共 805664 字节，调用者缓冲分别为 1、257、4096、32768 字节。分片测试覆盖 TLS 1.2/1.3 的 8 个头部/正文边界及 127 字节读取；另覆盖截断、损坏、正常结束和 TLS 读取等待时独立写入。完整原生库 19 项测试通过，既有超时、关闭、大响应及 JNI 安全码测试同批通过。

`ImageNetworkDeviceTest.repeatedParallelImagesReuseOneNativeHttp2ConnectionWithoutLosingDownloadPermits` 在 RMX2202 / Android 13 真机的真实 JNI Socket 与 Coil 路径关闭图片缓存，先加载一张 128×128、超过 32 KiB 的 PNG，再分四轮并发各加载八张。33 次请求全部解码成功，服务端请求数恰为 33，所有波次复用一个原生 HTTP/2 连接。此夹具使用 HTTP/2 明文回环，只验证 JNI、并发许可、图片完整下载及连接复用；TLS 分片根因由前述原生 TLS 夹具覆盖。

ImageNetworkDeviceTest 与 DohTransportDeviceTest 的离线真机批次为 12 项通过、2 项线上 opt-in 跳过（`artifacts/doh-tls-backpressure-device.txt`），包括限流、失败地址切换、读写并发、取消、证书拒绝、gzip/chunked、连接复用及写入不重放。Debug、AndroidTest 和 arm64 原生 Release 库构建通过，16 KiB ELF 对齐通过（`artifacts/doh-tls-backpressure-build.txt`）；未修改 JVM 策略，26 项相关单测结果由 Gradle 复用。

显式启用 `imageDohLiveProbe=true` 后，生产 SingletonImageLoader 使用手机已有启用的 DoH 配置加载此前第三楼的动态 WebP，关闭内存/磁盘缓存，确认数据来源为 NETWORK、尺寸 300×370，独立线上用例通过（`artifacts/doh-tls-backpressure-live-fixed.txt`、`artifacts/doh-tls-backpressure-live-result.json`）。没有更改用户节点或 VPN 策略，没有向第三方发送社区 Cookie。线上用例默认跳过，只有显式 opt-in 才访问该已知公开图片。

探测首轮的 `networkCachePolicy(DISABLED)` 会禁止网络读取，产生客户端 504；移除这一错误的测试配置后才进行了实际下载。关闭图片缓存与禁用网络读取不是同一件事，后续探测继续以 NETWORK 来源断言保证真正走网络。

覆盖前读取已安装 2005 / 0.2.3，与新构建证书 SHA256 一致；构建显式传入同版本参数，使用 `adb install -r` 与 `install -r -t`，未卸载、未清数据。最终真机开发版仍为 2005 / 0.2.3，更新时间 2026-10-14 23:45:39。没有构建新正式版本或发布 Tag。

## Consequences

每个原生连接固定增加 16 KiB 的持久密文空间，替代原本每次读取的栈缓冲；空间不会随图片数量或流量增长。明文交付更早，剩余密文有序保留。循环可能增加空的 TLS 发送检查，但不会增加网络请求或重放响应正文。

预置密文的测试只控制分片边界，不复现真实站点的全部服务行为。已通过真机 JNI 连续并发及一次生产 DoH 原图加载，但这不能保证用户所有偶发图片失败都属于此原因；持续快刷的线上使用仍需观察是否出现其他错误码。
