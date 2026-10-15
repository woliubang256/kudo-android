# Agent Note: 外部链接由用户选择系统或应用内打开

Status: implemented

## Problem

第三方分享优先匹配 App 的行为需要包名例外，普通网页默认进入 WebView，用户难以控制。用户要求设置外部链接的打开方式，默认交给系统，或选择应用内网页；linux.do 及其子域名不属于外部链接。

## Decision

AppSettings 持久化 ExternalLinkMode（SYSTEM 默认、IN_APP），缺失或未知值回退 SYSTEM；设置的“链接打开”组提供同名 Action Sheet，复用既有选中黄色与关闭交互，说明系统可能使用浏览器或关联应用。没有存储值的既有用户同样默认 SYSTEM。

ExternalAppLinkLauncher 在 SYSTEM 模式对普通外站发送原始隐式 ACTION_VIEW，不指定包或组件、不自行筛选或强制 chooser；准确的 HTTPS 夸克分享链接按[夸克包名例外](../bug-fix/2026-10-16-quark-share-package-routing.md)指定 `com.quark.browser`；IN_APP 模式返回 false。linux.do 及以 .linux.do 结尾的真实子域名始终返回 false，伪装后缀不是站内。受限夸克 scheme 校验继续有效，启动异常交还网页回退。

KudoApp 原生正文统一入口先检查既有文件下载，再传入当前偏好给启动器；没有外部启动时使用已校验的 HTTP(S) 回退并沿用原生站内路由。子域名不扩展原生帖子解析，进入应用内网页。受限夸克 scheme 在 IN_APP 模式使用其分享网页回退。

WebView 只在主文档、用户手势且非重定向的外部点击、当前模式为 SYSTEM 时调用系统启动器；linux.do 及子域名、IN_APP 点击、脚本与重定向维护网页历史。模式通过 rememberUpdatedState 更新，切换设置不重载网页。受支持的 scheme 在 IN_APP 或系统启动失败时使用 HTTP(S) 分享回退。Manifest 保留显式浏览器功能所需 HTTP(S) 查询，并为准确夸克分享目标声明包可见性。

## Existing note audit

[第三方 App 分流](../../implemented/feature/2026-10-14-external-app-links.md)部分取代：不再自动筛选候选或指定夸克包名，但协议范围与已记录的验证限制仍有效。[网页浏览](../../implemented/feature/2026-10-10-in-app-web-links.md)部分取代外站默认方式，保留站内连续历史、会话和显式浏览器入口。[站内深链](../../implemented/feature/2026-10-09-linuxdo-links.md)保留原生解析与系统入口，仅新增外部域名边界，不给任意子域名扩展原生帖子解析。[下载管理](../../implemented/feature/2026-10-14-native-download-manager.md)保留下载优先。没有其他同主题 proposed/rejected；无完全取代或需要归档的笔记。

## Alternatives considered

- 强制默认浏览器能保证总是浏览网页，但绕开系统 App Links，无法提供用户期望的 Telegram 类链接分发；系统模式使用隐式 VIEW。
- 为所有外站保留夸克包名兜底能保证当前手机选择夸克，但违反系统模式交给系统的目标；只对准确夸克分享路径保留窄范围例外，其他链接仍由系统决定。
- 所有 WebView 导航都遵循外部设置能覆盖自动跳转，但会破坏连续历史并让脚本随时唤端；只分流主动的主文档点击。

## Testing

16 项 JVM 专项测试通过：策略 5、Linux.do 解析 7、原生下载 4（`artifacts/external-link-mode-unit.txt`）。Debug / AndroidTest 构建与 Lint 成功，0 errors、45 既有 warnings、1 hint（`artifacts/external-link-mode-build.txt`）。

RMX2202 / Android 13 本轮共 34 项不同真机用例验证通过：启动器 5、设置保存 2、WebView 13、深链 4、原生下载 7、下载通知入口 1、设置面板关联回归 2。覆盖普通外站的隐式系统 Intent、准确夸克分享的目标包、应用内不唤端、域名边界、缺失/未知配置回退、重新创建存储保持选择、真实触摸、切换不重载、scheme 网页回退、脚本/重定向/子框架隔离、网页历史与 Cookie、下载进度/完成通知和应用内完成事件。

首轮 34 项中 32 项通过，2 项新夹具失败（`artifacts/external-link-mode-device.txt`）：LazyColumn 屏幕外设置项尚未生成，测试改为从列表 performScrollToNode；重载计数原本混入异步子资源，新增仅主文档的计数，仍断言模式切换不重载且不重建 WebView。没有修改产品代码来规避失败。修正后设置的 2 项和模式切换 1 项全部通过（`artifacts/external-link-mode-fixed-device.txt`）；重复项不另计用例数，未重复无关联已通过的测试。

构建显式 versionCode=2005、versionName=0.2.3，与已安装版本相同。安装前回读包核对签名 SHA256 一致（95f95aff266413e4c3fb431bf7ae96552d0e5e601b986e4a53d2a116b6623e4f）；应用 adb install -r、测试包 -r -t，再 am instrument，保留用户数据。设置保存测试使用隔离 SharedPreferences，不修改真实偏好。`artifacts/external-link-mode-settings.png` 已实际查看，确认贴边选项面板与黄色选中态。产物 `artifacts/kudo-external-link-mode.apk`。

系统启动器通过捕获生产 Intent 验证，不实际唤起第三方 App；此前外部夸克启动受自动审批拒绝的限制仍见旧笔记，没有改用测试绕过，也没有将 Intent 验证记为夸克分享页面端到端验收。提交前笔记门禁与 diff 检查通过。

## Consequences

用户有可保存的选择入口，原生及 WebView 主动点击均遵循偏好。系统模式普通外站可能直接进入默认浏览器；准确的 HTTPS 夸克分享会优先交给 `com.quark.browser`，厂商面板与关联设置仍可能影响其他链接。应用内网页与系统浏览器登录态独立。系统没有处理程序或拒绝启动时使用应用内网页回退。显式“在浏览器打开”继续强制浏览器，不随新偏好变化。
