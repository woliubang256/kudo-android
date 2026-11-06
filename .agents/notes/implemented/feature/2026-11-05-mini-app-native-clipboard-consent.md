# Agent Note: 小程序剪贴板单次原生确认

Status: implemented

## Problem

官网先确认剪贴板操作，WebView 接口失败后又触发原生确认。用户要求能真正原生处理的权限改为原生弹窗；本次仅处理剪贴板文字读写，声音、视频和全屏仍依赖网页真实手势。

## Decision

原生请求取消或完成后不可再次读写，执行前还检查前台状态和完整顶层 URL。切页、后台和超时均撤销，文字上限 4 KiB。每次都确认的策略由[授权记忆](2026-11-05-mini-app-remembered-clipboard-consent.md)部分取代；本篇保留官网适配与请求有效性约束。

兼容脚本仅在官方应用文档和同源 frame-host 中生效。识别官网固定 dialog 结构、操作标题和按钮文案，优先使用首屏预加载 configuration.translations.native，中文官网已核对的文案作为 SPA 回退。无法识别时保留网页原交互。只把已通过官网校验的剪贴板执行回调推进到被包装的 navigator.clipboard 接口，在该接口弹出原生确认；不产生通用授权令牌，不提前读取剪贴板，也不点击声音、视频和全屏的确认。

脚本请求带文档随机标识、请求序号和完整顶层 URL。原生确认绑定同一请求；网页 dialog 被移除、原请求超时、pagehide 或站内 URL 改变时取消对应原生请求。原生回复仍完成官网原有 Promise，不复制 SDK、manifest 权限、登录或资源校验。

## Existing note audit

[小程序网页容器](../../implemented/feature/2026-11-04-mini-app-square.md)部分重叠：本篇替代“优先浏览器接口、失败后二次确认”的剪贴板决定，其来源隔离、H5、关于、媒体和普通材质约束继续有效。[沉浸运行视图](../../implemented/feature/2026-11-04-mini-app-immersive-runtime.md)和[原生启动进度](../../implemented/feature/2026-11-04-mini-app-native-launch-progress.md)部分重叠：仅剪贴板确认让位于原生面板，其余网页权限继续显示。未发现同主题 proposed/rejected。其他应用广场、原生正文小程序卡片与链接笔记无关。

## Alternatives considered

- 保留标准接口优先最少改变浏览器行为，但会出现两次确认，且某些内核写入成功时绕过原生面板，不能达到一致体验。
- 在网页确认前直接原生读写能够完全去掉网页 dialog，但绕过官网 manifest 和登录检查；因此只在官网执行回调到达剪贴板接口时请求原生操作。
- 原生确认后合成网页点击适用于无需浏览器手势的剪贴板，但不能给声音和全屏提供真实用户激活；本次不接管这些操作。

## Testing

- 旧实现先失败两项：官网写入停在网页 dialog，未出现原生确认；原生请求 cancel 后调用旧 allow 仍改写剪贴板。记录为 `artifacts/mini-app-native-clipboard-repro-device.txt`。
- 首轮 MiniAppWebUiTest 与 MiniAppClipboardRequestTest 共 20 项通过（30.586 秒），记录为 `artifacts/mini-app-native-clipboard-device.txt`。验证官网原 Promise 的读写和取消、仅一次原生确认、浏览器 API 本身成功时也需确认、写入完整文字而非 200 字预览、4 KiB UTF-8 上限、旧请求不可重放、后台暂停、英文预加载配置与未知声音 dialog 保留。剪贴板测试保存并恢复原内容，不输出用户剪贴板。
- 补充同源 frame-host、并发取消、脚本超时与后续请求，再加 WebScreenUiTest 和 WebViewMaterialLayoutUiTest，共 17 项通过（23.245 秒），记录为 `artifacts/mini-app-native-clipboard-extended-device.txt`；两轮共 37 项不同用例。第三方与 opaque iframe 没有桥，媒体需要手势、文件访问和第三方 Cookie 限制保持。
- Debug / AndroidTest 构建通过；lint 为 0 errors / 68 warnings / 1 hint，见 `artifacts/mini-app-native-clipboard-build.txt`、`artifacts/mini-app-native-clipboard-extended-build.txt`、`artifacts/mini-app-native-clipboard-lint.txt`。JavaScript 语法和 UTF-8 乱码检查通过。
- 真机 RMX2202 / Android 13，构建与安装均为 0.3.1-dev / 3002，同签名覆盖安装，不卸载或清数据。浅深原生确认截图 `artifacts/mini-app-clipboard-light.png`、`artifacts/mini-app-clipboard-dark.png` 中中文清晰；系统剪贴板浮层来自测试种子文字，界面本身未新增文案或材质。
- 最终补上桥已释放时仍结束网页 Promise 的保护，Debug 再构建并覆盖安装后，22 项小程序与原生请求用例全部通过（33.661 秒），记录为 `artifacts/mini-app-native-clipboard-final-build.txt`、`artifacts/mini-app-native-clipboard-final-device.txt`。首次安装时间保持 2026-10-23 14:55:34；`npm run verify-notes` 验证 170 篇笔记通过。
- 桌面已读取当前官网预加载 `mini-app/production/<id>/` 的 configuration 及 native 翻译，确认 action.clipboard.readText/writeText、rejectAction 和 allowAction 的实际形状。真实登录后的 SDK 剪贴板业务没有完整验收，离线真实 WebView fixture 的成功不代替线上业务通过。

## Consequences

已识别官网剪贴板操作只显示原生面板，浏览器能力差异不再改变确认次数。官网 dialog 结构或文案变化时保留网页确认，不猜测权限类型，可能重新出现网页加原生的两次确认；无预加载配置的站内 SPA 入口当前仅内置已核对的简中文案。音频会话的确认界面由[原生声音弹窗](2026-11-06-mini-app-native-audio-consent.md)部分接管，以本次原生选择转交原网页按钮，保留浏览器激活和官网回调；本篇关于普通 JavaScript 点击不能提供播放手势的限制仍成立。视频与全屏的确认界面由[统一操作弹窗](2026-11-07-mini-app-native-operation-dialogs.md)扩展，执行仍归官网；资料和网络授权继续由官网处理。原生面板保持网页容器既有普通材质，不采样 WebView。
