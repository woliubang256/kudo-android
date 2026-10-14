# Agent Note: Native download manager

Status: implemented

## Problem

Download links in topic content currently enter WebView and cannot reuse the app's DoH/ECH route. There is also no task list for active or completed downloads.

## Decision

Add an application-owned download manager backed by OkHttp through `DohNetwork.client`. Requests attach the existing Linux.do session cookie, user agent, and referer. Responses stream to the public Downloads collection through `MediaStore` on modern Android and an app-owned Downloads directory on older Android. Task metadata is persisted in private preferences and exposed as a `StateFlow` for the account page. A dedicated notification adapter reports progress and completion when system notifications are enabled; otherwise completion is surfaced through the app toast layer.

Download-looking links are intercepted before content routing. Ordinary web links keep the existing in-app WebView behavior. Tasks can be cancelled while active and completed or failed records can be removed from the download manager.

## Alternatives considered

- Android `DownloadManager` would provide background scheduling, but it cannot use the app's DoH/ECH `Call.Factory` or the same authenticated cookie policy.
- Keeping the download inside WebView would preserve browser behavior but would bypass the requested native network path and make task management depend on WebView callbacks.
- Storing only in memory would make the account page lose completed records after process recreation, so task metadata is persisted while active transfers remain cancellable foreground work.

## Consequences

Native downloads follow the same routing policy as API and image requests. The app owns progress, cancellation, naming, and task history. Transfers are not resumed after process death; persisted in-progress records are marked failed on the next startup. Android 29+ writes user-visible files to Downloads without storage permission; older Android versions use the app-specific external Downloads directory.

## Testing

Unit tests cover filename sanitization, download-link classification, persisted task state transitions, request headers, cancellation, and streamed response handling with MockWebServer. Compose coverage verifies the account-page entry and task actions. Device validation should confirm DoH/ECH telemetry, notification progress, and the in-app completion fallback when notifications are disabled.
