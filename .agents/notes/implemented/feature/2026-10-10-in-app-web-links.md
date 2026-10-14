# Agent Note: 应用内网页与原生站内链接分流

Status: implemented

## Problem

正文通过统一 openLink 分发，但非原生链接调用外部浏览器。分类链接尚未识别，其他站内网页被系统深链解析器回退到首页。用户要求正文网页留在应用 WebView 内，并共享应用登录 Cookie；帖子、个人主页和分类链接优先进入原生页面。

## Decision

[外部链接打开方式](2026-10-14-external-link-opening-mode.md)取代原生外站默认进入网页及自动 App 分流的决定：默认交给系统，用户可改为应用内；站内及子域名仍留在应用内，下述会话、连续历史和显式浏览器入口保持。

原生第三方分享链接的 App 优先分流，以及网页内主文档、带用户手势、非重定向的第三方 App 唤起，由[第三方 App 链接](2026-10-14-external-app-links.md)部分接管；本篇保留普通网页回退、站内网页连续历史与显式浏览器入口。

应用内点击先解析受支持的 Linux.do 原生目标：帖子保留楼层，用户子路径进入个人主页，分类支持 /c/id、/c/slug/id 以及带层级 slug 的分类路径。其他站内页面和外站 HTTP(S) 网页进入应用内 WebView；保留显式外部浏览器入口。来自系统的未知 Linux.do 深链保留原有首页回退约定，分类为本次新增原生目标。用户批准时明确修正：进入网页后的点击和重定向都留在 WebView，不再次切回原生页，以维持连续浏览体验。

WebView 使用 CookieManager 默认存储，与 LoginScreen 和 AndroidSessionCookies 相同，不复制登录 Cookie 到 URL、手工请求头或外站。Cookie 发送遵循 WebView 域、路径和 Secure 规则；应用内登录态可共用，系统 Chrome 的存储不属于本应用。页面具备标题、加载进度、刷新、错误重试和返回，网页历史优先返回，无历史返回原生来源；释放时停止加载并销毁实例。沿用关闭文件/内容访问和第三方 Cookie 的设置，不引入 JS 原生桥。网页阅读允许 HTTP，Manifest 放开明文流量；社区 API 和图片请求仍由各自既有同源 HTTPS 校验约束，不手工向明文网页附加社区会话。

标题栏仅显示页面标题，不另占一行显示域名，外部浏览器入口使用Language网格地球矢量。首屏、导航和刷新等待期间覆盖主题底色并居中显示CommunityLoadingIndicator，使用L站图标的黑/白/黄三色，弧长3:4:3（108°/144°/108°），淡轮廓帮助黑白段区别于背景。onPageCommitVisible或onPageFinished后的视觉状态确认后收起；下载进度100本身不代表内容可见。视觉回调按导航代次检查，错误或下载交接停止loading，失败保留原有重试入口，等待期间禁用网页回顶入口。

网页等待期间的原生绘制与 Compose 共用主题：KudoTheme 同步 Activity 窗口背景与根 Surface 的 background 色，状态栏图标继续按当前主题及个人主页覆盖设置决定。WebView 在加载地址前设置 surface 底色，AndroidView 更新时同步新主题；原生 View 及 Compose 内容区都裁切到网页视口，防止其初始绘制影响上方标题栏和状态栏。颜色变化只更新已有实例，不重载网页或丢弃历史。此约束也覆盖显式选择深色、浅色及跟随系统。

## Existing note audit

WebView 原生布局使用[有界视口](../bug-fix/2026-10-10-webview-viewport.md)，保证 vh 与 iframe 高度随可用空间正常计算。

[站内深链](../../implemented/feature/2026-10-09-linuxdo-links.md)部分重叠：保留外部 Intent 消费、帖子/用户格式和系统未知地址首页回退，应用内未知网页采用新回退。[原生客户端](../../implemented/architecture/2026-10-07-native-community-client.md)部分重叠：保留原生正文和受限网络契约，扩展 WebView 用途为网页阅读。[官网会话](../../implemented/feature/2026-10-07-account-and-composer.md)保留登录与 current_user 验证，复用同一 Cookie 存储。[酷安表单](../../implemented/feature/2026-10-10-coolapk-forms.md)只提供顶部栏/控件样式，不改变编辑流程。无完全吸收或过时提案。

