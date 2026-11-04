# Agent Note: Linux.do 深层链接路由

Status: implemented

## Problem

系统入口仅接收 /t/ 链接，用户主页及其他站内地址无法进入应用。同一个 URL 再次唤起不会触发 Compose effect，恢复 Activity 时又可能重复导航。扩大入口后显式浏览器动作可能被应用自身接回。

## Decision

[外部链接打开方式](2026-10-14-external-link-opening-mode.md)以 linux.do 及其真实子域名为应用内边界；本篇原生解析仍仅用于 linux.do 主域，子域名进入网页，不扩大系统深链 Manifest 域名。

应用内未知页面与外站的回退由[应用内网页](2026-10-10-in-app-web-links.md)部分替代；本篇保留系统入口消费和原生目标格式。

同源 HTTP(S) 链接解析为帖子、用户、分类、首页四种目标；解析与 Android 导航分离。帖子保留 /t/id 兼容，并支持 /t/slug/id/number；用户路径忽略用户名后的子路径；分类支持 /c/id、/c/slug/id 和嵌套 slug 后的有效 ID。每次新 Intent 作为独立事件消费，重建后不重放已消费地址。其他系统传入的站内地址清回首页并选中首页页签，非站内地址不参与路由。显式浏览器动作使用浏览器包名避免自身回环。

官方 HTTPS `/apps/<UUID>` 系统深层链接解析为携带完整 URL 的 MiniApp 目标；与[小程序运行视图](2026-11-04-mini-app-immersive-runtime.md)共用 MiniAppLocation 的来源、端口和路径校验，进入现有 web 导航条目及沉浸容器，不复制运行时或改用底层 iframe。应用 ID 只用于识别路径，导航地址保留全部 query/hash、重复参数、百分号编码及 `hb` 等启动参数。`/apps` 仍进入原生广场，无效 UUID、额外路径或 HTTP 运行页沿用其他系统站内地址回首页的规则；非官方来源不处理。每次新的小程序 Intent 独立消费，返回后同址再开、前台切换到另一红包均有独立导航上下文；恢复已消费 Activity 不重放链接，关闭仍回到来源。正文链接使用同一目标，网页内部跳转继续留在原 WebView。

正文普通文字链接与 onebox 共用传入的 onLink 回调。Compose 的 LinkAnnotation.Url 默认使用 LocalUriHandler，RichContent 在正文范围提供自己的 UriHandler，避免文字链接绕过应用路由；回调重组时随之更新。文本仍保留选择、链接语义和样式，显式“浏览器打开”继续使用独立浏览器动作。

带链接的正文图片保留最近一层非灯箱 a[href] 的合法 HTTP(S) 目标，点击通过同一个 onLink 路由；图片 URL、原图、相册归属和长按图片菜单仍独立使用图片资源。灯箱 a.lightbox 仅提供原图地址，不成为网页目标。引用与已揭开的剧透图片遵循同一规则；未揭开的剧透先揭开。此规则覆盖话题 3119942 的静态 SVG 红包图片，不按红包网站域名硬编码行为，也不执行图片内脚本。

## Existing note audit

[原生客户端](../../implemented/architecture/2026-10-07-native-community-client.md)、[数据契约](../../implemented/architecture/2026-10-07-discourse-data-contract.md)和[详情作者](../../implemented/feature/2026-10-09-topic-author-and-responses.md)部分重叠，分别保留导航结构、网络同源和深链主帖补齐的职责。本篇不替代上述决策。检索未找到独立深链决策或过时提案。

## Alternatives considered

- 将所有 URL 直接注册为 Navigation deepLink：可少写入口代码，但用户子路径及任意站内地址的首页回退仍需统一解析，且需要独立处理重复 Intent，故使用显式目标解析。
- 按 URL 文本去重：能阻止恢复时重复入栈，但无法区别恢复与用户再次打开同一地址，改为事件消费和恢复标记。
- 继续让文字链接使用系统 UriHandler：无需自定义处理，但未关联域名时打开浏览器，且与 onebox 行为不一致。正文范围提供 UriHandler，复用现有链接分流并保留 Compose 链接交互。
- 仅把所有 `/apps/` 系统链接交给普通网页可减少目标类型，但会接受畸形路径，也把小程序识别与运行页校验拆成两套；使用携带原始启动地址的独立目标，并复用运行页的严格解析。目录和无效系统链接继续遵循既有规则。

## Verification

小程序系统链接先由 LinuxDoLinkTest 复现 `Home` 目标（`artifacts/mini-app-deeplink-repro-build.txt`），DeepLinkUiTest 的新增真机用例也复现没有进入网页条目（`artifacts/mini-app-deeplink-repro-device.txt`）。修复后 LinuxDoLinkTest 与 MiniAppInfoTest 共 16 项 JVM 测试通过；DeepLinkUiTest、MiniAppArticleUiTest 及胶囊关于用例共 14 项真机测试通过，记录为 `artifacts/mini-app-deeplink-fixed-build.txt`、`artifacts/mini-app-deeplink-fixed-device.txt`。覆盖首次链接、返回后同址再开、前台切换另一红包、逐级返回来源、事件消费、重复 query、百分号编码、hash、目录及非法来源回退；正文入口继续进入沉浸容器。

