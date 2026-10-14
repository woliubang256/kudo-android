# Agent Note: 外部链接由用户选择系统或应用内打开

Status: implemented

## Problem

第三方分享优先匹配 App 的行为需要包名例外，普通网页默认进入 WebView，用户难以控制。用户要求设置外部链接的打开方式，默认交给系统，或选择应用内网页；linux.do 及其子域名不属于外部链接。

## Decision

AppSettings 持久化 ExternalLinkMode（SYSTEM 默认、IN_APP），缺失或未知值回退 SYSTEM；设置的“链接打开”组提供同名 Action Sheet，复用既有选中黄色与关闭交互，说明系统可能使用浏览器或关联应用。没有存储值的既有用户同样默认 SYSTEM。

ExternalAppLinkLauncher 在 SYSTEM 模式发送原始隐式 ACTION_VIEW，不指定包或组件、不自行筛选或强制 chooser；IN_APP 模式返回 false。linux.do 及以 .linux.do 结尾的真实子域名始终返回 false，伪装后缀不是站内。受限夸克 scheme 校验继续有效，启动异常交还网页回退。

KudoApp 原生正文统一入口先检查既有文件下载，再传入当前偏好给启动器；没有外部启动时使用已校验的 HTTP(S) 回退并沿用原生站内路由。子域名不扩展原生帖子解析，进入应用内网页。受限夸克 scheme 在 IN_APP 模式使用其分享网页回退。

WebView 只在主文档、用户手势且非重定向的外部点击、当前模式为 SYSTEM 时调用系统启动器；linux.do 及子域名、IN_APP 点击、脚本与重定向维护网页历史。模式通过 rememberUpdatedState 更新，切换设置不重载网页。受支持的 scheme 在 IN_APP 或系统启动失败时使用 HTTP(S) 分享回退。Manifest 移除夸克包可见性例外，保留显式浏览器功能所需 HTTP(S) 查询。

## Existing note audit

[第三方 App 分流](../../implemented/feature/2026-10-14-external-app-links.md)部分取代：不再自动筛选候选或指定夸克包名，但协议范围与已记录的验证限制仍有效。[网页浏览](../../implemented/feature/2026-10-10-in-app-web-links.md)部分取代外站默认方式，保留站内连续历史、会话和显式浏览器入口。[站内深链](../../implemented/feature/2026-10-09-linuxdo-links.md)保留原生解析与系统入口，仅新增外部域名边界，不给任意子域名扩展原生帖子解析。[下载管理](../../implemented/feature/2026-10-14-native-download-manager.md)保留下载优先。没有其他同主题 proposed/rejected；无完全取代或需要归档的笔记。

## Alternatives considered

- 强制默认浏览器能保证总是浏览网页，但绕开系统 App Links，无法提供用户期望的 Telegram 类链接分发；系统模式使用隐式 VIEW。
- 保留夸克包名兜底能保证当前手机选择夸克，但违反系统模式交给系统的目标；不再特殊指定包名，是否命中由系统决定。
- 所有 WebView 导航都遵循外部设置能覆盖自动跳转，但会破坏连续历史并让脚本随时唤端；只分流主动的主文档点击。

## Testing

按用户要求先完成各功能单元，再集中验证设置、原生分流及网页分流；本设置单元尚未运行功能测试。提交前笔记门禁与 diff 检查通过。

## Consequences

用户有可保存的选择入口，原生及 WebView 主动点击均遵循偏好。系统模式可能直接进入默认浏览器，夸克 HTTP(S) 不保证匹配夸克；厂商面板与关联设置不同。应用内网页与系统浏览器登录态独立。系统没有处理程序或拒绝启动时使用应用内网页回退。显式“在浏览器打开”继续强制浏览器，不随新偏好变化。
