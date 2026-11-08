# Agent Note: 用户授权后手动启用 Sentry

Status: implemented

## Problem

应用目前没有 Sentry。用户提供接收错误的 DSN，要求禁止自动初始化，首次使用明确征求同意；拒绝时保持未接入时的运行行为。设置需要展示并修改选择，修改后让用户决定立即重启还是下次启动生效。

## Decision

按 SDK 与授权存储、首次询问及设置交互、重启与集中验证三个单元实施并分别提交。

固定使用 sentry-android-core 8.63.0，不引入 NDK 和 Replay 模块。只保留未捕获异常、授权后的实时 ANR 看门狗与已过滤事件的缓存重送集成，不读取授权前的历史 ApplicationExitInfo。关闭自动性能、会话、日志、指标和面包屑。beforeSend 重建白名单事件，传输包装器再过滤缓存重送及附件，防止旁路。异常消息全部去除，调用栈只保留代码符号、相对文件名和行号；环境由应用版本及系统型号重建，不复制 SDK 的完整环境或账号上下文。只保留固定的 ANR / UncaughtExceptionHandler / generic 类别、handled 布尔值和格式验证后的 Proguard UUID，删除 mechanism 数据/描述及调试文件路径，避免隐私过滤破坏错误分类或后续 mapping 关联。

禁用 Sentry Android 的自动初始化入口，并检查最终合并 Manifest。仅由应用自有控制器在允许状态手动初始化一次；后台服务、小程序独立任务或页面重建不能绕过决定。尚未选择和明确拒绝都不能初始化 SDK、创建上报任务或发送事件。官方手动初始化文档确认支持 io.sentry.auto-init=false，具体 Provider 和性能入口以选定 SDK 的 Manifest 为准。

授权单独存入应用私有偏好，使用未选择、允许、拒绝三态，作为应用级而非账号级设置。新安装和升级后尚未选择的用户，在首次前台界面询问一次；拒绝后不重复询问。允许后持久化并在当前进程手动启用，之后每次冷启动按已保存结果决定是否手动启用。未选择时直接进入后台的组件不弹窗，也不上报。

首次询问显示“是否启用 Sentry 错误上报？”及“仅收集排错所需的错误堆栈、应用版本及系统和设备信息，用于定位问题。可随时在设置中修改。”按钮为“不允许”“允许”。弹窗跟随当前材质，真实背景采样不能包含自身，也不能重新把 WebView 放入 Compose 背景录制层。

设置新增“Sentry 错误上报”开关，显示已保存的用户选择。切换时询问“立即重启生效”“下次启动生效”；取消或返回不修改。选择后先可靠落盘，再执行对应行为。下次启动指下一次进程启动，当前进程继续使用启动时的决定；回前台、导航和 Activity 重建不提前应用。立即重启必须是真正重启应用进程，不能只重建当前 Activity；提示会中断正在运行的小程序和音乐，并保留账号与其他本地数据。重启辅助进程不能初始化 Sentry 或重复创建应用容器。

仅上报必要的错误与诊断环境，关闭性能追踪、性能分析、会话回放、截图和视图层级附件。不绑定 Linux.do 账号，不上传 Cookie、请求头、请求正文、帖子和输入内容；发送前过滤用户、请求、面包屑、附件和可能含 URL 或数据的异常消息。隐私说明与开源致谢同步更新。测试使用隔离偏好及本地接收端，不能擅自为已连接真机保存“允许”或向正式 DSN 发送测试事件。

## Existing note audit

检索 Sentry、错误上报、首次启动、重启与隐私说明，未发现同主题决定。[应用容器](../../implemented/architecture/2026-10-07-native-community-client.md)保留主线程初始化与单例图像加载器约束；Sentry 授权必须在不破坏现有初始化的前提下单独处理。[关于页](../../implemented/feature/2026-10-10-about-settings.md)的隐私与致谢需要同步事实。[小程序独立任务](../../implemented/feature/2026-11-07-mini-app-independent-tasks.md)保留入口与账号隔离，立即重启覆盖同一进程中的后台任务。[网页普通材质](../../implemented/bug-fix/2026-10-29-webview-ordinary-material.md)保留兼容边界。均为部分关联，不归档。

## Process restart

SettingsScreen 只显示保存选择，ReportingController 的 start 在本次进程只读取一次启动决定。首次明确允许另行执行一次初始化；后续设置修改只保存，下次进程生效。异常消息全部删除会降低部分问题的细节，但保留异常类型、代码模块/函数、相对源文件名与行号；缓存事件保留格式验证后的原始 release，不能将升级前崩溃错误归到新版本。

首次询问接入两个前台 Activity，使用独立 Dialog Window 与 Activity PixelCopy 采样，拒绝/允许必须明确选择；返回和点空白不替用户决定。共享所有权在后台或销毁时释放，避免两个恢复的界面并行询问。设置取消不保存，落盘失败保留开关并显示错误；开关具有无障碍名称。

