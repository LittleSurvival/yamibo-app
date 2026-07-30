# Sync Domain Inventory

This inventory is exhaustive for `YamiboBackupFile` schema version 1. Metadata fields
`schemaVersion`, `appVersionCode`, and `createdAt` describe an export/checkpoint and never
become user-data operations.

| Backup model | Domain | Stable entity id | Fields | Operations | Conflict policy | Delete authority |
|---|---|---|---|---|---|---|
| `BackupSetting` | `settings` | `key` | `type`, `value` | Put/Patch/Delete | per-key field register | explicit setting reset only |
| `BackupFavoriteCategory` | `favorite.category` | generated `syncId` | `name`, `sortOrder`, `createdAt`, `updatedAt`; `localId` is projection-only | Put/Patch/Delete | per-field register, generation tombstone | explicit category delete |
| `BackupFavoriteCollection` | `favorite.collection` | generated `syncId` | `categorySyncId`, `name`, `colorKey`, `sortOrder`, `createdAt`, `updatedAt`; local ids are projection-only | Put/Patch/Delete | per-field register, generation tombstone | explicit collection delete |
| `BackupFavoriteItem` | `favorite.item` | `targetType|targetId|authorId` | `title`, `coverUrl`, `lastUpdatedTime`, `forumId`, `forumName`, `createdAt`, `lastFavoriteStatusUpdateAt` | Put/Patch/Delete | per-field register | explicit unfavorite/delete |
| `BackupFavoriteItemCategory` | `favorite.item-category` | item stable id + category `syncId` | `createdAt` and relation identity | RelationAdd/RelationRemove | concurrent remove-wins | explicit membership removal |
| `BackupFavoriteItemCollection` | `favorite.item-collection` | item stable id + collection `syncId` | `createdAt` and relation identity | RelationAdd/RelationRemove | concurrent remove-wins | explicit membership removal |
| `BackupDetailNote` | `detail-note` | `targetType|targetId|authorId` | `content`, `createdAt`, `updatedAt` | Put/Patch/Delete | per-field register | explicit note delete |
| `BackupBookMark` | `bookmark` | `targetType|parentId|targetId` | `title`, `bookmarked`, `read`, `createdAt`, `updatedAt` | Put/Patch/Delete | per-field register | explicit bookmark delete |
| `BackupThreadReadingHistory` | `reading.thread` | `threadId|threadType|authorId|historyOrigin` | `threadName`, `threadCover`, `forumName`, `forumId`, `page`, `postId`, `postTitle`, `anchorPostId`, `anchorPostRatio`, `anchorBlockId`, `anchorBlockType`, `anchorBlockRatio`, `globalScrollY`, `viewportHeight`, `firstVisibleItemIndex`, `firstVisibleItemOffset`, `lastVisitTime`, `lastUpdatedTime` | Put/Patch/Delete | causal record winner; operation-id tie for concurrent aggregate positions | explicit history delete |
| `BackupImageReadingHistory` | `reading.image` | `postId` | `threadId`, `pageIndex`, `totalPages`, `firstVisibleItemIndex`, `firstVisibleItemOffset`, `lastVisitTime` | Put/Patch/Delete | causal record winner; operation-id tie for concurrent aggregate positions | explicit history delete |
| `BackupTagMangaReadingHistory` | `reading.tag-manga` | `tagId` | `tagName`, `tagPage`, `threadId`, `threadTitle`, `threadImagePageIndex`, `threadImageTotalPages`, `firstVisibleItemIndex`, `firstVisibleItemOffset`, `lastVisitTime`, `coverUrl` | Put/Patch/Delete | causal record winner; operation-id tie for concurrent aggregate positions | explicit history delete |
| `BackupReadingTimeStat` | `reading.time` | `dateKey` | `durationMillis`, `updatedAt` | Put/Patch/Delete | concurrent `durationMillis` maximum, otherwise deterministic register | explicit statistics delete |

Absence from a backup, reset database, logout, account change, failed read, failed migration,
or unsupported schema has no deletion authority. `BackupModels` is used for manual recovery and
verified checkpoints only; routine sync never diffs snapshots.