## Alternatives considered

- 继续用系统浏览器能获得完整浏览能力和独立浏览器已有登录态，但正文会离开应用，不能共享本应用 CookieManager；仅保留用户显式选择的外部打开动作。
- 全部站内页面直接原生路由能保持交互统一，但当前只实现帖子、用户和分类，指南等页面仍需要网页；只匹配已支持目标，其余交给 WebView。
- 手工把社区 Cookie 注入每次网页请求能直观控制首个请求，但容易遗漏重定向与子资源，也会破坏按域隔离；复用平台 CookieManager。
- 网页内点击站内链接再次原生分流，能直接复用客户端页面；用户明确指出连续浏览会割裂，因此只在原生内容入口分流，WebView 全程维护网页历史。
- 仅保留 Compose 加载遮罩可复用现有主题渲染，但遮罩只覆盖网页内容区，无法约束原生 WebView 的白底及越界绘制；原生窗口和 WebView 也需要同步底色并限定绘制范围。
- 仅增加 XML 夜间窗口资源可在系统深色下提供启动底色，但运行中显式切换 App 外观仍需要跟随 Compose 主题；窗口在运行时同步颜色，不借此改变网页 CSS 或站点自身的深色设置。

## Testing

RMX2202 / Android 13 的延迟主文档用例在修复前捕捉到深色内容区上方整块白色标题栏和状态栏，截图 artifacts/browser-dark-repro-initial.png，状态栏背景期望 #1C1D20、实际 #FFFFFF，日志 artifacts/browser-dark-repro-device.txt。真实社区指南的一次连续截图未捕捉同一顶部闪白，因此不把真实页面的首次检查作为复现证据。用例使用与 MainActivity 一致的 edge-to-edge 宿主，检查截图中状态栏和标题栏的实际像素以及原生窗口后备背景，覆盖初始等待和刷新等待。

修复后该用例通过，已检查 artifacts/browser-dark-fix-initial.png 和 artifacts/browser-dark-fix-refresh.png，标题及操作图标完整显示，顶部为 #1C1D20。WebScreenUiTest 全部9项在同机通过（artifacts/browser-dark-regression-device.txt），覆盖首屏/刷新、网页历史、Cookie、恢复与释放、错误重试、浅色切深色不重载及 CSS 视口。实际 App 外观为 SYSTEM，系统深色下进入社区指南的5帧顶部采样均为 #1C1D20，已检查 artifacts/browser-dark-live-final-2.png；网站当时显示 Cloudflare 验证页，本轮不把这张截图作为社区指南内容加载成功的证据。Debug 验签后使用 install -r 覆盖，数据保留，测试结束恢复系统夜间模式 auto。Release 以 0.2.0 / 2001 构建通过，产物为 artifacts/release/0.2.0/browser-dark-loading/kodo_0.2.0_release_arm64-v8a.apk。

三色加载样式已统一共用列表、网页、楼层向前补载、投票等待与下拉刷新；保留18/24/26dp现有尺寸，下拉刷新仍用Material3的40dp容器、16dp图形和原有拖动状态，图形随拖动旋转，真正刷新后无限旋转。5项现有真机用例通过（artifacts/tricolor-loading-device-ui.txt），覆盖网页初始等待/刷新、深色大字首页及短分类列表下拉触发、向前补载保持锚点；网页初始等待另在系统深色下复验通过（artifacts/tricolor-loading-dark-ui.txt）。已查看tricolor-loading-light.png、tricolor-loading-dark.png及真实下拉中的tricolor-loading-pull.png，黑白段保留固定图标色，通过淡轮廓提高背景区分度。系统夜间模式恢复为测试前的auto，开发版验签后install -r覆盖，未卸载或清数据。此轮仅改变样式，复用现有用例，不新增镜像绘制实现的测试或运行无关全量单测。

网页首屏loading改动先用可控制的主文档响应闸门复现初始加载与刷新两项缺少web-loading失败（artifacts/web-loading-repro-feed-fixed-ui.txt）。同批5项返回列表用例通过。修复后7项WebScreenUiTest全部通过（artifacts/ui-polish-final-ui.txt），包含进度先到100仍显示loading、内容可见后收起、失败/刷新与恢复历史。最终重新构建后的初始加载用例也通过（artifacts/ui-polish-touch-final-ui.txt）。UiAutomation取得的ui-polish-web-loading.png和ui-polish-web-loaded.png已检查黄色等待提示及内容切换，但测试窗口截图未完整呈现标题栏，不作为顶部控件视觉证据。

