# Agent Note: 白名单小程序音乐控制

Status: implemented

## Problem

SDK 0.1.0 的 audio.play/stop 不提供歌曲、暂停或音乐角色，不能把游戏背景音当作音乐。用户确认暂时仅 Music Player（01a1a755-8210-7a3f-877b-6b09df8dce08）属于音乐应用，需要收起后继续播放并控制歌曲。

用户进一步反馈尚未设置小程序浮窗就出现音乐入口。原宿主只判断播放器就绪，没有要求对应浮窗存在；关闭小程序条目也只依赖 Activity 后续销毁清理播放器，不能把浮层消失当作音源停止。

## Decision

真机只读检查确认该应用使用 audio.webAudio，并通过 #track-title、#track-artist、#toggle、#previous、#next 提供现成播放器状态和操作。白名单适配器只读取这些公开界面状态，控制调用原按钮，不创建音源、不改 SDK 权限。浏览器 Media Session 在当前 WebView 不可用时仍可工作。

受限网页适配器与状态控制、左侧原生浮层、后台播放服务与生命周期分别实现。脚本可进入不透明 sandbox，但不向它暴露原生接口；通过官方 frame-host 校验 iframe source、runtime 精确地址，再由官方顶层页面报告；原生仅接收 https://linux.do 主框架、当前 URL、当前账号和白名单应用的状态。页面导航、关闭、账号变化注销控制器。其他应用不注入控制，也不显示音乐入口。

官网包挂载在启动后使用 document.open/write 替换整个文档，会删除早期 window 事件监听。运行时适配器使用仍能跨该操作存活的 500ms 定时同步，幂等注册同一控制函数并检查现有播放器 DOM；仅状态变化才上报，不更改文档挂载和 CSP。命令等待原播放器反馈，4 秒未反馈时显示实际错误，不能把静音或乐观改图标当作暂停成功。

左侧音乐图标点击在附近向右展开音符、歌名、播放/暂停和关闭组成的横条，展示与安全材质采样见[锚定浮层方案](2026-11-08-mini-app-anchored-floating-panels.md)。音乐 × 请求暂停并隐藏音乐入口，保留[小程序浮窗](../../implemented/feature/2026-11-07-mini-app-floating-windows.md)。重新在小程序内播放会恢复入口。后台播放使用 mediaPlayback 前台服务维持进程和显示控制通知；音源仍全部留在原 WebView。白名单播放器可继续运行 WebView，普通游戏保持暂停生命周期。服务只在实际播放时启动，暂停、关闭、账号变化时停止；不添加系统权限询问界面。

音乐入口还必须匹配当前账号、同一音乐小程序的已收起浮窗条目；仅播放、仅存在其他应用浮窗或其他账号条目都不能显示。恢复小程序移除收起条目，只隐藏音乐入口并继续原播放；音乐条 × 仍只暂停并隐藏音乐入口，保留小程序条目。明确关闭小程序浮窗或运行页时，通过同一账号/应用键立即终止对应音乐控制器：向原播放器投递暂停，注销桥与通知服务，再释放任务，迟到状态不能恢复入口。不把普通恢复的条目移除当作关闭，不停止其他小程序的音乐。

## Existing note audit

检索音乐、白名单、后台播放：浮窗保留任务，独立任务隔离账号；[声音确认](../../implemented/feature/2026-11-06-mini-app-native-audio-consent.md)与[原生弹窗](../../implemented/feature/2026-11-07-mini-app-native-operation-dialogs.md)继续保留网页权限流程。均为部分关联，不取代既有决定。

## Alternatives considered

- 直接操作所有 HTMLAudioElement 简单通用，但真实应用使用 Web Audio，且 SDK 音频可能脱离 DOM；只适配用户确认的播放器。
- 捕获 AudioContext 并 suspend/resume 可控制声音，但不能同步应用的歌曲、网络缓存和播放状态；调用原播放器按钮。
- 统一原生播放更易维护 Android 媒体会话，但需要重写流媒体协议和权限业务；保留官方 H5 音源，仅提供控制层。
- 播放器一就绪就显示入口，能在运行页直接控制音乐，但绕过用户主动收起为浮窗的步骤；入口以对应收起条目为前提。关闭时仅等待 Activity 销毁复用现有资源清理，代码最少，但 UI 移除与真正销毁存在时间差；关闭入口显式终止对应音乐会话。

## Testing

2026-11-08 入口与关闭生命周期：两项新增真机复现先失败，分别是未收起即出现音乐入口、关闭小程序浮窗后原按钮未收到暂停，见 artifacts/mini-app-music-entry-repro-device.txt。入口条件单独修复后通过；完成关闭联动后，MiniAppMusicFloatingUiTest 六项通过（artifacts/mini-app-music-entry-lifecycle-device.txt），Debug/AndroidTest 构建与 Lint 通过（artifacts/mini-app-music-entry-lifecycle-build.txt）。

新增用例覆盖尚无收起条目、其他小程序及其他账号不能触发音乐入口，恢复原小程序保留播放且入口隐藏；关闭其他应用不误停音乐，关闭音乐小程序时原网页按钮的暂停回调实际执行，即使正有切歌命令也不能阻止关闭，音乐会话与服务清除。音乐条 × 的原有“暂停并隐藏、保留小程序”回归继续通过。原真机材质六种组合、反复采样、关闭目标、四项横向溢出等用例保留。测试为不透明 iframe 离线播放器，真实线上音乐小程序本轮未重新打开；不把原来的线上验收当作本轮重测。

真机仍为 3006 / 0.3.5-dev，覆盖签名一致，firstInstallTime 保持 2026-10-23 14:55:34；未卸载或清理数据。对应操作边界补入 AGENTS.md，防止再次仅按播放器就绪决定显示。

MiniAppMusicStateTest 与 MiniAppTaskRequestTest 共 4 项 JVM 测试通过。真机 MiniAppMusicFloatingUiTest 与现有 MiniAppAudioPermissionUiTest 共 11 项通过（artifacts/mini-app-music-fixed-device.txt），覆盖不透明 iframe、异步整页重写、伪造消息、暂停/继续/切歌、音乐关闭保留右侧条目、六种主题材质以及原声音授权逻辑。

重写文档复现先失败于暂停操作（artifacts/mini-app-music-remount-repro-device.txt），浮窗像素断言先发现触控区的方形 ripple，再分别修复并通过。此前的简单 fixture 通过不能替代真实挂载行为。DeepLinkUiTest 的 10 项真机回归与最终 Lint 通过。

RMX2202 / Android 13 的真实 Music Player 验证：收起至论坛任务 3897 时播放器仍在任务 3898，runtime 执行上下文 16 与 nonce 保持不变；《过期彩券》的进度在后台继续前进。原生控制暂停后原按钮移除 is-playing 且服务停止；继续播放恢复前台服务；下一首显示《沦陷》。点原生 × 后原按钮为暂停、左侧音乐入口消失，右侧小程序入口与真实预览仍在（artifacts/mini-app-real-music-panel.png、mini-app-real-preview.png、mini-music-adapter-inspection.json）。桌面启动器行为单独核验。

## Consequences

白名单应用更新 DOM 会使适配失效，应隐藏不可用操作，不根据文件名猜歌名。系统仍可能回收进程，不能承诺跨进程恢复播放位置。厂商后台策略和 WebView 版本需要真机验证。