ReportingRestartActivity 在非导出的 :restart 辅助进程中前台启动，验证父进程 UID、名称和目标组件，再结束原进程任务、终止父 PID、等待退出并重启入口。KudoApplication 只在主进程创建 ReportingController 与 AppContainer，辅助进程不触碰 SDK、网络客户端或 WebView。Android 26/27 从 ActivityManager 或自身 cmdline 获取进程名，旧系统使用 RecentTaskInfo.id，避免新 API 导致冷启动崩溃。辅助进程退出后自行结束。Debug 独立进程 fixture 复用同一重启路径，使用单独授权文件和假初始化器，不中止真机的主应用进程。

## Alternatives considered

- 使用默认自动初始化最容易接入，也能捕获最早期的崩溃，但会先于用户选择运行；禁用自动入口，按同意结果手动启用。
- 先初始化再丢弃事件可以复用 SDK 的采集和同意接口，但仍安装了监控、线程或原生组件，不满足拒绝后不初始化的要求。
- 切换开关后热更新 SDK 能立即生效且无需重启，但与用户要求的两种生效时间不一致；区分保存的选择与当前进程实际状态。
- 仅重建页面实现最简单，但 Application、后台小程序和音乐服务仍存活，无法可靠应用启动决定；立即生效采用完整进程重启。

## Testing

- 13 项 JVM 用例通过（ReportingConsentTest / ReportingPrivacyTest，artifacts/sentry-verified-build.txt），验证三态、一次初始化、写入失败、跨界面所有权、下次进程语义、缓存 release、敏感内容清除，以及实际 HTTP 传输只发送白名单事件。固定诊断类别和 UUID 的复现用例先失败于 ANR 被改成 generic（artifacts/sentry-mechanism-repro.txt），修复后与其他用例集中通过，真实 SDK 真机复测通过（artifacts/sentry-sdk-final.txt）。
- 真机 RMX2202 / Android 13 的 8 项 Sentry 专项用例通过。授权使用假初始化器或隔离偏好；真实 SDK 使用本机 MockWebServer DSN 与独立缓存，测试关闭 SDK，正式 DSN 没有收到测试事件。辅助进程确认父 PID 变化、保存的授权在新进程启用且本地 sentinel 保留。
- 首次弹窗在浅深色、三种材质及 130% 字号下文字和操作可见。模糊/液态分支实际 PixelCopy 已就绪，截图在 artifacts/sentry-verification/；已目视确认浅色液态、深色模糊与浅色半透明的中文、对比度和背景来源。
- Debug / Release 合并 Manifest 均为 auto-init=false 且没有 Sentry Provider；Release 无 debug fixture。最终 Lint 与 Release R8 混淆构建均通过（artifacts/sentry-verified-build.txt、artifacts/sentry-release-final-r8.txt），API 26 兼容问题由失败的 Lint 复现后修复。
- AboutPageUiTest 的旧设置用例曾直接寻找尚未组成的 LazyColumn 项，失败日志保留在 artifacts/sentry-device-final.txt；现使用 performScrollToNode 先滚动，不改变被检查的标题或可见性要求。受影响的 3 项复测通过（artifacts/sentry-device-recheck.txt），21 项相关真机用例均已通过。
- 仅覆盖安装相同签名的 0.3.5-dev / 3006，firstInstallTime 保持 2026-10-23 14:55:34；实际 error_reporting 授权文件仍未设置，测试未改变账号授权。

## Consequences

首次授权前的启动错误无法上报，这是明确同意的必要边界。立即重启会中断原进程中的小程序和音乐；选择下次启动时当前状态不变，包括关闭后当前进程仍继续按原决定上报。SDK 增加 APK 与运行时开销；Core 不引入 Sentry NDK / Replay 原生库，也不支持原生 C/C++ 信号崩溃。授权前的历史 ANR 不读取，避免追溯收集。

正式包启用 R8 混淆，DSN 只提供事件接收能力；完整还原混淆堆栈需要对应构建的 mapping 与 Sentry 项目符号处理。[仅 CI 上传映射](../process/2026-11-08-ci-only-sentry-mappings.md)补充 Tag 正式构建的官方插件与凭据边界，本地 Debug / Release 不应用插件或上传。v0.3.8 的 Tag CI 已成功上传 mapping，下载实际公开 APK 后确认 UUID 一致、auto-init=false 且无 Sentry Provider；验证记录归属该篇。运行时事件只验证本地接收，未向正式 DSN 发送测试事件，正式事件接收与服务端 IP 保留策略没有代替用户改动；隐私说明如实说明接收服务可见来源 IP。关于页、THIRD_PARTY_NOTICES.md 与随包 Sentry / Epitaph MIT 许可同步。