真实指南页ui-polish-web-live-loaded.png已人工查看：顶部只显示页面标题、刷新、网格地球和关闭，域名副标题已移除，内容保持在原WebView。真实站点未刻意制造延迟，等待时机通过离线闸门用例验证。最终构建保持0.1.7/1008并通过签名、ABI和16KB对齐校验，产物和校验值见[回顶验证记录](2026-10-11-quick-scroll-to-top.md)。

分类链接先由失败单测复现错误回首页，日志 artifacts/category-link-repro.txt；修复后精确断言 ID。LinuxDoLinkTest 增加正文路由与网页地址校验，涵盖相对 URL、查询/片段、不支持协议、凭据地址及相似域名。131项 JVM 测试、Debug/测试 APK 构建通过，Lint 为0错误、30条现有警告和1条提示，日志 artifacts/in-app-web-build.txt。

DeepLinkUiTest 覆盖共享生产导航 helper 的帖子、用户、分类和网页目标；RichContentLinkUiTest 覆盖 HTTPS 与 HTTP 正文链接回调。WebScreenUiTest 通过 WebView 网络拦截提供网页夹具，复用生产 WebViewClient 处理跳转、错误和历史；覆盖站内帖子/用户/分类、新窗口、重定向、Cookie 双向共享与跨域隔离、状态恢复、关闭释放、刷新重试和重组不重载。

首轮20项回归有2项夹具失败，日志 artifacts/in-app-web-first-device.txt：脚本模拟点击缺少用户手势，target=_blank 被网页引擎阻止；拦截的500响应未产生真实网络层 HTTP 错误回调。测试改为按 DOM 边界换算真实触摸位置，并在页面加载后向生产客户端传递对应 HTTP 错误回调，未为夹具增加产品延时或放开自动弹窗。聚焦4项中的前三项通过，重试用例另行通过，随后整批20项真机全部通过，日志 artifacts/in-app-web-final-device.txt（35.34秒）。Cookie 夹具只使用 reader-fixture.test 的两个独立名称，结束按名称删除，未读取或打印真实社区 Cookie。

RMX2202 / Android 13 实页验收：设置的社区指南在应用内加载，点击网站标识进入网页首页、再点击话题均留在同一 WebView；原生正文点击 App Store onebox 在应用内打开 apps.apple.com，关闭恢复原生帖子与“写回复”操作栏。已查看 artifacts/web-reader-live-guidelines.png、artifacts/web-reader-live-web-home.png、artifacts/web-reader-live-web-topic.png、artifacts/web-reader-live-external.png。设备当前未登录，真实账号登录态未做端到端验收。

最终 Lint 0错误、30条现有警告和1条提示，日志 artifacts/in-app-web-final-lint.txt；verify-notes 检查33篇通过。artifacts/kudo-in-app-web.apk 与已安装回读文件 SHA256 相同：0ca174cc6ff112f254478e71cdeb5daed8fefcafda6c974296d3e483158f8c52。沿用原有签名覆盖安装，保留设备数据与偏好。

## Consequences

进入网页后连续浏览，不强制切换本应用原生页面；原生正文和显式完整资料/指南入口共享 WebView 能力。HTTP 网页允许明文传输，社区请求依旧要求同源 HTTPS。WebView Bundle 保存历史与当前地址，不保留网页 DOM 或未提交网页表单。下载提示使用外部浏览器；非 HTTP(S) scheme 的主动点击仅按第三方 App 链接笔记中的受限分享规则处理，脚本与重定向不自动唤起其他应用。分类仅 slug 的历史地址回退网页，不猜 ID。网页登录仍受站点验证与 WebView 兼容性约束；真实登录态的端到端验收需要已登录账号，夹具不能替代实际网站权限验证。

窗口后备背景在 App 主题变化时同步，网页绘制被限定在自身视口。网页自身仍可声明浅色背景；此处不强制改写网站 CSS。顶部防闪白的设备验证覆盖 RMX2202 / Android 13，其他厂商和 WebView 版本仍需复核。
