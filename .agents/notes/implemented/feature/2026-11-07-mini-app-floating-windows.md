# Agent Note: 小程序收起为应用内浮窗

Status: implemented

## Problem

用户要求像微信一样把运行中的小程序缩到右侧竖向三点，点击预览并恢复；小程序音乐的关闭入口不能销毁小程序浮窗。

## Decision

保留[独立任务](../../implemented/feature/2026-11-07-mini-app-independent-tasks.md)中的原 Activity 与 WebView，收起时只退到后台并恢复来源任务。缩略图通过当前窗口 PixelCopy 取得，限制尺寸，不建立第二个 WebView。浮窗条目使用账号、应用 ID 和原任务 ID；恢复优先前置原任务，系统回收后再用保留的入口重建。明确关闭才移除任务和资源，账号变化移除旧账号条目。

右侧三点位于酷DO窗口内，支持上下拖动；点击后在附近向左横向展开铺满快照的卡片，关闭入口在左上、应用图标在右下，点击卡片恢复。展示与材质由[锚定浮层方案](../../proposed/feature/2026-11-08-mini-app-anchored-floating-panels.md)接管；有界 Popup 使用 Activity 窗口 PixelCopy 作为独立背景源，不将 WebView 接入 Compose 玻璃录制层。不申请系统悬浮窗权限。

条目与任务恢复、运行页收起和预览截取、全局入口与原生预览分别实现。[音乐浮层](2026-11-07-mini-app-music-floating-control.md)复用同一保留任务，但单独维护显示状态。没有可见浮层或 Activity 不在前台时不采样背景。

## Existing note audit

检索任务、浮窗、WebView 生命周期及背景采样，除独立任务外无同主题记录。[沉浸视图](../../implemented/feature/2026-11-04-mini-app-immersive-runtime.md)保留官网运行时与安全区域，[网页普通材质](../../implemented/bug-fix/2026-10-29-webview-ordinary-material.md)继续约束 WebView 场景，[原生弹窗](../../implemented/feature/2026-11-07-mini-app-native-operation-dialogs.md)仍把操作交回原网页。无完全取代或需要归档的笔记。

## Alternatives considered

- 关闭网页后保存 URL 最节省内存，但恢复会重新启动并丢失游戏或播放状态；保留实际任务，回收时才降级重建。
- 创建系统悬浮窗可覆盖其他应用，但用户确认的方案是微信式应用内入口；使用同窗口原生覆盖层。
- 用第二个 WebView 显示实时预览能保持动态画面，但会重复加载和执行小程序；仅截取原运行页。

## Testing

编译与真机预览测试通过，浅深色三种材质分别保存截图。像素回归确保 48dp 触控区外侧不绘制方形 ripple，预览关闭不触发恢复。真实音乐应用收起后，dumpsys 确认论坛和 Music Player 分属任务 3897/3898，后台保留原 WebView；用户确认预览恢复与桌面入口正常。多个应用并行与强制冷启动等未专项实测，不把代码检查记作这些用例已通过。

## Consequences

后台任务仍可能被 Android 回收；恢复时按原地址重建。预览是收起时快照，不能代表后台实时画面。预览图只在当前进程保留，账号退出时清理。
