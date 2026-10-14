# Agent Note: Native download manager

Status: implemented

## Problem

Download links in topic content currently enter WebView and cannot reuse the app's DoH/ECH route. There is also no task list for active or completed downloads.

## Decision

The application-owned download manager uses OkHttp through `DohNetwork.client`. The initial request and every redirect hop strip any previous Cookie and attach the existing session only for HTTPS linux.do on port 443; CDN requests retain the user agent and public referer but no community credentials. Responses stream to the public Downloads collection through `MediaStore` on Android 29+ and the app-specific external Downloads directory on older Android, shared through FileProvider. HTML and Cloudflare challenge responses are rejected rather than published as files.

Task metadata is persisted in private preferences and exposed as a StateFlow. Progress updates are throttled to 500 ms and do not rewrite the task store. A dataSync foreground service keeps user-started downloads alive in the background and displays an ongoing progress notification. Completion replaces the progress display with a per-task system notification; permission or channel denial queues an app event until the UI returns to the foreground. Notification clicks open download management. Cancel, file commit and terminal-state updates are serialized so a canceled task cannot become completed.

Download-looking links are intercepted before content routing. Ordinary web links keep the existing in-app WebView behavior. Both guest and member pages place download management directly below settings. Tasks can be canceled, failed/canceled tasks can be retried, and completed files can be opened. Removing or clearing records preserves downloaded files. A verification failure offers the existing manual verification workflow and retries only after it completes.

## Existing note audit

[应用内更新安装](2026-10-14-in-app-update-installation.md)复用下载任务、网络与通知，更新 APK 下载完成后前台进入安装流程；下载管理中 APK 的打开动作显示“安装”并接入来源授权。普通文件保留原有打开行为。

[Web routing](2026-10-10-in-app-web-links.md) partially overlaps: native file links now leave that routing path before WebView; ordinary web navigation and existing WebView download fallback remain owned by that decision. [DoH/ECH policy](2026-10-12-doh-network.md) retains all routing rules, including suspension under VPN/system proxy and the distinction between configured DoH and accepted ECH. [Local notifications](2026-10-11-local-notification-delivery.md) remains responsible for community notifications; download notifications use a separate channel and do not change reading state. No prior note is fully superseded.

## Alternatives considered

- Android `DownloadManager` would provide background scheduling, but it cannot use the app's DoH/ECH `Call.Factory` or the same authenticated cookie policy.
- Keeping the download inside WebView would preserve browser behavior but would bypass the requested native network path and make task management depend on WebView callbacks.
- Storing only in memory would make the account page lose completed records after process recreation, so task metadata is persisted while active transfers use a foreground service. Automatic replay/resumption could recover long downloads but requires Range/ETag validation and durable scheduling; interrupted tasks instead show a retry action.

## Consequences

Native downloads follow the same routing policy as API and image requests. The app owns progress, cancellation, naming, and task history. Transfers are not resumed after process death; persisted in-progress records are marked failed and their known pending files are removed on the next startup. The latest 100 task records are saved. Android 29+ writes user-visible files to Downloads without storage permission; older Android versions use app-specific external storage. Foreground-service permissions are required and Android's dataSync timeout cancels active tasks. Clearing history keeps files; the file manager remains the place to delete completed files. Cloudflare verification can still depend on network exit and TLS stack.

## Testing

Four JVM tests verify link classification, filename parsing, literal plus/percent preservation, and cookie stripping on foreign/insecure origins. NativeDownloadDeviceTest uses isolated task preferences, notification channels and a local MockWebServer; it covers cancellation before call registration, HTML/challenge rejection, record removal without file deletion, retry, restored completed metadata, long-sized progress notifications, and delayed app events for a disabled notification channel. DownloadsUiTest verifies guest/member entry order in narrow dark large text and task retry/removal. The notification route is checked in a Compose test host; an initial real-MainActivity ActivityScenario run timed out during teardown and was replaced with this host, rather than changing production activity behavior.

On RMX2202 / Android 13, the initial three regression cases all failed (artifacts/download-repro-device.txt), then passed after correction (artifacts/download-fix-device.txt). Screenshot artifacts/downloads-completed.png was visually inspected. The installed development APK is 2005 / 0.2.3, its certificate SHA256 matches the new build, and APK/test APK installation uses install -r / install -r -t without clearing data.

Final verification: 4 JVM cases and 10 device cases pass (artifacts/download-verified-build.txt, artifacts/download-verified-device.txt). Lint has 0 errors, 45 existing warnings and 1 hint; verify-notes passes all 103 notes. The real OPEN_DOWNLOADS MainActivity intent is also exercised with am start and its UI hierarchy confirms download management (artifacts/download-notification-route.xml).

The user's example topic 3020259 was read through the existing production route. With the current settings (DoH off, system proxy present), the upload returned a Cloudflare 403 challenge (artifacts/download-live-report.json). A separate read-only probe using the same DohTransport, selected DoH server, user agent and cookie policy downloaded key-labyrinth-20261013.zip from cdn3.ldstatic.com: HTTP 200, 10,589 bytes, PK ZIP header verified, linux.do ECH accepted (artifacts/download-direct-report.json). This probe did not change app or phone network settings and is not evidence that the current disabled production DoH setting is active.
