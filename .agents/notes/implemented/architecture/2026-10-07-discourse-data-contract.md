# Agent Note: Discourse 请求与数据契约

Status: implemented

## Problem

话题列表、详情和写操作具有不同的响应和认证边界。只读取首批楼层会截断长帖，盲目重试写请求会重复发帖，错误地跟随分页地址则可能泄露会话。

0.3.9 的会话反馈表现为未重启时网页登录仍似已登录、原生刷新失败，重启后必须重新登录。版本差异没有直接修改登录代码；本次确认的是本地夹具可复现的续期接收与并发回写缺口，尚未复现反馈者在真实 VPN 出口下完整的长期失效过程。

## Decision

data 包分开模型解析、DiscourseClient 和 CommunityRepository。分页使用 more_topics_url，帖子使用 post_stream.stream 按 20 个 id 补齐；JSONObject 显式处理缺失或 null 字段。请求层只允许 baseUrl 同 scheme、host、port 的地址，禁用 OkHttp 自动重定向和失败重试。只读 GET 可显式跟随最多三次同源重定向，每次重新验证来源；写操作和跨源重定向绝不重放。

真机验证发现 `/c/{id}/l/latest.json` 返回规范地址的 301。服务端可在规范地址中加入分类 slug 或父分类，故客户端接受服务端同源 Location，不在所有页面重复拼接和缓存路径。

列表、搜索和详情共用标签解析：兼容字符串与 `{id, name, slug}` 对象，只接受非空字符串 name，跳过未知对象和 null。Topic 保留 tags 名称列表，同时通过 tagDetails 保留 slug 及可选 icon/color；详情保留同样的标签模型。真机返回新版对象标签，不能以 optString 将它序列化后显示给用户。Post 保存 updated_at 和 version；updated_at 缺失回退创建时间，version 大于1才视为已编辑。列表不提供编辑版本，不为编辑标记额外请求详情。展示和同源主题图标目录见[主题展示](../feature/2026-10-09-topic-presentation.md)。

分类沿用 /site.json，icon 仅接受字符串，空白、null和异常类型归为空；名称与ID不参与图标推断。[分类图标](../feature/2026-10-08-category-web-icons.md)使用此名称索引官网一致的本地矢量目录，不把远程字段当作图形代码执行。

Member 保留独立 animated_avatar、资质 flair_* 和状态。账号/用户资料使用 status，帖子使用 user_status；完整响应中状态缺失与列表中状态未知分开表示，防止旧缓存恢复已清除的状态。帖子与搜索结果的作者使用 user_id，缺失时为 0，绝不把帖子 id 当作用户身份。新增字段只接受字符串，错误类型不转成图片地址；非法结束时间不转成永久状态。表情目录按分组解析 name / url，保留自定义上传地址。展示语义见[头像方案](../feature/2026-10-08-avatar-presentation.md)。

AvatarMetadataCache 合并 250ms 内的可见作者请求，每批最多 50 个 ID，相邻批次开始至少间隔 1 秒，使用 /user-cards.json?user_ids= 读取完整角标。只补齐无资质且状态未知的作者；ID 与用户名都匹配才应用结果。缓存最多 256 人、有效 5 分钟，失败退避至少 15 秒，429 采用更长的服务端等待时间，单次辅助请求最多 5 秒；失败不传播为页面错误。更长缓存和批次间隔减少来回刷帖的附加请求，不延迟帖子正文加载；状态结束时间仍在本地隐藏已到期状态，完整响应仍可立即清除旧状态。/emojis.json 目录在缓存生命周期内复用，支持官网自定义表情地址。缓存拥有独立可取消任务，账号切换时调用 close，旧请求不能进入新账号实例。

详情作者优先使用第一楼完整资料，深链未加载第一楼时使用 details.created_by 与主题 created_at，不能把首个已加载回复当成楼主。valid_reactions 保留服务器允许的回应名称。Post 区分 reactions、reaction_users_count、current_user_reaction 和 can_undo；其他回应即使同步到了 actions_summary 的赞，也不能被显示或撤销成爱心。

L 站回应使用 PUT /discourse-reactions/posts/{post_id}/custom-reactions/{reaction}/toggle.json，短按选择当前回应名称，没有回应才使用 heart；再次选择相同名称会撤销，换种类由服务器原子切换。计数和当前回应使用写接口返回的完整帖子，不自行累加。无插件数据时保留核心 post_actions 的爱心流程；写请求始终不自动重试。用户详情的 can_follow / is_followed 决定关注入口，通过 PUT /follow/{username}.json 关注、DELETE 同址取消，成功后更新本地状态。用户名和回应名均按单一路径段编码，客户端不拼接任意远端路径。

