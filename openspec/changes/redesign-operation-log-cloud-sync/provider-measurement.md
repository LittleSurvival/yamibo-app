# Yamibo Provider Measurement

Measured on 2026-07-30 with the debug-only read probe on `emulator-5554`.
The report contains only counts, anonymous format kinds, body character counts,
and durations. It does not contain titles, blog ids, account ids, cookies,
FormHash values, or blog content.

## Observed fixture

- Initial authenticated `fetchUserSpaceMyBlogs(userId = null)`: 2,000 ms.
- AppSync private class: 4 summaries on 1 page; class-page read completed
  without pagination or rate failure.
- Authoritative reader reloads: 4/4 successful, 432-1,138 ms.
- New-format journal bodies: 5,375 and 24,901 reader-HTML characters.
- New-format advisory index body: 506 reader-HTML characters.
- Ignored legacy private blog body: 57,101 reader-HTML characters.
- A manual cycle with two pending setting operations verified both the updated
  journal and newly created index in 6,632 ms. Later cycles continued to update
  the journal/index pair without HTTP 429 or provider-rate failure.

These are practical lower bounds for the current account fixture, not claims
about Yamibo's undocumented hard limits. The retained legacy body proves that a
50,000-character conservative request tier has been stored successfully. It
does not justify the previous 900,000-character preflight cap.

## Selected safety limits

- Journal/checkpoint preflight cap: 50,000 encoded characters.
- Checkpoint creation starts at 64 acknowledged local operations instead of
  250, leaving room before current settings-heavy journals approach the
  evidence-backed cap.
- If real content exceeds the cap before acknowledgement-gated compaction can
  finish, synchronization pauses with typed storage pressure. It never drops
  acknowledged history or silently raises the provider limit.
- Discovery remains bounded at 100 pages. The live fixture exercised the
  one-page termination path; multi-page next-page and total-page traversal are
  covered by fake-provider tests.

The limit must be raised only after a separate bounded write/read/delete probe
successfully verifies the larger tier. A single-account fixture is not evidence
for a provider-wide maximum blog count or SLA.
