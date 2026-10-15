# Agent Note: DoH、ECH 与代理登录后的原生直连接入

Status: implemented

## Problem

用户希望在设置中启用默认关闭的 DoH，使用文章提供的服务器，支持自定义服务器增删改查，并在手机启用 VPN 时自动停用。目标流程是通过代理在 App WebView 登录，返回后关闭代理、开启 DoH，原生页面仍可访问。需要评估 Cloudflare 再次验证的风险。

当前 App 的原生请求使用 OkHttp 4.12.0，WebView 由 Android 系统提供。两者已经共享 CookieManager 和明确相同的 User-Agent，但 DNS、TLS、连接池和出口网络不是同一个概念。只替换 DNS 无法证明直连可用，也无法保证原来代理出口获得的过盾凭据在新出口继续有效。

本篇记录用户批准“按照 doh+ech 开发”后的正式设置与原生网络接入。[原生直连技术验证](../testing/2026-10-12-doh-ech-device-probe.md)提供先前的独立探针对照，生产客户端也在当前非代理 Wi-Fi 上验证了现有登录、列表和头像；WebView 继续使用系统网络。

## Decision

[网络请求调度](2026-10-15-network-request-scheduling.md)为API、Connect与图片增加独立于DoH的共享请求节奏；修改并发参数不重建DoH通道，本地冷却不改变连接状态，本篇凭据与路由边界继续保留。

应用采用独立编写的 Rust DoH + ECH DNS/TLS 桥接，保留 OkHttp HTTP 与 Android WebView 的既有边界。配置持久化、原生 DNS/TLS、Socket 适配、VPN 策略、客户端接入与界面按小单元提交；纯 OkHttp DoH 仅作对照。

生产接入保留 OkHttp 的 HTTP、连接池、Cookie 边界、流式请求/响应及取消机制，通过自定义 SocketFactory/SSLSocketFactory 将 DNS 与 TLS 交给独立编写的 Rust 模块。支持真实 ALPN 的 HTTP/2 与 HTTP/1.1；原生 Socket 的关闭必须可中断正在等待的读写，TLS 仍严格验证原域名及证书；`linux.do` 与子域、`ldstatic.com` 与子域必须真实接受 ECH，否则明确失败。

[DoH 地址恢复](../bug-fix/2026-10-14-doh-image-address-recovery.md)补足 TCP 建连失败后的候选优先级；仅记忆当前通道的临时失败，保留 DNS/ECH、证书和写操作不重放契约。

配置单元位于 `DohSettings.kt`，与界面显示偏好分开持久化。自定义服务器拥有稳定 ID；删除选中节点回退内置，内置不可编辑/删除；非法持久数据过滤并修复选中 ID。URL 中的私人参数仅留在应用私有配置，不进入普通日志。

Rust DNS 单元位于 `native/doh/src/dns.rs`：标准 POST 并行读取 A/AAAA/HTTPS，校验 ID/问题/rcode/截断，按域名合并在途查询并按最小 TTL 缓存，CNAME/HTTPS alias 限制 8 跳。HTTPS 查询失败与缺少 ECH 区分；DNS 引导仅解析服务器自身，不复用业务请求或凭据。依赖锁定于 Cargo.lock；6 项离线边界测试通过。

Socket/JNI 单元使用有界 64KB 读写块，不缓存完整上传或响应。读写使用独立锁，TLS 状态锁不包围阻塞 I/O；关闭走独立通道并以最多 200ms 等待片检查关闭标志，对外仍遵守 Socket 超时。ECH 被拒绝立即清除该域名缓存，只在下次请求重新解析，不重发业务请求；错误只暴露固定代码。

回归测试先复现两项问题：等待读取时阻塞另一个线程发送，以及 `read_tls` 只消费部分密文后遗失剩余数据。修复为独立读写、按 16KB 分片且循环喂完 TLS 输入；512KB TLS 响应测试与其他测试共 11 项通过。测试自签名证书/私钥仅由 `cfg(test)` 引用，生产二进制和信任根不包含它们。

