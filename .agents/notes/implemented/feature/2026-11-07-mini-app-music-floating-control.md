# Agent Note: 白名单小程序音乐控制

Status: implemented

## Problem

SDK 0.1.0 的 audio.play/stop 不提供歌曲、暂停或音乐角色，不能把游戏背景音当作音乐。用户确认暂时仅 Music Player（01a1a755-8210-7a3f-877b-6b09df8dce08）属于音乐应用，需要收起后继续播放并控制歌曲。

## Decision

真机只读检查确认该应用使用 audio.webAudio，并通过 #track-title、#track-artist、#toggle、#previous、#next 提供现成播放器状态和操作。白名单适配器只读取这些公开界面状态，控制调用原按钮，不创建音源、不改 SDK 权限。浏览器 Media Session 在当前 WebView 不可用时仍可工作。

受限网页适配器与状态控制、左侧原生浮层、后台播放服务与生命周期分别实现。脚本可进入不透明 sandbox，但不向它暴露原生接口；通过官方 frame-host 校验 iframe source、runtime 精确地址，再由官方顶层页面报告；原生仅接收 https://linux.do 主框架、当前 URL、当前账号和白名单应用的状态。页面导航、关闭、账号变化注销控制器。其他应用不注入控制，也不显示音乐入口。

官网包挂载在启动后使用 document.open/write 替换整个文档，会删除早期 window 事件监听。运行时适配器使用仍能跨该操作存活的 500ms 定时同步，幂等注册同一控制函数并检查现有播放器 DOM；仅状态变化才上报，不更改文档挂载和 CSP。命令等待原播放器反馈，4 秒未反馈时显示实际错误，不能把静音或乐观改图标当作暂停成功。

左侧音乐图标点击显示歌名、歌手和原播放器支持的控制。音乐 × 请求暂停并隐藏音乐入口，保留[小程序浮窗](../../implemented/feature/2026-11-07-mini-app-floating-windows.md)。重新在小程序内播放会恢复入口。后台播放使用 mediaPlayback 前台服务维持进程和显示控制通知；音源仍全部留在原 WebView。白名单播放器可继续运行 WebView，普通游戏保持暂停生命周期。服务只在实际播放时启动，暂停、关闭、账号变化时停止；不添加系统权限询问界面。

## Existing note audit

检索音乐、白名单、后台播放：浮窗保留任务，独立任务隔离账号；[声音确认](../../implemented/feature/2026-11-06-mini-app-native-audio-consent.md)与[原生弹窗](../../implemented/feature/2026-11-07-mini-app-native-operation-dialogs.md)继续保留网页权限流程。均为部分关联，不取代既有决定。

## Alternatives considered

- 直接操作所有 HTMLAudioElement 简单通用，但真实应用使用 Web Audio，且 SDK 音频可能脱离 DOM；只适配用户确认的播放器。
- 捕获 AudioContext 并 suspend/resume 可控制声音，但不能同步应用的歌曲、网络缓存和播放状态；调用原播放器按钮。
- 统一原生播放更易维护 Android 媒体会话，但需要重写流媒体协议和权限业务；保留官方 H5 音源，仅提供控制层。

## Testing

MiniAppMusicStateTest 与 MiniAppTaskRequestTest 共 4 项 JVM 测试通过。真机 MiniAppMusicFloatingUiTest 与现有 MiniAppAudioPermissionUiTest 共 11 项通过（artifacts/mini-app-music-fixed-device.txt），覆盖不透明 iframe、异步整页重写、伪造消息、暂停/继续/切歌、音乐关闭保留右侧条目、六种主题材质以及原声音授权逻辑。

重写文档复现先失败于暂停操作（artifacts/mini-app-music-remount-repro-device.txt），浮窗像素断言先发现触控区的方形 ripple，再分别修复并通过。此前的简单 fixture 通过不能替代真实挂载行为。DeepLinkUiTest 的 10 项真机回归与最终 Lint 通过。

RMX2202 / Android 13 的真实 Music Player 验证：收起至论坛任务 3897 时播放器仍在任务 3898，runtime 执行上下文 16 与 nonce 保持不变；《过期彩券》的进度在后台继续前进。原生控制暂停后原按钮移除 is-playing 且服务停止；继续播放恢复前台服务；下一首显示《沦陷》。点原生 × 后原按钮为暂停、左侧音乐入口消失，右侧小程序入口与真实预览仍在（artifacts/mini-app-real-music-panel.png、mini-app-real-preview.png、mini-music-adapter-inspection.json）。桌面启动器行为单独核验。

## Consequences

白名单应用更新 DOM 会使适配失效，应隐藏不可用操作，不根据文件名猜歌名。系统仍可能回收进程，不能承诺跨进程恢复播放位置。厂商后台策略和 WebView 版本需要真机验证。
