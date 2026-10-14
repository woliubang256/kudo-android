# Agent Note: 应用内下载更新与系统安装

Status: implemented

## Problem

更新面板只提供 GitHub 网页入口，用户要求直接在应用内下载安装包，下载完成进入系统安装界面。

## Decision

读取公开正式 Release 中当前发布约定的 `kodo_<version>_release_arm64-v8a.apk` 附件，只接受已上传、正大小且属于已知仓库和 Tag 的 HTTPS 地址。附件缺失时明确显示暂无安装包，保留查看 Release 的辅助入口。首页与手动更新共用同一下载操作区。

复用原生下载管理器与 DoH/ECH 网络策略、进度、后台服务、持久化、取消、重试及完成通知。同一附件复用正在进行或已完成的任务。更新面板展示下载进度和取消、重试、安装动作；下载完成后只在应用前台调用系统安装界面。后台完成仍由现有下载通知承接，恢复前台后继续安装。

安装前把完成文件复制到私有缓存并校验 APK 可解析、包名、版本及与已安装应用的签名兼容；有 GitHub SHA256 digest 时同时校验。通过受限 FileProvider URI 授予系统安装器临时读取权限。首次缺少安装来源授权时打开本应用的系统设置，授权返回后继续安装；拒绝保留安装按钮。安装由系统最终确认，应用不能静默覆盖自身。下载管理的 APK 打开动作也接入安装来源授权；普通文件继续原有打开方式。

## Existing note audit

[公开版本检查](../../implemented/feature/2026-10-11-github-updates.md)部分重叠：版本比较、日志和首页静默检查保留，本篇取代其“前往 GitHub 下载”主入口及暂不内置安装的取舍。[原生下载管理](../../implemented/feature/2026-10-14-native-download-manager.md)保留任务与通知责任。[DoH/ECH](../../implemented/feature/2026-10-12-doh-network.md)仍将 GitHub 版本元数据请求豁免为独立系统网络；APK 文件下载采用现有原生下载路由。没有同主题 proposed/rejected 或完全被取代的旧笔记。

## Alternatives considered

- Release 网页支持所有附件、浏览器下载能力成熟，但会离开应用；用户已选择内置下载，网页仅作为缺失附件和查看详情的辅助入口。
- 独立实现更新下载器能私有保存 APK 并细分状态，但会重复已有任务、后台服务和通知能力；复用下载管理器，安装前复制校验。
- 下载完立即从后台启动安装可减少一次回前台操作，但 Android 限制后台启动且会打断其他应用；待应用前台继续系统安装。

## Verification

公开 GitHub latest API 实际返回 v0.2.4，附件名、已上传状态、大小 9223822 与 SHA256 digest 符合选择规则。验证仅使用公开无凭据请求。

首轮 23 项真机用例中 22 项通过，进度文本显示失败：Row 内复用 fillMaxWidth 的 ModalLink 让权重文本得到零宽度。已有用例先复现，再将取消动作改为紧凑按钮；完成态安装与重新下载同排，避免矮屏操作区过高。日志 artifacts/in-app-update-device.txt。第二轮六项下载/授权/短窗口用例通过，新加删除安装包用例复现 FileNotFoundException 向用户暴露路径，随后统一为“安装包已不可用，请重新下载”；日志 artifacts/in-app-update-repro-device.txt。

最终 16 项 JVM 通过：GitHubUpdatesTest 六项、HomeUpdateViewModelTest 三项、ApkInstallViewModelTest 三项及 NativeDownloadManagerTest 四项。构建、Lint 通过，0 errors / 45 既有 warnings / 1 hint，无新增告警（artifacts/in-app-update-final-build.txt）。

RMX2202 / Android13 共 34 项不同真机用例通过：UpdateDownloadUiTest 七项、ApkInstallerDeviceTest 五项、HomeUpdateUiTest 五项、UpdateUiTest 七项、DownloadsUiTest 两项、NativeDownloadDeviceTest 七项、DownloadNotificationNavigationTest 一项。34 项批次中手动更新夹具临时在主线程检查回调内生成 MockWebServer URL，出现无消息异常；改为在组合前构造 Release 后，七项下载 UI 用例全部通过，生产逻辑未因夹具调整。日志 artifacts/in-app-update-final-device.txt、artifacts/in-app-update-manual-final-device.txt，不重复计算复验项。

已查看 artifacts/update-native-progress.png、artifacts/update-native-short-dark.png，确认进度与取消动作同时可见，320×360dp 深色 130% 字号下长日志滚动且安装操作固定。APK 安装准备验证当前已签名开发包可复制解析与读取、错误大小/摘要/版本/其他包名/无效包/文件删除均拒绝，既有正式 0.2.4 APK 的不同签名也被拒绝。构建显式版本 2005 / 0.2.3，签名 SHA256 与已安装包相同，仅使用 adb install -r 和测试包 -r -t 覆盖安装并显式 instrument，保留应用数据。

## Consequences

更新下载直接复用原生下载任务与网络路由。正式包与开发包签名不同，开发版不能覆盖安装正式包，会明确报错并保留数据。首次安装需要用户系统授权和最终安装确认。APK 缓存占额外空间，后续安装准备清理超过一天的旧缓存；进程结束后自动安装意图不保存，可从下载管理再次安装。GitHub 网络和系统安装器存在设备差异。只有当前发布约定的 arm64-v8a 附件可自动选择，新增架构或命名约定须同步选择规则。

真机安装准备测试读取当前 Debug APK 和既有正式 APK，不执行最终安装或修改未知来源授权。授权回调及系统安装启动通过可注入回调夹具验证；其他厂商的系统授权、安装界面尚未逐台验收。