生产构建由 Gradle `buildDoh` 调用 `scripts/build_doh.py`，使用 Rust、Android NDK 27 与 CMake/Ninja，仅生成 arm64-v8a，强制校验 16KB ELF 对齐；依赖锁定，输入未变复用生成库。生成文件均在忽略的 `app/build` 下；不将本机预编译二进制当作可复现源代码。

随包 `assets/licenses/native-doh-NOTICES.txt` 保留锁定 Android 依赖图 118 个组件及相关构建依赖的完整许可/版权，包括 AWS-LC、BoringSSL、OpenSSL、Fiat。`native_notices.py --check` 在 Cargo 构建后执行，兼容 Windows CRLF；jni-sys-macros 发布包缺失的两份许可从其固定 VCS 提交补入源树。构建与许可脚本明确使用 UTF-8，避免 Windows 默认编码影响 CMake 路径和 Cargo 元数据。

README 记录 Rust、NDK、Python 与 CMake/Ninja 的新增构建要求及 WebView 边界；手机运行不需要 Rust 环境。关于页告知所选 DoH 服务可看到查询域名，但不会接收社区登录凭据，并提供完整原生组件许可入口；第三方说明保留独立实现与 FluxDO 参考的区别。

Android 适配单元为 `network/DohTransport.kt` 与 `NativeDoh.kt`。原始 Socket 与 SSLSocket 共用同一个 Rust 句柄，提供 JNI InputStream/OutputStream、真实证书链和 TLS session，供 OkHttp 进行主机名复核与连接复用。配置关闭不创建原生 profile，不发 DNS。验收构建的 arm64 库为 4,979,856 字节（未压缩，不等于 APK 增量），HTTP 语义与现有会话通过下方定向验收。

`NativeAlpnPlatform` 为当前固定的 OkHttp 4.12.0 内部 Platform 扩展，向自定义 Socket 传入协议并读取真实 ALPN；安装后所有普通 Android Socket、证书、明文策略与日志继续委托原平台，关闭功能无需恢复全局对象。该库没有公开的自定义 Socket ALPN 扩展点，因而使用其现有 Platform setter；升级 OkHttp 必须重新审核这份完整委托和原生/系统两路回归，不能视为普通依赖补丁。

只使用 HTTP/1.1 的适配最简单且没有内部 API 耦合，但首次生产真机主站返回 CF 挑战，未满足目标；HTTP/2 修复后同机同会话的正式客户端返回登录有效、30 条话题和可解码头像，ECH Accepted。该对照支持采用 HTTP/2，不证明所有 CF 差异都由 HTTP 版本引起，更不能承诺免验证。

运行策略位于 `network/DohNetwork.kt`：用户选择与有效状态分离，Android 可观察 VPN 或系统 HTTP 代理时暂停。监听包含 VPN 的网络请求、默认网络变化与 Activity 恢复，每次创建请求前复查；配置/网络变化关闭原生 Socket 并清理连接池，旧代回调不能覆盖新状态。主站实际 ECH 握手才显示已连接；检测仅发送无凭据 GET，可取消并随网络切换停止。配置与策略 6 项 JVM 测试通过。

Discourse、Connect、MessageBus 通过构造参数注入 `(OkHttpClient) -> Call.Factory`，保留原客户端的 Cookie、来源、重定向和超时约束；默认是原客户端，既有测试和调用保持兼容。主站 GET 显示固定的 DoH 错误说明，写失败仍提示结果未知且禁止自动重放。

写请求再由 `DohRequestPolicy` 标记一次性 body；关闭连接重试并不能阻止 OkHttp 对 `503 Retry-After:0` 的 HTTP follow-up。MockWebServer 先复现 POST 被发送两次，修复后原样返回 503，服务端只收一次；GET/HEAD 保留既有语义，上传仍流式写入。

