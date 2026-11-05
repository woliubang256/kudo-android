# Agent Note: 小程序剪贴板授权记忆

Status: implemented

## Problem

用户要求原生剪贴板面板提供“不允许／仅本次允许／允许且不再询问”。长期授权需要本地保存，并按访客及各登录账号、小程序 ID、读取或写入分别隔离。

## Decision

继续经过官网 manifest 和业务检查后的兼容接口，由原生执行文字剪贴板操作。不允许仅拒绝当前请求，单次允许不写授权；长期允许仅在当前请求有效且操作成功后保存当前账号、精确应用 ID 和操作类型。后续同范围请求可直接执行，其他应用、账号及反向读写仍需询问。账号尚在验证期间不读取或保存长期授权，不执行新请求；换号销毁旧网页与未完成弹层。

保留官网来源、主文档 URL、前台、请求一次性、4 KiB UTF-8、取消、超时与并发限制。长期授权不绕过这些校验，不把非精确应用路径或未知权限保存为通用允许。三个选项纵向排列，保留触控区，使用网页容器已有普通材质，不采样 WebView。

偏好使用专用 MiniAppPreferences，本地标题栏开关按账号保存为一个布尔值，默认显示；剪贴板授权使用独立的账号／应用／读写键，默认未授权。读到错误类型时回到安全默认值，不变更其他账号偏好。

## Existing note audit

[单次原生确认](../../implemented/feature/2026-11-05-mini-app-native-clipboard-consent.md)部分重叠：本篇取代每次都需确认的策略，保留其官网适配、来源隔离、请求取消和不接管其他权限的约束；旧篇互链，不整体归档。[可切换标题栏](../../implemented/feature/2026-11-05-mini-app-optional-title-bar.md)仅共用本地账号分区存储，其显示及全屏规则仍由原篇维护。检索未发现其他同主题 proposed/rejected 笔记；其他应用广场笔记不涉及此授权范围。

## Alternatives considered

- 永远只做单次允许能避免维护授权记录，但不满足用户明确的“不再询问”要求；增加精确分区的长期允许。
- 同一账号下所有应用共用授权能最少打扰，但对一个应用的写入选择会扩展为其他应用读取；使用账号、应用和读写三维隔离。
- 在点击按钮时立即保存授权能简化回调，但已取消、离开页面或超过文字上限的旧请求也可能留下授权；仅有效请求成功操作后保存。

## Testing

- 旧实现上两项复现均失败：标题栏关闭重进后恢复默认，剪贴板缺少“不允许”及长期允许选项，见 `artifacts/mini-app-preferences-repro-device.txt`。
- 首轮 47 项真机用例中 43 项通过、4 项失败，见 `artifacts/mini-app-preferences-device.txt`。发现面板退出动画期间 `showing` 仍为真，紧接着的新请求被误拒绝；改为与原有面板一致地检查 `visibility.targetState`，只在面板目标状态仍打开时拒绝并发。
- 修复后小程序界面 27 项、原生请求 2 项、存储 3 项共 32 项复测，30 项通过、2 项新测试仍因界面未同步而失败，见 `artifacts/mini-app-preferences-fixed-device.txt`。测试等待会话重新组合及原生应用切换完成后，最后两项定向复测通过，见 `artifacts/mini-app-preferences-focused-device.txt`。普通网页 14 项和 WebView 材质 1 项在首轮已通过；三轮累计 47 个不同真机用例获得通过结果，不将任一失败批次写作全绿。
- 用例覆盖长期读写分别授权、关闭重进及重开存储恢复、访客和两账号隔离、另一应用和反向操作继续询问、拒绝与单次允许不落盘、会话检查和换号取消请求、失效／取消／完成／超限请求不保存授权。系统剪贴板在涉及读写的测试前后保存恢复，不输出真实内容。
- 浅深色与三种材质共六种组合、130% 字号下三个选项均可见，触控高度至少 48dp；已目视检查 `artifacts/mini-app-three-clipboard-light.png`、`artifacts/mini-app-three-clipboard-dark.png`，中文清晰。其他声音、全屏和未知官网弹窗的原有用例继续通过，WebView 原生面板仍用普通材质。
- Debug／AndroidTest 构建及 MiniAppInfoTest 7 项 JVM 用例通过，Lint 为 0 errors／70 warnings／1 hint（本轮存储新增两条 UseKtx 建议），见 `artifacts/mini-app-preferences-build.txt`。修复后的运行时及最终测试包构建分别见 `artifacts/mini-app-preferences-fixed-build.txt`、`artifacts/mini-app-preferences-focused-build.txt`。
- 设备 RMX2202／Android 13，显式版本 0.3.1-dev／3002；开发版签名与覆盖前一致，仅 install -r、install -r -t 和手动 am instrument，首次安装时间保持 2026-10-23 14:55:34。真实登录后的线上 SDK 业务未在本轮重新验收，离线真实 WebView 用例不替代线上业务。

## Consequences

同一授权范围的后续请求不再打扰，其他账号、应用和操作方向继续独立询问。长期授权只存本机，系统清除应用数据后丢失。本轮不增加授权管理页；不允许按钮只拒绝本次，不会撤销已保存的其他授权。读取剪贴板仍受 Android 前台及系统限制，其他系统和 WebView 版本需后续验收。
