# Agent Note: Connect 普通链接复用原生等级页面

Status: implemented

## Problem

应用已有等级要求页，但原生内容里的 connect.linux.do 链接仍进入网页。用户要求普通入口复用原生页，减少不必要的 WebView 使用。

## Decision

仅在应用普通链接入口精确匹配 connect.linux.do 的默认端口 HTTP(S) 地址，所有路径、查询及片段进入现有 trust 路由。沿用该页面的登录提示和账号数据，不新增页面或扩大 API 同源范围。原生正文分流及普通网页入口共享此规则，等级页面主动打开官网提供明确绕过选项，继续用于认证及错误恢复。已经打开的 WebView 内导航保持网页连续历史，系统域名关联与外部 Intent 解析范围不扩大。

## Existing note audit

检索 connect.linux.do、等级要求及链接分流。[应用内网页](../../implemented/feature/2026-10-10-in-app-web-links.md)部分取代：Connect 普通入口新增原生目标，保留网页内连续导航。[官网会话](../../implemented/feature/2026-10-07-account-and-composer.md)提供既有等级页和官网认证回退。[站内深链](../../implemented/feature/2026-10-09-linuxdo-links.md)的系统入口不变。[外部打开方式](../../implemented/feature/2026-10-14-external-link-opening-mode.md)的站内域名边界及[网页普通材质](../../implemented/bug-fix/2026-10-29-webview-ordinary-material.md)决定不变。没有完全吸收项或同主题待实施、被否提案。

## Alternatives considered

- 所有包含 connect.linux.do 的字符串直接匹配易于接入，但会误收相似域名、带凭据和非默认端口地址；使用标准 URL 解析后的精确主机及端口判断。
- 同时拦截 WebView 内导航能覆盖更多入口，但会破坏已有网页历史、认证及主动官网恢复，因此只分流原生普通入口。
- 只改正文回调影响最小，但通知等普通网页入口仍可能遗漏；统一导航 helper 接入，并为等级页显式官网入口保留绕过。

## Consequences

- 普通 Connect 链接打开等级页，重复点击不叠加相同页，返回恢复来源。
- 大小写主机、HTTP(S)、默认端口、路径、查询和片段正常；相似域名、其他子站、凭据及非默认端口不进入等级页。
- 普通网页入口也分流，显式官网绕过仍打开 WebView；未登录沿用原有提示。
- 帖子、用户、分类、标签、其他网页及 WebView 内导航相关回归保持通过。

所有 Connect 普通路径都落到当前账号的等级概览，不展示官网特定子路径；需要官网功能时使用保留的显式入口。真实账号的 Connect 认证及数据权限不能由离线路由夹具证明。

## Testing

先补解析和导航复现：旧实现 LinuxDoLinkTest 新增一项失败，两个 DeepLinkUiTest 新增用例也因仍进入网页而失败，日志 artifacts/connect-native-repro-build.txt 与 artifacts/connect-native-repro-device.txt。修复后 17 项定向 JVM 测试全部通过：LinuxDoLinkTest 8、ConnectDataTest 4、ExternalAppLinkPolicyTest 5。

真机 7 项 DeepLinkUiTest、2 项正文链接、6 项外部唤起策略、Connect 独立验证回退 1 项及 14 项 WebScreenUiTest 通过，证据 artifacts/scroll-connect-final-device.txt。普通正文和网页入口均进入 trust，重复进入保持单顶、返回保留来源，显式 nativeConnect=false 仍进入网页；系统入口解析范围不扩大。与回顶任务合并共 44 个不同 UI 用例通过，主页回顶的一项夹具定向补测记录见[回顶方向笔记](2026-10-29-scroll-top-direction.md)。

Debug、测试 APK、lintDebug 构建通过，记录 artifacts/connect-native-fixed-build.txt，Lint 为 0 错误、58 条既有警告、1 条提示。设备 RMX2202 / Android 13 保留原 3002 / 0.3.1-dev 版本和签名，通过 install -r 覆盖；没有卸载、清数据或操作真实账号。路由验证使用离线导航夹具，未将其宣称为真实账号的等级数据与认证端到端验证。