[详情状态](../feature/2026-10-09-topic-author-and-responses.md)将主帖补齐和关注资料作为辅助读取；分页保留已完成的写入，账号 ID 参与 ViewModel 身份。

AndroidSessionCookies 与官网 WebView 共用 CookieManager。CSRF 通过互斥刷新；失败的写请求不自动重发。401、验证页、权限拒绝、429、服务端错误和网络错误分型。图片仅向 HTTPS linux.do 附加 Cookie，跨站图片不携带账号凭据。备份禁用，设置和草稿在应用私有目录；退出清除账号草稿。

会话续期的响应 Cookie 在网络回调交付协程之前保存，保留服务器原始 Set-Cookie 属性；页面退出取消协程不能丢掉已经收到的续期。接收发生在响应头到达后，不等待正文读取成功，不重放任何写请求。

Connect 等级请求也在回调接收自己的来源 Cookie，并沿用同一存储代次；仍只发送到 connect.linux.do，不跟随认证跳转，不把独立服务的认证失败当作主站登出。

图片网络拦截器对每一跳按 scheme、host、port 核验主站来源，并在跟随重定向前保存该跳的 Set-Cookie。跨站响应不参与社区 Cookie 写入，主站凭据也不发送到非默认端口；测试通过注入本地来源验证相同边界。

文件下载采用相同的逐跳接收规则，先保存主站下载/重定向响应，再读取后续跳转的 Cookie；CDN 响应不能覆盖社区会话。`createDownloadHttpClient` 统一生产和夹具使用的下载网络策略，DoH 只包裹该客户端，不替换认证拦截器。

`SessionCookieStore` 串行读取、批量写入和 flush，Android 仍以进程共享的 WebView CookieManager 为唯一存储，不建立第二份持久 CookieJar。请求快照只在内存持有发送时的 Cookie 和会话代次；退出清理或网页登录边界推进代次，旧代次响应不能写回。相同代次下，若 `_t` 已被网页或另一响应更新，旧请求不能再覆盖或删除 `_t` / `_forum_session`，其他 Cookie 保留各自正常更新。快照不输出凭据，原始属性和到期时间不改写，服务端明确删除当前会话仍然生效。

AndroidSessionCookies 的多个包装实例共用同一存储锁和代次；无 callback 的 CookieManager 写入完成后按响应批量 flush。全量退出清理在 IO 线程持有同一锁执行，先推进代次、清理，再 flush。真机持久化夹具仅写入保留的 `.invalid` 来源，跨独立 instrumentation 进程核对持久 Cookie，不清空或读取真实社区凭据。

图片与下载为整个调用保存会话边界，每跳另取实际发送快照，防止重定向途中换号后改用新账号凭据继续旧调用。响应按该跳发送快照批量接收；失效代次的响应关闭后失败，不继续读取或重放。单纯 `_t` 续期不推进代次，下一跳仍可使用已接受的新 Cookie。

API 的 CSRF 获取、只读重定向与一次过盾重试也绑定起始代次；读取正文前后再次核验，旧账号的延迟正文不能作为新会话返回，旧 CSRF 不能缓存到新代次或触发后续写入。会话失效只会终止旧调用，不自动重放写操作。

CSRF 缓存本身也携带签发请求的代次，并以 volatile 发布：即使退出 reset 与旧协程的缓存赋值交错，新代次也不会复用旧缓存；下次真正写入前重新获取 CSRF。仅检查赋值前代次无法覆盖检查与赋值之间的并发窗口，`aLateCsrfCacheWriteAfterResetIsNeverReusedByAnotherAccount` 用可控检查栅栏复现并守住该边界。

CSRF 成功响应必须包含非空字符串 `csrf`，缺失、null、空白和错误类型按服务端格式异常处理，不提示登录失效、不缓存、不发送后续写请求；401 与明确 `not_logged_in` 仍保留认证错误，验证、权限、限流和网络错误保持各自分类。用户明确重试后重新获取 CSRF，不能自动重放写入。

[主站共享冷却](../bug-fix/2026-10-14-request-rate-cooldown.md)补齐 429 后的跨功能保护：按来源保存服务端等待截止时间，冷却中的新调用在本地返回剩余秒数。会话检查不清除此状态；写请求不排队、不自动重放。

