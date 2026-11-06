# Agent Note: 小程序音频授权使用原生界面

Status: implemented

## Problem

小程序启动时的播放音频授权仍显示官网 dialog，与其他原生面板不一致。用户要求保留现有 Web 静音、开启声音和运行逻辑，只替换确认界面，本次不发布版本。

## Decision

仅识别官网顶层正式小程序中结构、操作和选项均匹配的音频会话授权，显示普通材质的原生底部弹窗，保留“静音进入／开启声音”。原生只将本次用户选择送回当前官网 dialog 的原按钮；不实现播放器、静音状态或长期授权，不修改 mediaPlaybackRequiresUserGesture。

请求绑定完整页面地址、文档、应用和前台会话。原生按钮只消费一次，取消、切页、切账号、后台和网页撤销请求都会清理待确认界面，不能让迟到的选择作用于后续小程序。返回和关闭沿用官网 cancel 事件；官网仍决定取消后的会话结果。

音频使用独立的官方来源消息对象 KudoMiniAppAudio，不覆盖剪贴板的 onmessage。只接受主 frame 的精确小程序路径与当前完整 URL，消息长度上限 2 KiB。请求确认后不设置额外倒计时，沿用官网音频会话 timeoutMs=null；1.5 秒交接看门狗只用于原生未接管或选项没有到达网页按钮时恢复网页界面。

原生选择交接时，临时打开且透明显示原 dialog，测量已识别的所选原按钮，验证命中后仅向拥有该请求的 WebView 转交本次明确选择的输入。原按钮继续执行官网已有事件处理、静音配置和音频初始化；接收原网页可信点击后移除适配并关闭原生界面。该通道不接收通用坐标点击消息，不注入播放器，不修改 WebView 播放手势设置。失配或输入未完成时恢复原 dialog 的透明度和网页交互，不能假装回调成功。

## Existing note audit

[剪贴板原生确认](../../implemented/feature/2026-11-05-mini-app-native-clipboard-consent.md)部分重叠：原篇保留剪贴板协议与取消规则，本篇部分取代音频始终保留官网 UI 的限制；其“JavaScript 点击不能提供播放手势”的理由仍成立。[原生启动进度](../../implemented/feature/2026-11-04-mini-app-native-launch-progress.md)与[沉浸运行视图](../../implemented/feature/2026-11-04-mini-app-immersive-runtime.md)部分重叠：只为已识别的音频请求让位于原生界面，其他网页权限和稳定 WebView 保持。[网页容器](../../implemented/feature/2026-11-04-mini-app-square.md)保留普通材质及来源边界。未发现同主题 proposed 或 rejected 笔记。

## Alternatives considered

- 保留官网 dialog 能自然保留按钮的浏览器手势，但不满足替换原生界面的要求；作为未知结构和失败时的回退保留。
- 仅用 JavaScript 点击原按钮实现最少，且原回调完全保留；若 WebView 拒绝由该路径发起的播放，需将本次原生选择转为对应按钮输入，不能伪造播放成功。
- 关闭 WebView 的播放手势限制能直接消除原生回传后的自动播放限制，但会影响当前页面其他内容；本次只更换 UI，不更改该策略。

## Testing

- 原生界面缺失用例在旧运行时代码上先失败，记录为 `artifacts/mini-app-audio-repro-device.txt`。以下载的官网 loader 核对 dialog、翻译键、prepareAudio、cancel 和四个静音 WAV 初始化的顺序；测试夹具保留原事件、异步播放与静音流程，不把只收到选择当作完成。
- RMX2202 / Android 13 真机，6 项音频用例最终全部通过（14.022 秒），见 `artifacts/mini-app-audio-final-device.txt`。验证静音与开启声音的原回调均完成、浏览器可信点击与激活、同一 WebView、原手势限制、浅深色 × 三种材质及 130% 字体、英文翻译、未知权限回退、返回/后台/导航/切账号取消、网页移除请求、同源子 frame 和第三方来源，以及吞掉输入后恢复原网页并可正常完成。
- 相关 56 项真机回归首轮通过 54 项，见 `artifacts/mini-app-audio-regression-device.txt`。既有模拟浏览器剪贴板用例初始确认超时，单独复跑通过，见 `artifacts/mini-app-audio-recheck-device.txt`；新增来源用例在跨站导航中 evaluateJavascript 回调丢失，夹具改为等待 onPageFinished 后，包含它的 6 项音频用例通过。本轮未改剪贴板运行时代码。
- MiniAppActionsTest、MiniAppInfoTest、MiniAppLaunchStateTest 共 20 项 JVM 测试通过；Debug/AndroidTest 构建、JavaScript 语法和 UTF-8 乱码检查通过，Lint 保持 0 errors / 71 warnings / 1 hint，无新增告警，见 `artifacts/mini-app-audio-final-build.txt`、`artifacts/mini-app-audio-verified-build.txt`。`npm run verify-notes` 验证 174 篇笔记通过。
- 真机截图 `artifacts/mini-app-audio-light.png`、`artifacts/mini-app-audio-dark.png` 已检查，中文清晰且普通面板不采样 WebView。构建显式使用 0.3.5-dev / 3006，同签名覆盖安装，首次安装时间仍为 2026-10-23 14:55:34，未卸载或清数据。未启动模拟器、桌面浏览器或发布版本。

## Consequences

原生与网页界面选择保持相同回调和会话语义，播放、静音和业务状态继续由官网运行时维护。新增适配依赖官网 dialog 的结构、翻译及浏览器输入处理；官网变化、按钮无法命中或不支持安全消息通道时保留网页确认，其他权限不受接管。

本轮成功证据来自物理设备上的官网形状受控 WebView 夹具，未实际授权线上游戏、播放有声内容或覆盖所有 WebView 版本。单机媒体初始化成功不能代表线上每款小程序已完成验收；后续应实测有声音的小程序分别选择“静音进入／开启声音”，并在网页更新后重新核对识别结构。
