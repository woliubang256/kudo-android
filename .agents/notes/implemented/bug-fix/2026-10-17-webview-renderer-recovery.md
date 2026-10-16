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