# Forum reading defaults and Smart Comic

## Agreed direction

Give readers a settings entry where they can choose how threads open in individual forums. The two choices discussed were opening the thread detail page and reading the thread author's posts only. A reader who wants replies in a discussion forum and only the author's installments in a fiction forum should be able to configure both.

Add a Smart Comic mode. The reference is YamiboX, which lets readers choose a mode per forum and groups chapter threads from the same work into a browsable directory. The grouping rules and editing controls for Yamibo are still open. [YamiboX reading features](https://github.com/Arkalin/YamiboX#功能概览)

## How this relates to downloads

A saved thread page already has a thread ID, page number and optional author ID. An absent author ID means all authors. A specific author ID identifies a filtered copy. The download manager can therefore contain two copies of the same thread, even though it offers no direct choice between them when starting a download there.

The proposed forum setting would give that distinction a visible entry point. Opening an existing download should respect the author scope stored with it. Changing a forum preference should affect future opens and preserve both existing copies. Smart Comic also needs to distinguish the identity of the work from the thread ID of each chapter so readers can reopen a downloaded chapter directly.

## Decisions still open

- What should a forum with no saved preference open? Can readers override the forum default for one thread, and should that choice persist?
- Should detail-page behavior and author scope be independent settings? When a download starts, does it inherit the current view or offer an explicit choice?
- What evidence is sufficient to group chapter threads as one work? How should the directory order specials, handle similar titles by different authors, and let readers correct a mistaken match?
- When opening Smart Comic offline, which saved chapter should resume and how should unavailable chapters appear?

## Examples to verify before rollout

1. Assign different defaults to two forums, reopen the app and open a thread from each.
2. Save the same page with all authors and with only the thread author. Change the forum preference, reopen both copies offline, then delete one and verify the other still opens.
3. Open a work split across several chapter threads alongside an unrelated work with a similar title. Check chapter order, resume position and the result of correcting a mistaken match.
