# Agent Note: Nested reply dialog shows replies only

Status: implemented

## Problem

The nested-reply dialog repeated the original floor as its first card, even though the dialog is opened from that floor's reply preview. This duplicated the same content and made the dialog's reply list appear to contain an extra reply.

## Decision

Keep the original root post in the thread model for attribution, pagination, and loading requests, but render only `thread.replies` inside the dialog. The first reply has no leading divider; later replies keep separators. Root lookup, `replyAuthorLabel`, continuation loading, and the original timeline entry remain unchanged, so direct replies still display the root author and replying to the original floor remains available from the main timeline.

## Existing note audit

- [Nested reply actions](./2026-10-14-nested-reply-actions.md) still defines the like, long-press reaction, reply, and floor-jump behavior for each rendered reply. This note narrows the dialog's rendered set and supersedes its earlier statement that the root card is rendered alongside children.
- [Reply timeline and preview](./2026-10-10-reply-timeline-and-preview.md) continues to own the main timeline, preview, read-state, and navigation boundaries.
- [Nested reply attribution](./2026-10-14-nested-reply-attribution.md) continues to own root-relative attribution and floor-jump labels.

## Alternatives considered

- Keep the root card and visually mark it as the original: this preserves the duplicated content and still makes the reply count look wrong.
- Remove the root from the thread data: this breaks parent attribution, cursor-based continuation, and root-scoped loading.
- Render a root reply button in the dialog only: the requested dialog content is replies only; the original floor already has its own reply entry in the main timeline.

## Testing

`NestedReplyActionsUiTest.discussionDoesNotRepeatTheOriginalComment` failed on the previous implementation because `thread-preview-post-2` was present. After the change, all 10 tests in `NestedReplyActionsUiTest` pass, including likes, long-press reactions, reply targeting, attribution, ignored replies, and narrow large-font layout. `TopicContinuationUiTest` passes all 4 tests after its footer index was updated for the removed root item.

## Consequences

The dialog starts with the first actual reply and has no orphan divider above it. The root is still retained in memory and in the reply-load key, so pagination and attribution contracts are unchanged. Screens with no replies still show the existing loading/error/empty states rather than a root card.