AppContainer 持有统一 DohNetwork，并应用于主站、Connect、Coil 图片及原图下载；图片凭据继续在每次真实网络请求处剔除/按 HTTPS 主站添加，跨源重定向不泄漏 Cookie。ImageDownloader 只依赖 Call.Factory，不持有固定路由客户端。GitHub 更新明确豁免，继续走独立系统网络且不携带社区凭据。

首页和账号通知创建的两处 MessageBus 均注入同一路由。切换时旧轮询被关闭，原有循环沿当前游标重连；不复用主站的 CookieJar 或会话拦截器。

设置入口与 `DohSettingsScreen` 已接通，展示默认关闭开关、有效状态、内置/自定义服务器列表、选择、查看/编辑/删除和分层检测结果。编辑使用既有贴底可拖动面板、无描边字段与黄色选中态；自定义引导 IP 可留空，内置节点只读。界面 CRUD、校验及 VPN 暂停显示通过真机测试。

检测结果同时核对响应正文：正常话题 JSON 才报告站点访问成功，HTML 挑战、非话题数据与 HTTP 错误分别显示；最多读取 2MiB，不存储检测 Cookie。两项检测分类测试与既有社区/Connect/消息/图片回归共 42 项 JVM 测试通过，Debug/AndroidTest/经 R8 的 Release 和 Lint 构建通过。

第一阶段保留现有系统 WebView 登录、验证和 Cookie 存储。用户可通过自己的 VPN／代理完成网页登录；原生页面启用新的 DNS／TLS 通道。第一阶段不接管 WebView TLS，不新增用户证书信任，不改网页内的导航边界。

若原生直连成功但 Cloudflare 必须在同一出口重新验证，而系统 WebView 在该线路仍无法直连，则本阶段无法保证完整闭环。届时应单独评审 WebView 同出口的 DoH + ECH 方案，或继续保持浏览器与原生请求使用一致的代理出口，不能用反复复制 Cookie 代替这个能力。

### 设置与服务器管理