通过显式 VIEW Intent 启动真机 MainActivity，系统报告首次为 COLD，第二次 Intent 投递到当前顶层实例。调试页只读取 WebView 的 URL，确认两次 `hb`、重复 `room`、编码 `next` 和 hash 均保留，并由容器补上 embed_mode，记录为 `artifacts/mini-app-deeplink-cold.json`、`artifacts/mini-app-deeplink-warm-launch.txt` 和 `artifacts/mini-app-deeplink-warm.json`。线上页面遇到 Cloudflare 验证，截图 `artifacts/mini-app-deeplink-cold.png`，因此该检查仅证明真实 Activity 的链接投递与参数保留，不声称线上应用已运行。开发版 Manifest 使用 debug.kudo.invalid，显式组件启动不等于验证系统域名关联；正式版既有 linux.do 域名过滤未改变。此前冷/热启动未验收的记录仍作为历史证据保留；本次没有新增真实旋转重建验收。

本轮外观和深层链接共 17 个不同真机用例通过，Lint 通过（`artifacts/mini-app-refinements-lint.txt`）。`npm run verify-notes` 验证 169 篇笔记通过（`artifacts/mini-app-deeplink-notes-final.txt`），UTF-8 中文和 diff 空白检查通过。覆盖前后均为同签名 0.3.1-dev / 3002，首次安装时间保持 2026-10-23 14:55:34，未卸载或清理数据。

LinkedArticleImageUiTest 先在未修改运行时代码的真机复现图片点击未调用链接回调，记录为 `artifacts/redpack-linked-repro-result.txt`。修复后 3 项用例通过，覆盖 SVG 浅深色实际渲染、更新后的回调、长按图片菜单、普通图片与灯箱原图、剧透先揭开再跳转，记录为 `artifacts/redpack-linked-final-device.txt`。既有 RichContentLinkUiTest 和 SpoilerContentUiTest 的 9 项回归通过，另 1 项线上审计按默认开关跳过。ContentParserTest、QuoteParserTest 和 SpoilerParserTest 集中验证通过，包含链接与图片资源分离、相对链接、实体与参数、危险 scheme/凭据过滤及引用/剧透递归。截图 `artifacts/linked-redpack-light.png` 和 `artifacts/linked-redpack-dark.png` 已检查，文字可读。设备仍为 RMX2202 / Android 13，使用同签名 0.3.1-dev / 3002 覆盖安装，未卸载或清理数据。

分类链接先由 LinuxDoLinkTest 新用例复现错误回到 Home，日志 artifacts/category-link-repro.txt；修复后精确断言分类 ID，DeepLinkUiTest 同时更新为分类入栈及返回原话题。新增分类改动随应用内网页20项集中真机回归全部通过，日志 artifacts/in-app-web-final-device.txt。

LinuxDoLinkTest 的4项测试通过，覆盖 slug、楼层、用户子路径、无效 ID、查询参数及非本站来源。DeepLinkUiTest 的2项真机用例直接调用生产 IncomingLinuxDoLink 与导航 helper，验证初次消费、返回后同址再开、用户子路径及首页页签回退；它们不声称验证 Activity Intent 生命周期。第8楼滚动由 TopicPresentationUiTest 的实际详情列表验证，截图 presentation-floor-eight 已查看。

现场点击正文文字链接时复现绕过 onLink。RichContentLinkUiTest 首先失败（expected URL、actual 空列表），日志 artifacts/presentation-link-repro-tests.txt；提供正文 UriHandler 后通过，同时验证回调重组和外站地址仍交给应用分流。修复后详情、导航及文字链接8项回归全部通过（14.544秒），日志 artifacts/presentation-link-fixed-tests.txt。结合先前46项回归，共47个不同UI用例通过；106项JVM、构建和Lint通过。

真实正文 URL 点击已进入应用详情，目标2974886返回服务器404，未将内容不可用误记为路由失败；截图 presentation-live-native-link-fixed。显式“浏览器打开”进入Chrome，截图 presentation-live-browser。设置快捷入口打开本应用的系统默认打开页，新增链接列表准确显示linux.do，截图 presentation-live-supported-links 与 presentation-live-domain-choice；未更改设备域名关联。

自动审批拒绝 adb 外部 VIEW 启动（blocked by policy，未给出更具体原因），因此外部冷/热启动和旋转重建未现场验证。原 MainActivity ActivityScenario 用例有生命周期等待问题，改为共享生产处理器的确定性用例，没有将其当作真实外部唤起的替代证据。

分发 artifacts/kudo-topic-presentation.apk 与构建、真机回读 APK 的 SHA256 一致：6e55dc4cac40ee92076619d8568a906894b86e7ee802cb9b92b43d1daeabb10d。保留原安装签名覆盖安装，未清理数据。

## Consequences

Android 12 及以上对未验证的域名需要用户在系统“默认打开”中启用；应用不能自行发布 linux.do 的 assetlinks.json。深链目标内容仍受服务器权限和网络验证约束。