[DoH 与 ECH 接入](../feature/2026-10-12-doh-network.md)提供可选的统一 DNS／TLS 策略，保留本篇的同源、凭据隔离和写请求不重放契约；WebView 仍使用系统网络。

Discourse 的 401 和携带 `error_type: not_logged_in` 的 JSON 403 是认证信号；其余 JSON 403 保持权限错误，不按中文文案猜测类型。有 `_t` 的普通 GET 遇到认证信号时，先只读复核 `/session/current.json`；仅严格有效的用户响应允许将当前 GET 重试一次。会话端点、无凭据访客、已明确删除的凭据和所有写请求不进入这条恢复路径，重试再失败就结束。复核的网络、验证、限流和格式异常保持各自分类，不能转成重新登录提示。

同凭据、同代次的自动复核采用互斥合并，并将成功、访客或异常结果在内存缓存 30 秒，避免并发认证信号和短时间连点重复请求；Cookie 变化或会话代次变化使旧结果不可复用。复核前后与重试均检查起始代次，退出或换号不能继续原账号的读取；不存储候选旧 Cookie，不扩大既有同源和写入边界。

## Alternatives considered

- 使用 User API Key 执行所有接口：接口层简单，但 FluxDO 记录了 linux.do 的 scope 和限流限制，不能假定 write scope 可用。
- 在协程读取响应时顺带保存 Cookie：代码集中且容易跟踪，但协程取消可能先于调度恢复，导致已经收到的续期响应被关闭而未保存；将保存提前到网络回调。
- 独立持久 CookieJar 作为原生权威：便于完全控制落盘和元数据，[FluxDO 的 Cookie 架构](https://github.com/Lingyan000/fluxdo/blob/bc5a04b0fc09bde915415e2ab345bc24c8c8146c/docs/cookie-sync-status.md)采用此机制；但 Android 现有网页与原生已经共享 CookieManager，双存储会引入属性和同步冲突，先补全共享库的响应入口与代次边界。
- 把会话 Cookie 强制改为长期有效：冷启动时看似不会消失，但无法延长服务端有效期，也破坏过期和退出语义；严格保留站点签发属性。
- 按页码猜测所有接口分页：实现一致，但 topic post_stream 不是普通页码，且服务端可能改变列表游标；使用服务端返回的链接和楼层 id。
- 所有重定向一律报错：边界最简单，但会让正常分类规范地址无法加载；只读、同源、最多三跳保留边界，写操作仍禁止重放。

## Consequences

2026-11-10：`receivedRenewalIsSavedEvenWhenTheWaitingCoroutineWasCancelled` 在旧实现稳定复现续期 Cookie 丢失（`artifacts/session-cookie-cancellation-repro.txt`）；修复后 DiscourseClientTest 定向回归通过（`artifacts/session-cookie-cancellation-fixed.txt`）。这是本地服务器的接收时序复现，不能据此断言已复现用户的生产会话失效。

响应有 Cookie 变更时会同步等待一次批量落盘，原生网络路径在后台线程执行；串行保护可能增加并发响应的等待，但避免彼此交错并减少逐 Cookie flush。共享 WebView 库仍受系统 WebView 与服务端会话策略约束，不能延长服务端已撤销的登录，也不能保证 VPN 出口变化不触发站点风控。

最终 88 项关联 JVM 用例通过（`artifacts/session-final-tests-build.txt`），覆盖图片/下载逐跳续期与来源隔离、接收时取消、跨账号代次、旧 CSRF 缓存回写和有界会话复核；不以保留本地用户资料代替真实认证。

RMX2202 / Android 13 的 16 项常规真机用例通过（`artifacts/session-final-device.txt`，runner 另报告两项默认跳过的分阶段用例）。网页登录错误中文可读，WebView 与原生共享 Cookie，循环回环请求模拟系统路由的 VPN / 代理状态变化后仍发送已续期凭据；没有切换手机实际 VPN 或请求生产登录接口。`seed` 与 `verify` 分别在不同 PID 完成持久 Cookie 写入、重启读取及清理（`artifacts/session-final-device-seed.txt`、`artifacts/session-final-device-cold-start.txt`），保留 Secure / HttpOnly / 有效期及实际子域隔离；CookieManagerCompat 回读的 Domain 字段不能单独证明 host-only，故直接核对对子域不可见。

Debug / AndroidTest 构建及 Lint 通过（`artifacts/session-final-tests-build.txt`、`artifacts/session-final-lint.txt`）。开发版以原签名和显式 0.3.9-dev / 3010 覆盖安装，`firstInstallTime` 仍为 2026-10-23 14:55:34，未卸载、清理用户数据或更改正式版；测试只清理自身的假域名 Cookie 与独立偏好。

通知类型14是custom。[解决通知](../bug-fix/2026-10-10-solved-notifications.md)结合专用message识别；data.title中的插件翻译键不能遮蔽topic_title，保留原始类型、楼层及独立解决状态供原生界面使用。

[首页推送](../feature/2026-10-08-home-live-refresh.md)使用固定推送域名的独立无 Cookie 客户端；主站 HTML 引导仍复用本篇同源验证，只提取 shared_session_key，不放宽常规 API 的地址边界。

接口错误可以在原生界面提供具体恢复入口。直接 HTTP 仍可能遇到 Cloudflare 指纹限制，网页验证成功不保证 OkHttp 在所有网络可用。关注与回应采用已核实的站点插件契约，其他复杂插件正文保留网页入口。

Cloudflare challenge is detected from the challenge response header and common challenge-page markers limited to HTML responses, including successful HTML responses that are not JSON. Markers inside normal JSON are not treated as a challenge, and a non-HTML 403 keeps its permission-error meaning. The client reports an explicit manual-verification message instead of treating the page as empty content; the WebView remains the recovery path because completing a browser challenge does not guarantee that every OkHttp request will pass.

## Verification

2026-11-10：`CsrfResponseTest` 先复现成功响应缺字段被归为登录失效，再覆盖 null、空白、错误类型、不缓存和手动重试，共 3 项通过；`ApiSessionRecoveryTest` 先复现普通认证信号未经复核直接失败，再覆盖有效/失效/异常会话、四路并发合并、30 秒冷却及到期、换凭据、换号、取消、续期 Cookie 和重定向/过盾的重试上限，共 16 项通过。相关 8 类 JVM 回归共 66 项通过。证据为 `artifacts/csrf-response-repro.txt`、`artifacts/api-session-recovery-repro.txt` 与 `artifacts/api-session-recovery-tests.txt`；CSRF 换号用例改用同步栅栏，避免用固定正文延迟猜测调度时机。所有写入使用本地夹具，没有向社区重放写操作。

2026-10-09 其他回应被误认为爱心及短按发错撤销请求的两项测试先失败，输出 artifacts/topic-reaction-repro.txt。修复后关注/回应契约 9 项、正文解析 10 项、头像模型 7 项全部通过，输出 topic-contract-fixed.txt。覆盖服务端计数、当前回应、不可撤销、合法路径段、失败不重试、关注成功确认、深链作者和首楼完整头像。线上字段、表情图片仅做只读核查，写操作使用 MockWebServer。

集中测试覆盖同源地址限制、null 字段、作者关联、点赞与收藏、写请求不可自动重放以及 401 / 403 / 429。分类 301 的复现测试先失败，修复后验证规范地址、跨源拒绝、循环上限和写请求不重放。最新真机公开列表成功，详情可能要求网页验证；不以某一接口可用推断全部可用。

AvatarModelTest 的帖子作者 ID 复现先失败（artifacts/avatar-model-repro.txt），随后覆盖真实用户 ID、缺失用户 ID、原动图、图标/图片资质、两种状态与到期边界、未知/空状态和自定义表情目录。

AvatarMetadataCacheTest 验证 61 人分两批、重复作者合并、完整响应清空状态、LRU 上限与过期、失败退避、错身份拒绝、换号取消、表情单次读取和真实 Repository 请求格式。

长时浏览新增的三项复现先失败（artifacts/auxiliary-request-repro.txt）：两分钟后重复作者重新请求、连续批次突发发送、服务端要求 60 秒却在 20 秒后又请求。修复后 AvatarMetadataCacheTest 全部 10 项以及头像模型、首页推送、阅读同步定向回归通过（artifacts/auxiliary-request-fixed.txt）。真机头像与首页共 13 项通过（artifacts/auxiliary-request-device.txt），不对真实社区进行压力测试。

头像数据与缓存共 14 项定向测试通过，输出 artifacts/avatar-metadata-final.txt。失败退避用例曾复现 CompletableDeferred(null) 选中 parent 构造器导致无限等待，改为显式完成的空结果后通过；空结果也必须有确定完成状态。
