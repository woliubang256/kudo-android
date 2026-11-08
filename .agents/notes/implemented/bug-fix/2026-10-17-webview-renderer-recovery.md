# Agent Note: WebView renderer crash recovery

Status: implemented

## Problem

Some devices reported an Android System WebView crash only when opening the login page. The operating system recovery dialog indicated that the installed WebView provider was incompatible or corrupted. A WebView renderer termination could otherwise terminate the host activity or leave a dead WebView mounted.

## Decision

Handle `onRenderProcessGone` in both the login WebView and the in-app web reader. The callback clears the active reference, stops loading state, shows a user-facing recovery message, and changes a Compose generation key so the dead instance is released and never reused. Automatic reload is not attempted from the callback; the reader keeps its explicit retry action.

## Alternatives considered

- Catching the failure around network or page callbacks cannot handle a native WebView renderer signal.
- Retrying the same WebView instance can reuse a dead renderer and create a crash loop.
- Disabling all WebView usage would break login and in-app web navigation, so the recovery remains localized.

## Consequences

Java/Kotlin-level renderer termination callbacks now degrade to an error state when Android delivers the callback. Provider crashes during WebView construction or system-level failures may still terminate the process; provider diagnostics and a fallback entry point remain follow-up work.
## Follow-up

Renderer error messages include the current WebView provider package and version so affected users can report diagnostics without Logcat.

## SVG signature protection

用户反馈小米 15 / HyperOS 3.0.301.0 偶发进入帖子闪退；RMX2202 / Android 13 打开 3137953 后上下滚动 80 次，PID 与 crash buffer 保持不变，不能据此判断原机原因。排查发现 SVG 签名图片壳缺少 onRenderProcessGone 处理，用户明确同意补齐该保护。

签名按同一退出契约处理 didCrash=true 与系统回收：立即停止使用并释放失效视图，通知签名宿主隐藏失败图片，正文及其他签名内容继续保留。释放必须幂等；布局测量、已排队尺寸回调、生命周期与晚到网页回调都不得再次使用已释放实例。不自动重载、重建该失败图片，不调用全局 pauseTimers，也不改变既有 WebView 安全设置或材质。

沿用本篇与[SVG 签名](2026-10-10-svg-user-signatures.md)的职责，签名尺寸与动画契约保留。[网页普通材质](2026-10-29-webview-ordinary-material.md)仍管理网页顶部，不以本次保护认定或修复未复现的 GPU / HyperOS 问题。未发现同主题 proposed/rejected；无需归档旧决定。

复现测试使用真机真实 WebView 与隔离 SVG 夹具，显式投递渲染进程退出回调，不终止实际系统渲染器。检查回调被处理、失败图片及空签名区域移除、混合签名文字和原生正文保留、只销毁一次，以及晚到回调和前后台切换的资源安全。新增测试先在旧实现运行，再验证修复。

2026-11-08 在 RMX2202 / Android 13、Google WebView 156.0.8078.47 上，修正夹具地址变量与 WebView.url 重名后，同一测试 APK 在旧版的 3 项均因未处理退出回调而失败（artifacts/signature-renderer-fixture-repro-device.txt），覆盖安装修复版后 3 项全部通过（artifacts/signature-renderer-fixture-fixed-device.txt）。已断言只销毁一次、销毁后没有生命周期或尺寸访问、不重新创建失败签名。首次回归的 3 项夹具线程错误不作为产品复现证据；只修改夹具变量名，保留所有产品断言。

既有离线 SVG 的比例、错误释放、CSS 动画及关闭、原生滚动及离屏释放 4 项，与普通签名失败隐藏 2 项均通过（artifacts/signature-site-fixed-device.txt）。同轮 WebView / 链接回归另有 24 项通过，总计 33 项不同真机用例通过；未重跑真实网络 SVG。相关 19 项 JVM、Debug / AndroidTest 构建及 Lint 通过（artifacts/signature-site-fixed-build.txt、signature-renderer-fixture-build.txt）。显式版本 0.3.5-dev / 3006、核对签名后只用 install -r 覆盖应用与 -r -t 安装测试包，没有卸载或清除数据。

保护仅覆盖系统投递 onRenderProcessGone 的退出；原小米机型没有复现或崩溃栈，不能将本次通过的夹具测试记为该偶发闪退的根因确认或修复证明。
