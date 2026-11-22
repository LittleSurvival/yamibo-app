# Forum reading defaults and Smart Comic

## Agreed direction

Readers choose one of three opening modes for each forum, in Settings or the forum menu. Forum mode opens the existing discussion reader. Novel mode opens the existing novel detail page and its reader. Smart Comic opens the tapped chapter directly in the image reader with a directory of related threads, as if entering through an RSS or tag manga catalog. It uses the forum search without creating a subscription. Boards without a preference keep their existing opening behavior.

The first version seeds the directory from the selected board page and follows the forum search through its result pages. It compares work names and printed creator credits. Where a credit is missing it also checks the uploader. Each thread keeps its own ID. Numbered chapters sort by chapter and part, bundled chapters by their starting number, and unnumbered extras follow in thread-ID order. The directory can reverse its display order locally while the reader retains its current chapter and chronological next/previous behavior.

The directory cache is scoped to the signed-in account, board and work. A successful result is fresh for two hours. Older results remain readable during a refresh or after a network error. The directory provides manual refresh and retry. A successful empty search still preserves the selected thread.

## How this relates to downloads

A saved thread page already has a thread ID, page number and optional author ID. An absent author ID means all authors. A specific author ID identifies a filtered copy. The download manager can therefore contain two copies of the same thread, even though it offers no direct choice between them when starting a download there.

Opening an existing download should respect the author scope stored with it. Changing a forum preference affects future board opens and preserves both existing copies. Work-level offline downloads are a separate follow-up.

## Decisions still open

- Should a one-thread override persist? When a download starts, does it inherit the current view or offer an explicit choice?
- What evidence is sufficient to group chapter threads as one work? How should the directory order specials, handle similar titles by different authors, and let readers correct a mistaken match?
- When opening Smart Comic offline, which saved chapter should resume and how should unavailable chapters appear?

## Examples to verify before rollout

1. Assign different defaults to two forums, reopen the app and open a thread from each.
2. In 中文百合漫画区, open a middle installment of `DOUBLE HELIX BLOSSOM`. Compare the directory with multiple pages of results. Toggle the sort order and verify the highlighted thread and image stay the same.
3. Compare the bundled chapters and extras of `青梅竹马BIG LOVE` with its thread titles. For `少女所不期望的英雄史诗` check decimal chapter numbers, and for `JK⇔杀手` check the numbered parts within each chapter.
4. Reopen a cached directory offline, refresh it, reconnect and retry. The selected chapter should stay readable throughout whenever its images are available locally.
5. Check unrelated works with similar titles. Alternate translations with different work names and ambiguous titles still need a way to review or correct matches.