- 设置提供“DNS over HTTPS”开关，首次安装默认关闭，关闭时不向第三方 DoH 服务发起预热查询。
- 内置文章普通版 `https://edge.1molchuan.top/linuxdo`。文章明确说明旧 `/plus` 已回退普通版，因此不把两个路径当作两个独立节点，也不接入文章面向 AI 服务的中继能力。
- 另内置“始皇DoH” `https://kudo.ddd.oaifree.com/query-dns`，来源为用户指定的[来源文章](https://idcflare.com/t/topic/39038)。内置节点统一由 `BuiltInDohServers` 列表管理，全部 ID 均禁止被自定义配置覆盖、编辑或删除；新增节点不切换现有选择，默认及失效选择回退仍为“社区 DoH”。持久化格式保持 version 1，升级时自动展示新增内置项。
- 内置节点只供选择、查看和检测；自定义节点支持新增、列表查看、编辑、删除和选用。字段为稳定 ID、名称、HTTPS DoH URL、可选 bootstrap IP。
- URL 保留服务路径，使用标准 DNS wire format POST `application/dns-message`；仅有 JSON DNS 接口的服务不兼容。
- 自定义 URL 必须为合法 HTTPS 地址，拒绝 userinfo 和 fragment；bootstrap 只接受合法 IP，不替换上游的 Host 或证书校验名称。带私人标识的服务器地址不进入普通日志。
- 编辑当前节点后使新配置生效；删除当前节点后回到有效内置节点。无效持久配置回到明确可用的选择，不保留悬空 ID。
- 检测结果区分“DNS 查询成功”“有 ECH 配置”“实际 ECH 握手成功”“站点请求成功”。普通 DoH 节点可能没有 ECH，不能只显示一个绿色成功就承诺全部能力。

### VPN 自动暂停

参考 FluxDO 的用户意图与生效状态分离：`effective = userEnabled && !vpnDetected && !systemProxyDetected`，初始化、切换中和错误状态另行显示。

检测到 VPN 时默认暂停 DoH，显示“VPN 已开启，DoH 暂停”；不关闭用户的 VPN，不永久抹掉用户的开关选择。VPN 断开后按保存的选择恢复；暂停期间关闭总开关则断开 VPN 后仍关闭。`vpnAutoSwitch` 默认开启且在 DoH 设置中可关闭；关闭后 VPN 和系统代理仍显示检测状态，但允许按用户分流规则继续使用 DoH/ECH。

启动时先检查 VPN，再创建需要联网的通道；运行中监听网络变化，回到前台时再次核对。Android 使用 `ConnectivityManager`／`NetworkCapabilities.TRANSPORT_VPN` 等公开能力；构建监听请求时注意默认 `NET_CAPABILITY_NOT_VPN` 可能过滤 VPN。

Android 普通 App 不能保证感知其他 UID 的全部分应用 VPN、被排除的网络或所有普通 HTTP／路由器代理。本方案承诺响应 App 可观察到的 VPN 状态，不把“VPN App 已安装／正在运行”等同于 VPN 生效。

### 原生网络与会话契约

- 集中管理 DNS／TLS 路由策略，并注入主站 API、Connect、图片及下载、MessageBus 等原生客户端。GitHub 更新客户端也需明确采用同一策略或明确豁免，不能无意遗漏独立客户端。
- 共用网络策略不等于共用 CookieJar、认证拦截器或来源许可。主站继续限定同源 HTTPS，Connect 按自己的来源读取 Cookie，图片仅对允许的站点附会话，MessageBus 与 GitHub 不携带社区 Cookie。
- DoH 客户端独立且无社区 Cookie、CSRF、Authorization。解析服务只能得到 DNS 查询，不能收到社区登录凭据。
- 保留请求原始域名与严格 TLS 证书校验，不用 IP 改写业务 URL，不放宽重定向，不忽略 SSL 错误。
- bootstrap 使用独立解析路径，避免用 DoH 解析 DoH 自己造成递归。无 bootstrap 时可用系统 DNS 引导 DoH 服务本身，并明确这一边界；不写死本次探测到的业务 IP。
- DNS／HTTPS 记录按 TTL 缓存、合并并发查询并限制等待。节点或网络变化使相关缓存及闲置连接失效，长轮询按原游标重连。
- 配置切换停止创建旧通道请求。只读请求可按原有明确规则重新请求；在途写请求不自动重放，结果未知时保留输入并通知用户确认。不能因引入传输层而恢复 OkHttp 或新库的隐式写重试。
- 节点故障需显示明确原因；不静默退回普通 DNS 再宣称 DoH／ECH 生效。ECH 配置缺失、过期、被拒绝与协商成功需可区分，实际降级须如实反馈。
- 继续使用现有 CookieManager、登录后的 `/session/current.json` 检查及验证后的 `/latest.json` 检查；不新增“把系统 Chrome Cookie 回传 App”的假设。

### ECH 与 WebView 的边界

当前 OkHttp 的 `Dns` 接口返回 IP 列表，4.12.0 的 `DnsOverHttps` 只读取 A／AAAA，不会把 HTTPS RR 中的 ECH 配置应用到 TLS。增加该依赖即可获得 DNS 替换，但不能自动获得 ECH。

正式方案通过 JNI 接入独立编写的 rustls 0.23 DNS／TLS 传输，依赖版本锁定于 Cargo.lock。FluxDO 的 Rust 子模块仅提供技术参考，没有复用其源码。构建启用 ECH／HPKE 能力，真机验收观测实际握手接受状态，而非只检查解析响应有配置。

这会增加本地二进制、构建与维护成本，还必须适配取消、超时、流式上传下载、重定向、Cookie 和错误分类。不能把它当作仅增加一个 OkHttp DNS 对象的工作量。

Android WebView 没有可直接注入 `OkHttp.Dns` 的公共 API。`ProxyController` 是 App 所有 WebView 的代理覆盖配置，需要检查 `PROXY_OVERRIDE` 支持，并等待设置／清除回调后加载新页面；它也会覆盖系统代理设置。

普通 CONNECT 隧道可以由代理决定目标 IP，但不能让不支持 ECH 的客户端自动使用 ECH。FluxDO 当前对 WebView CONNECT 接管 TLS，再在上游建立 ECH 连接，这涉及 CA、私钥与网页 TLS 信任。第一阶段不照搬此设计；后续若评审，至少需要每安装实例隔离的密钥、限定信任范围、仅本机监听的访问控制、证书错误保留和 VPN 切换时清除覆盖，不能关闭证书验证或打包共用私钥。

## Research evidence

### 文章与服务器探测

已通过用户现有浏览器读取[指定文章](https://linux.do/t/topic/2946664)。作者描述普通版补充 Cloudflare 域名的 HTTPS／ECH 记录、优选 IPv4／IPv6、拍平 CNAME、剔除不适用于普通站点的 CDN IP；普通网站出口仍是用户自己的 IP。这些服务行为来自作者说明，不代表本项目已经独立验证所有实现或运营稳定性。

使用不携带社区 Cookie、启用正常 TLS 校验的桌面 Python 探测了 `/linuxdo` 的标准 DoH POST：

| 查询 | 结果 |
| --- | --- |
| `linux.do` A | HTTP 200，`application/dns-message`，rcode 0，返回 IPv4 |
| `linux.do` AAAA | HTTP 200，rcode 0，返回 IPv6 |
| `linux.do` HTTPS（65） | HTTP 200，rcode 0，包含 ALPN、IP hints 及 71 字节 ECH 参数 |
| `connect.linux.do` A | HTTP 200，rcode 0 |
| `ping.ldstatic.com` A | HTTP 200，rcode 0 |

当时 `linux.do` A 的 TTL 为 14 秒，说明不能无限缓存或固定使用这批优选 IP。探测结果在忽略目录 `artifacts/doh-server-probe.json`，脚本为 `artifacts/doh-research-probe.py`，文章摘录为 `artifacts/doh-article-2946664.md`；这些是本地研究材料，不是自动化测试依赖。

初始 DNS 探测环境是当前电脑网络，未配置探测脚本代理；该阶段没有验证手机或业务访问。后续[真机验证](../../implemented/testing/2026-10-12-doh-ech-device-probe.md)在用户确认的非代理 Wi-Fi 上完成：系统 DNS、仅 DoH 及 Rust 不开 ECH 均失败，DoH + ECH 的会话、列表、头像均 HTTP 200，实际 ECH Accepted，现有登录有效且没有 CF 挑战。结果限于当前线路与会话，尚未重新执行代理网页登录，也未覆盖长期风控或直连 WebView 过盾。

### FluxDO 参考版本

核对的 FluxDO HEAD 为 `e1bd839ef87825347c062fb588751a1a62e02372`，与仓库现有研究快照一致。参考点如下：

- [网络设置](https://github.com/Lingyan000/fluxdo/blob/e1bd839ef87825347c062fb588751a1a62e02372/lib/services/network/doh/network_settings_service.dart)：默认关闭、内置与自定义节点、bootstrap、选中节点编辑／删除后的修复。
- [DoH 解析](https://github.com/Lingyan000/fluxdo/blob/e1bd839ef87825347c062fb588751a1a62e02372/lib/services/network/doh/doh_resolver.dart)：缓存、并发与解析配置。
- [VPN 自动切换](https://github.com/Lingyan000/fluxdo/blob/e1bd839ef87825347c062fb588751a1a62e02372/lib/services/network/vpn_auto_toggle_service.dart)：启用 VPN 时压制，停用后按记录恢复，启动先同步网络状态。
- [WebView TLS 策略](https://github.com/Lingyan000/fluxdo/blob/e1bd839ef87825347c062fb588751a1a62e02372/lib/services/network/doh/webview_mitm_policy.dart)：`useMitmConnect` 当前返回 true，不能把 FluxDO 的效果归因于单纯更换 DNS。

FluxDO 主项目为 GPL-3.0，本项目是 Apache-2.0；参考用户交互与设计，不能直接把 Flutter 代码复制过来而假设仍仅按本项目原许可分发。其独立 Rust 子模块 `fluxdo_doh` 固定为 `08d3468f0a1eb2858840c525d61f8aa888696522`，[README](https://github.com/Lingyan000/fluxdo_doh/blob/08d3468f0a1eb2858840c525d61f8aa888696522/README.md) 声明 MIT，但该提交的仓库树没有完整 LICENSE 文件，Cargo.toml 也没有 license 字段；正式复用前需补齐许可文本与版权归属核对。

子模块使用 rustls 0.23，ECH feature 非默认开启；`with_ech` 配置受信任根证书，连接后读取 `ech_status()`。这提供了可行的技术参考；本项目的 Kotlin／OkHttp 生产接入另由下方真机测试验证。

### 官方资料与 CF 风险依据

- [OkHttp 4.12.0 DoH 实现](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp-dnsoverhttps/src/main/kotlin/okhttp3/dnsoverhttps/DnsOverHttps.kt)：只读取 A／AAAA，返回 `InetAddress`。
- [Android ProxyController](https://developer.android.com/reference/androidx/webkit/ProxyController)：WebView 全局代理覆盖、feature check 与生效回调。
- [Android NetworkCapabilities](https://developer.android.com/reference/android/net/NetworkCapabilities)：VPN transport、网络能力与默认过滤边界。
- [Cloudflare Clearance](https://developers.cloudflare.com/cloudflare-challenges/concepts/clearance/)：`cf_clearance` 与特定访客、设备关联，不能等同社区登录会话。
- [Cloudflare Challenge solve issues](https://developers.cloudflare.com/cloudflare-challenges/troubleshooting/challenge-solve-issues/)：不稳定网络、浏览器配置、VPN／代理以及 User-Agent 变化可能影响验证；某些挑战子域 DNS 失败是预期的非致命情况，不应据此自动弹过盾。
- [Cloudflare Challenge Passage](https://developers.cloudflare.com/cloudflare-challenges/challenge-types/challenge-pages/challenge-passage/)：验证有效期由站点设置，不能保证永久放行，且不覆盖限流规则。
- [Cloudflare TLS 指纹说明](https://developers.cloudflare.com/bots/additional-configurations/ja3-ja4-fingerprint/)：不同客户端发起 TLS 的特征不同。此文说明平台能力，不证明 Linux.do 启用了特定套餐或某条具体规则。

工程判断：DoH 查询本身不会给网站增加一个“启用 DoH”请求头；ECH 隐藏的是链路中的 ClientHello 信息，不会让网站看不到访客 IP，也不是免过盾。切换代理出口、改用不同 TLS 栈或短时间大量重连，都可能改变风控结果。官方资料没有给出本站完整判断规则，不能把“切 IP 必然失效”或“使用 ECH 必然免验证”写成事实。

## Related decisions

检索 proposed／implemented／rejected 的 DoH、DNS、ECH、VPN、Cloudflare、Cookie 与网络机制；没有既有 DoH 提案。

- [Discourse 数据契约](../../implemented/architecture/2026-10-07-discourse-data-contract.md)部分重叠：本篇提出传输策略，原有来源校验、凭据隔离和写请求不重放继续成立。
- [可关闭的 VPN 自动切换](2026-10-14-vpn-auto-switch.md)在本篇默认暂停策略上增加用户可选例外；本篇的网络边界、ECH 约束和 WebView 分工继续适用。
- [人工验证流程](../../implemented/bug-fix/2026-10-11-cloudflare-verification-flow.md)部分重叠：本篇评估换出口后的能力边界，保留原有真实挑战识别、Cookie flush 与原生读取成功才完成验证的规则。
- [原生客户端](../../implemented/architecture/2026-10-07-native-community-client.md)、[应用内网页](../../implemented/feature/2026-10-10-in-app-web-links.md)、[通知传输](../../implemented/architecture/2026-10-10-notification-stream.md)及[账号登录](../../implemented/feature/2026-10-07-account-and-composer.md)提供当前事实，本轮不改变它们的模块、导航、会话或业务协议。
- 其余网络关键词命中是分页、图片资源、通知展示或历史测试，与本次新增 DNS／TLS 策略没有同一决策归属。没有完全吸收、需要归档或已经过时的提案。

## Alternatives considered

1. **只添加 OkHttp DoH**：改动小、沿用成熟 HTTP 栈，较少改变 TLS 指纹，能改善 DNS 污染问题。但只消费 A／AAAA，无法利用文章 ECH 能力；线路若存在 SNI 或 IP 层阻断，不能满足最终目标。保留为对照和较窄能力，不能作为完整方案交付。
2. **原生请求接入真正支持 ECH 的传输**：能使用文章提供的 HTTPS／ECH 配置，不必接管 WebView TLS，范围最接近用户“代理网页登录后原生直连”的流程。独立探针验证后采用此方案；代价是 JNI、本地库、HTTP 语义适配及 TLS 指纹变化，正式接入也需单独通过真机验证。
3. **完整复刻 FluxDO 的 WebView MITM／网关**：网页和原生可统一解析与上游出口，覆盖重新过盾和外链浏览，闭环最完整。但新增证书信任、私钥、本地代理生命周期和网页兼容成本，且不能直接复制 GPL 主项目。当前用户只明确要求代理完成 WebView 登录后原生继续访问，不把此项并入首阶段；同出口验证成为阻塞时再单独评审。

## Verification

RMX2202 / Android 13，当前用户确认的非代理 Wi-Fi：正式 DiscourseClient 读取现有会话、30 条话题与图片客户端同源头像成功，实际 ECH Accepted；没有为验证切换账号或发布真实内容。Debug 签名与原安装一致，采用 `adb install -r`，没有卸载或清数据。版本仍为 0.1.9 / 1010。

| 验收场景 | 必须确认的结果 |
| --- | --- |
| 新安装／功能关闭 | 默认关闭，不发生 DoH 查询；原有联网路径和会话边界保持 |
| 自定义节点 CRUD | 保存重启后保留；可选用、编辑、删除；当前节点删除不悬空；无效 URL／bootstrap 有明确反馈 |
| VPN 启动与运行中切换 | 首个请求前识别可见 VPN；暂停 DoH；断开按用户选择恢复；暂停期间关闭开关后不恢复 |
| 分应用 VPN／普通代理 | 记录设备可见性结果与检测上限，不以一次全局 VPN 测试宣称全部支持 |
| DNS 与 bootstrap | 标准 A／AAAA／HTTPS、TTL、并发去重、超时、错误响应、证书失败和 bootstrap 防递归有定向测试 |
| 实际 ECH | 真机 TLS 握手报告接受；缺配置、过期及拒绝能区分；解析成功不能冒充握手成功 |
| 目标完整流程 | App WebView 通过用户代理登录；返回、关闭代理、启用 DoH 后，原生 `/session/current.json` 和 `/latest.json` 成功且账号身份保持 |
| 网络覆盖 | 话题、分页、图片、图片下载、Connect、长轮询和更新检查的网络策略均明确，失败不会污染其他页面 |
| 受限凭据 | DoH、GitHub、MessageBus 无社区 Cookie；图片／Connect 按各自来源；TLS、同源和重定向限制不放宽 |
| 配置变更与写操作 | 老缓存／闲置连接不使新配置假生效；长轮询重连；写结果未知不重发，草稿保留 |
| 再次遇盾 | 只依据真实响应提示；保留登录状态；原生检查失败不假报验证成功；直连 WebView 不通时明确需要后续能力 |

自动回归包括 11 项 Rust 测试、42 项相关 JVM 测试、9 项真机测试，均通过。真机覆盖 gzip/分块响应、连接复用、HTTP/2 双向流、512KB 流式上传断线不重发、请求取消、自签名证书拒绝、模拟 VPN 路由暂停/恢复、CRUD 与无效地址反馈；测试的 loopback IP 不经过第三方 DNS。UI 和路由夹具将配置存入测试 APK 私有目录，避免修改用户节点或开关。

`dohProductionLive=true` 才启用线上只读会话测试，普通测试跳过该项。该项使用正式 AppContainer 的客户端，临时启用后恢复原配置，只写入登录有效性、话题数量、头像解码与 ECH 接受状态，不记录 Cookie 或用户身份。

Debug、AndroidTest、R8 Release 和 Lint 通过；Release APK 通过 `zipalign -P 16`，JNI 类及方法在 R8 后保留。原生依赖完整许可校验和 `npm run verify-notes` 通过。结果保存在忽略目录 `artifacts/doh-validation/production-final-device-tests.txt`、`production-final-build-checks.txt` 与设备私有目录的无身份结果 JSON。

验证范围限于当前 Wi-Fi 和现有登录。没有新走一次代理网页登录、切换真实 VPN App 或蜂窝线路；系统 VPN 监测使用公开回调，分应用 VPN 的 UID 可见性仍有上限。Connect/MessageBus 路由和来源边界有模拟回归，未为本次测试触发真实写操作。上表保留用户复验用例，不把未执行的网络场景记作通过。

新增“始皇DoH”后，定向运行 `DohSettingsTest` 的 8 项测试通过，覆盖双内置列表、默认与旧选择保留、新节点选用及持久化、自定义节点并存、全部内置 ID 的改删与覆盖保护。电脑端独立无凭据标准 DoH POST 探测中，`linux.do` 的 A、AAAA、HTTPS 查询均为 HTTP 200 / rcode 0，HTTPS 回答包含 ECH 参数；这只验证解析响应，尚未用手机验证该新节点的实际 ECH 握手或主站访问。

## Consequences

| 风险 | 判断与处理 |
| --- | --- |
| 关闭代理后再次 CF 验证 | 重点验收项；出口变化、访客／设备特征及 TLS 栈变化可能影响有效性，无法从文档估算概率。保持共享 Cookie 与 UA，减少重连，只读验证实际结果 |
| DoH 可用但网站仍不通 | DNS 无法绕过全部 IP／SNI／线路问题；必须验证实际 ECH 接受和业务请求，不能只显示 DNS 测速成功 |
| 原生可连但无法直连过盾 | 首阶段 WebView 不接管 ECH，可能出现验证闭环缺口；发生时单独评审同出口网页通道 |
| 第三方服务稳定性与隐私 | 社区公益服务无可确认 SLA，可能改地址或优选策略；服务商可见查询域名，文章称国内站点使用 ECS 网段；默认关闭、允许自定义节点、不发送社区凭据 |
| 解析回答改变目标节点 | 必须维持原域名证书校验；不得因采用优选 IP 而关闭 TLS 校验，优选 IP 不固定缓存 |
| VPN 可见性 | Android 平台存在分应用和 UID 边界；启动、网络回调及前台复查降低漏切换，但不承诺识别任意系统外代理 |
| ECH 原生库 | 增加 APK、本地构建及维护成本；需验证项目既有 arm64 与 16KB 对齐要求、依赖许可、取消／流式语义和真实 TLS 指纹表现 |
| WebView 网关扩展 | 若后续启用，CA 信任和本地代理会扩大风险面；需要独立设计与验证，不能作为普通 DoH 开关的隐含副作用 |

收益是获得可选择的加密 DNS、避免与可见 VPN 同时接管网络，并为无需常开代理的原生访问提供技术路径。已知上限是普通 DoH 不带来 ECH、ECH 不保证线路与 CF 放行；当同出口网页验证成为必要条件时重访 WebView 边界。
