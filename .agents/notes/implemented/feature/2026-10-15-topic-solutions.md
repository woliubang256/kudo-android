# Agent Note: Topic solution summary and floor markers

Status: implemented

## Problem

Discourse Solved returns an `accepted_answer` summary in topic JSON, but the native topic model did not retain solution floors. The topic screen also had no summary entry at the end of the original post or marker on the matching floor. A solution floor may be outside the first page, so navigation must reuse the existing floor jump and pagination path.

## Decision

Store a small topic solution summary containing floor number, author and excerpt. Parse the standard `accepted_answer` object and tolerate arrays, site extension fields and a post-level `accepted_answer` boolean. Render a clickable solution list after the original post content. Each loaded solution floor displays a check marker beside its floor number. Clicks use the existing `navigatePost` and `jumpTo` callbacks.

Only valid positive floor numbers create entries. Missing excerpts fall back to the floor and any author data available from loaded posts. Entries are deduplicated and sorted by floor number.

## Alternatives considered

- A single solved label on the original post cannot represent multiple solutions or locate their floors.
- A separate solution endpoint would add requests and rate-limit pressure even though topic JSON already includes the standard summary.
- Copying solution state into every Post object would complicate pagination merges; the topic floor set plus the post boolean provide the needed compatibility.

## Consequences

Topics without solutions keep their existing rendering. A server summary can be shown before the target floor is loaded; the full post body still comes from the existing pagination and jump flow. Array and post-level site variants degrade to floor entries, while invalid entries are ignored.

## Testing

`SolutionParserTest` covers the standard object, multiple entries, post booleans, HTML-to-text excerpts and invalid floors. `TopicViewModelTest` covers promoting a solution discovered on a later page into the topic summary. `TopicDetailUiTest` covers multiple entries, navigation callbacks, loaded-floor markers, unloaded-floor entries and hidden empty state. The device tests were built with explicit version properties and installed with `adb install -r -t`.
