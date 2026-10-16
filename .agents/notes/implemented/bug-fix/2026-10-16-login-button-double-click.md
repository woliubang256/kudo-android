# Agent Note: 修复登录按钮重复触发

Status: implemented

## Problem

登录页接入玻璃顶部操作后，TopBarTextButton 的外层 Box 和内部 LiquidButton 同时注册点击事件。一次点击会触发两次登录检查，导致会话状态和导航回调发生竞态。

## Decision

玻璃模式下由 LiquidButton 独占点击处理，外层容器只负责尺寸和布局；新增 UI 回归测试验证一次点击只执行一次会话检查。

## Alternatives considered

- 在登录页增加点击节流只能掩盖组件层重复事件源。
- 在 checkSession 内忽略重复调用会让其他合法调用失效。
- 固定所有 WebView 为普通材质可最大限度避开玻璃采样风险，但不满足用户保留可选高级模式的要求；因此默认普通，仅显式开启后创建高级采样层。

## Consequences

玻璃顶部文字按钮不再由外层容器重复响应点击；非玻璃回退按钮行为保持不变。

## WebView crash fallback (2026-10-17)

版本对比发现 0.2.7 将登录 WebView 页从 0.2.6 的普通 Column 和 PageTopBar 切换到 MaterialPageScaffold 与 TopBarTextButton。由于用户反馈点击登录立即闪退，默认分支恢复普通顺序布局、不创建采样源，保留渲染进程退出处理；高级分支仅在用户明确开启网页高级材质后启用。其他原生页面材质保持不变，当前设备测试不能证明已解决其他厂商系统的闪退。



## WebView material preference (2026-10-17)

网页高级材质默认关闭，使用 `advanced_webview_material` 同时读取和保存，重建 AppSettings 后保留用户选择。WebViewMaterialSettingsUiTest 使用隔离偏好验证默认关闭、开启后重建仍开启、关闭后重建仍关闭及其他设置不变；修复前真机已复现重建后错误回到关闭。

登录页读取 LocalDisplaySettings 的网页材质偏好，传给 MaterialPageScaffold，顶部完成操作使用单一点击源的 TopBarTextButton。此前页面固定为普通 Column，导致用户在设置开启后仍看不到高级分支。LoginWebViewUiTest 先复现开启偏好后无采样层，再分别覆盖普通与高级登录的采样选择、验证码高度、视口随可用空间缩放且不重载，以及一次完成点击只检查一次会话。

VerificationHost 将已接收的 advancedMaterial 继续传给内部 VerificationScreen，不能只在宿主签名接收却丢弃。VerificationUiTest 先复现宿主开启后仍无采样层，再覆盖关闭、开启、再次关闭后重新进入验证页的分支选择；验证码页面和原生验证检查、刷新及返回行为保持不变。

网页高级材质只影响原生顶部栏与按钮；WebView 仍位于工具栏下方，玻璃背后是主题背景，网页自身不变成透明玻璃。纯色背景下模糊和折射的可见差异较小，视觉判断必须同时核对页面是否实际接入开关。继续遵守[顶部材质](../feature/2026-10-16-page-toolbar-material.md)的有界网页视口约束，避免为放大效果遮挡网页首行或改写网页内容。

## Testing

RMX2202 / Android13 的 LoginWebViewUiTest、VerificationUiTest、WebViewMaterialLayoutUiTest、WebViewMaterialSettingsUiTest 与 WebScreenUiTest 共 26 项通过，覆盖两种登录材质、宿主参数转发、普通采样退出、偏好重建、验证码视口和网页导航回归。新增缺陷用例均先在修复前实现上失败，修复后通过。

同签名开发版 0.2.10-dev / 2021 通过 adb install -r 覆盖安装，未卸载或清除应用数据。生产登录页普通与高级模式分别截图人工查看：高级按钮具有高光边缘，普通按钮无玻璃边缘，网页首行均位于提示语下方；恢复测试前的开启选项后，强制停止并重启仍启用高级登录材质。截图保存在本机临时目录 kudo-webview-label-check 的 login-ordinary.png、login-advanced.png 和 login-after-restart.png。上述视觉验收与自动回归结果分开，不代表 ColorOS16 或 OneUI8.5 的崩溃已经验证解决。

