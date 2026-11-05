# Agent Note: 小程序可切换标题栏与全屏避让

Status: implemented

## Problem

悬浮胶囊覆盖了部分游戏右上角的按钮。用户要求默认显示仅含应用图标、名称和胶囊的原生标题栏，让网页从下方开始；更多菜单提供标题栏切换，应用请求全屏时自动隐藏。

## Decision

保留官网嵌入运行时和同一个 WebView，仅切换 WebPageScaffold 的标题栏与悬浮胶囊。默认开启标题栏，普通材质，不增加返回、简介或副标题。公开详情按当前应用请求并与关于共用，名称和图标缺失或失败时保留通用图标及页面标题，不阻塞运行；旧应用信息不能显示到新应用。

更多宫格增加“隐藏标题栏／显示标题栏”，选择通过 MiniAppPreferences 在本地按访客及每个登录用户 ID 分别保存，一个账号下所有小程序共用，初始默认显示；关闭重进、换应用及重开存储恢复该账号的选择。网页请求全屏的 custom view 期间临时隐藏标题栏，退出后恢复手动选择；全屏内切换只修改退出后的偏好。隐藏状态下胶囊继续悬浮，系统返回仍先退出弹层、再退出网页全屏、再处理历史。会话检查期间禁用偏好修改，换账号销毁旧网页和弹层，再读取新账号偏好。

## Existing note audit

[小程序沉浸运行视图](../../implemented/feature/2026-11-04-mini-app-immersive-runtime.md)部分重叠：本篇取代“运行页始终隐藏原生标题栏、胶囊始终覆盖内容”的展示决定，保留其嵌入模式、DOM/CSP 适配、稳定 WebView、无 loader 回退和权限约束；旧篇互链，不整体归档。[小程序网页容器](../../implemented/feature/2026-11-04-mini-app-square.md)、[原生启动进度](../../implemented/feature/2026-11-04-mini-app-native-launch-progress.md)及[剪贴板单次确认](../../implemented/feature/2026-11-05-mini-app-native-clipboard-consent.md)部分重叠，继续使用原契约。检索中其余主题不相关，没有同主题的未实施或被否提案。

## Alternatives considered

- 始终隐藏标题栏能提供最大游戏视口，原实现也避免重复官网导航；但第三方游戏不会统一避让原生悬浮胶囊，已出现按钮遮挡。默认用独立标题栏留出空间，并允许主动隐藏。
- 给网页增加右上内边距改动少且能保留大部分高度，但固定定位按钮、Canvas 和嵌套 iframe 不会统一遵守宿主内边距；原生整行避让能保证非全屏内容与胶囊不重叠。
- 按显示方式拆成两个网页容器能简化各自布局，但会重建 WebView、丢失游戏和历史；沿用同一脚手架及正文组合位置。

## Testing

- 默认标题栏的用例先在旧实现失败，见 `artifacts/title-library-repro-device.txt`；与两处图库默认页一起复现，三个用例均失败。新布局检查胶囊底边不超过网页顶边，保留无返回按钮、官网外壳隐藏与 iframe 铺满约束。
- 初步四项关于、应用切换、真实全屏和标题栏用例中，前三项通过，标题栏缺图标的无障碍名称失败，见 `artifacts/mini-app-title-bar-device.txt`；为通用图标与真实图标统一补充语义，不能把该次运行记为全绿。
- MiniAppWebUiTest 22 项、WebScreenUiTest 14 项和 WebViewMaterialLayoutUiTest 1 项全部通过，见 `artifacts/title-library-regression-device.txt`，覆盖标题栏／网页边界、六种主题材质切换、手动隐藏后全屏退出仍隐藏、全屏中更改退出后的偏好、换应用恢复默认、同一 WebView 与游戏标记及加载次数保持、关于与权限、无 loader 回退、历史和普通网页。该次合并运行共 73 项，图库有一项旧测试未切换到移动后的分类而失败，不把整个合并运行记为全绿。
- 仅整理运行时代码缩进和修正图库测试后，Debug／AndroidTest 再次构建成功，见 `artifacts/title-library-focused-build.txt`；标题栏默认避让、六种组合连续切换及图库修正用例共 3 项复测通过（12.362 秒），见 `artifacts/title-library-focused-device.txt`。
- 官网 MiniAppImmersiveLiveProbeTest 显式启用后 1 项通过（17.327 秒），两款小程序均达到就绪、保留完整嵌入运行时并验证胶囊底边位于网页上方，见 `artifacts/mini-app-title-live-device.txt` 及 `artifacts/mini-app-title-live-evidence.json`。已目视检查真实图标与名称的 `artifacts/mini-app-title-live-0.png`、`artifacts/mini-app-title-live-1.png`，以及浅深色夹具与两行更多宫格，中文清晰。没有开始新游戏、重置进度或主动同意权限。
- MiniAppInfoTest 7 项及 ImageLibraryTest 14 项 JVM 用例通过；构建与 Lint 见 `artifacts/title-library-final-build.txt`，Lint 为 0 errors／68 warnings／1 hint。设备 RMX2202／Android 13，显式构建 0.3.1-dev／3002；与覆盖前开发版签名 SHA-256 一致，仅 install -r、install -r -t 和手动 am instrument，首次安装时间保持 2026-10-23 14:55:34，保留登录与用户数据。
- 账号记忆补充验证：关闭重进、重开存储、访客与两账号往返均恢复自己的选择，换应用保留同账号选择，换账号销毁旧网页；全屏临时隐藏不覆盖保存偏好。用例随本地剪贴板授权一起验证，三轮累计 47 个不同用例获得通过结果，失败批次与定向复测详情见[授权记忆](2026-11-05-mini-app-remembered-clipboard-consent.md)。同设备继续使用 0.3.1-dev／3002 覆盖安装，首次安装时间未变。

## Consequences

默认标题栏保证非全屏游戏与胶囊不重叠，但减少普通状态可用高度。隐藏后仍可能覆盖游戏右上角，属于用户主动选择。应用全屏依赖系统 WebView 的 custom view 回调；其他系统和 WebView 版本需要后续验收。详情请求新增首屏只读网络调用，失败只影响应用标识。标题栏只保存本机账号级显示偏好，不同步网络；剪贴板授权的独立范围由[授权记忆](2026-11-05-mini-app-remembered-clipboard-consent.md)维护。
