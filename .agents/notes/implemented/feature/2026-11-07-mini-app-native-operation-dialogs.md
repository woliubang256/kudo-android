# Agent Note: 小程序操作弹窗统一原生呈现

Status: implemented

## Problem

音频会话和剪贴板已经使用原生面板，视频与全屏仍显示官网 dialog。用户明确五类弹窗均需原生显示，确认、取消及执行继续使用官网现有流程，不另行判断手机系统权限，本次仍不发布。

## Decision

既有音频选择交接扩为受限的小程序操作确认，覆盖音频会话、视频播放和进入全屏；只从已匹配官网结构及翻译的 dialog 取得操作类型和必要的视频资源预览。原生选项交回当前 dialog 的原按钮，关闭交回原 cancel 事件，官网继续处理异步执行、超时、业务校验及错误。保持同一 WebView，不增加媒体播放器、系统权限预检或长效媒体授权。

剪贴板继续复用已有单次原生面板、按账号/应用/读写保存的许可及 WebView 兼容桥，不再显示第二个确认。官网执行回调与 Promise 链保持；新适配不改写剪贴板协议。

MiniAppPermissionBridge 和 mini-app-permission.js 统一继承文档/请求/完整 URL/应用/前台会话绑定、单次选择、撤销与网页回退。独立来源消息对象为 KudoMiniAppPermission，消息仅允许 audio/video/fullscreen 三类及至多 200 字的视频资源预览，总长度上限 4 KiB。视频和全屏的超时继续由官网原 dialog 的撤销负责，不能沿用音频会话无限等待来覆盖官网倒计时。全屏原有空预览不显示空框，面板沿用 WebView 普通材质，不采样网页。

网站操作已经完成而原生面板关闭效果尚未开始时，下一条请求可接替已完成的请求，不误判为并发申请并取消。接替前清理已完成请求，尚未答复的请求和其他操作面板仍保持现有排他规则。WebView 释放后不再发送延迟的输入事件。

## Existing note audit

[音频原生界面](../../implemented/feature/2026-11-06-mini-app-native-audio-consent.md)部分重叠，本篇扩展其交接协议到视频和全屏，原篇保留音频回调与浏览器激活的理由和验证。[剪贴板原生确认](../../implemented/feature/2026-11-05-mini-app-native-clipboard-consent.md)及[记住剪贴板选择](../../implemented/feature/2026-11-05-mini-app-remembered-clipboard-consent.md)保留其兼容桥与本地许可，不改执行策略。[沉浸视图](../../implemented/feature/2026-11-04-mini-app-immersive-runtime.md)与[原生启动进度](../../implemented/feature/2026-11-04-mini-app-native-launch-progress.md)中视频/全屏保留网页确认的范围被部分替代，其全屏展示、稳定 WebView 和进度实现继续使用。网页容器、目录和社区操作笔记无独立协议冲突；未发现同主题 proposed/rejected。

## Alternatives considered

- 每类新增独立原生权限实现能分别优化功能，但会复制官网播放和全屏逻辑，偏离仅替换界面的要求；复用原网页按钮回调。
- 直接显示所有形似权限的网页 dialog 适配最广，但可能误解登录和其他确认；只接管已核对的三类，剪贴板继续已有通道。
- 原生按钮仅 JavaScript click 最简，但会改变官网媒体/全屏回调看到的浏览器激活；复用现有受限选项输入交接，不降低浏览器设置。

## Testing

- 新增视频/全屏回调用例先在旧运行时代码上失败，未显示原生确认，见 `artifacts/mini-app-operation-repro-device.txt`。官网 loader 中已核对 video.play 使用资源预览、fullscreen.request 使用空预览、通用选项翻译、原 cancel 和执行 Promise；不复制媒体与全屏实现。
- 第一轮 9 项音频/视频/全屏用例发现连续请求被旧面板误拒绝，见 `artifacts/mini-app-operation-device.txt`；复现用例在修复前失败，增加已完成请求的即时接替后通过，不以增加测试延时避开问题。
- 最终 MiniAppAudioPermissionUiTest、MiniAppWebUiTest、MiniAppClipboardRequestTest 与 WebScreenUiTest 共 52 项真机回归全部通过（97.691 秒），见 `artifacts/mini-app-operation-final-device.txt`。覆盖两种按钮原回调、真实网页全屏进入/退出、未答复不执行、原返回值/错误/超时、连续不同操作、翻译、浅深色 × 三种材质与 130% 字体，以及原有音频、剪贴板许可记忆、来源隔离、取消、失败回退和稳定 WebView。
- 视频夹具验证原网页执行回调和结果交接，未引入或验证原生视频播放器。音频沿用静音 WAV 初始化夹具；全屏调用真实 requestFullscreen。截图 `artifacts/mini-app-operation-dialogs.png`、`artifacts/mini-app-video-light.png`、`artifacts/mini-app-video-dark.png`、`artifacts/mini-app-fullscreen-light.png`、`artifacts/mini-app-fullscreen-dark.png` 已检查中文清晰、预览紧凑且全屏无空预览框。
- MiniAppActionsTest、MiniAppInfoTest、MiniAppLaunchStateTest 共 20 项 JVM 测试通过，Debug/AndroidTest 构建、JavaScript 语法和 UTF-8 乱码检查通过；记录为 `artifacts/mini-app-operation-final-build.txt`、`artifacts/mini-app-operation-sequence-build.txt`。Lint 保持 0 errors / 71 warnings / 1 hint，无新增告警，见 `artifacts/mini-app-operation-final-lint.txt`；`npm run verify-notes` 验证 175 篇笔记通过。
- RMX2202 / Android 13，构建参数与覆盖安装均显式保持 0.3.5-dev / 3006，安装前核对同一 Debug 签名，首次安装时间仍为 2026-10-23 14:55:34。只使用真机和手动 instrument，未启动桌面浏览器、模拟器或发布版本；未卸载和清数据。

## Consequences

五类操作确认均有原生界面，音频、视频和全屏复用同一选择交接，剪贴板复用既有兼容桥与本地许可。界面适配不承担手机系统权限判断，也不改变网页运行时的业务决定；该边界同步记入 AGENTS.md。

官网 DOM、翻译和浏览器输入行为变化仍可能触发网页回退。受控真实 WebView 的回调和全屏验证不能代表每款线上小程序业务均完成验收；后续线上验收应分别确认这五类原生选项及原网页业务结果，而非增加系统权限检查。
