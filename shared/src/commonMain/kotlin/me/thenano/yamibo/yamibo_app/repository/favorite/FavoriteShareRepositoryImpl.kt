package me.thenano.yamibo.yamibo_app.repository.favorite

import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.value.ForumId
import io.github.littlesurvival.dto.value.TagId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.repository.FavoriteShareRepository
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository.FavoriteTargetType
import me.thenano.yamibo.yamibo_app.repository.RssSearchSubscriptionRepository
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis

class FavoriteShareRepositoryImpl(
    private val favoriteRepository: FavoriteStoreRepository,
    private val rssRepository: RssSearchSubscriptionRepository,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        prettyPrint = true
    },
) : FavoriteShareRepository {
    override suspend fun export(selection: FavoriteShareRepository.ExportSelection): FavoriteShareRepository.Package {
        favoriteRepository.ensureDefaults()
        val selectedIds = selection.categoryIds
        require(selectedIds.isNotEmpty()) { "請選擇要分享的收藏夾" }

        val categories = favoriteRepository.getCategories().filter { it.id in selectedIds }
        require(categories.isNotEmpty()) { "找不到可分享的收藏夾" }

        val sharedFolders = categories.map { category ->
            val content = favoriteRepository.getCategoryContent(category.id)
            val items = (content.directItems + content.collections.flatMap { it.items })
                .distinctBy { it.id }
                .map { it.toShareItem() }
            FavoriteShareRepository.Folder(
                name = category.name,
                items = items,
            )
        }

        return FavoriteShareRepository.Package(
            exportedAt = currentTimeMillis(),
            folders = sharedFolders,
        )
    }

    override fun encode(packageData: FavoriteShareRepository.Package): String {
        validatePackageShape(packageData)
        return json.encodeToString(packageData)
    }

    override fun decode(jsonText: String): FavoriteShareRepository.Package {
        val packageData = try {
            json.decodeFromString<FavoriteShareRepository.Package>(jsonText.stripUtf8Bom())
        } catch (error: SerializationException) {
            throw IllegalArgumentException("收藏分享檔案格式錯誤：${error.message}")
        }
        validatePackageShape(packageData)
        return packageData
    }

    private fun String.stripUtf8Bom(): String =
        if (startsWith('\uFEFF')) substring(1) else this

    override suspend fun previewImport(jsonText: String): FavoriteShareRepository.ImportPreview {
        val packageData = decode(jsonText)
        val previews = packageData.folders.map { folder ->
            val analyzed = folder.items.map { analyzeItem(it) }
            FavoriteShareRepository.FolderPreview(
                name = folder.name,
                itemCount = folder.items.size,
                duplicateCount = analyzed.count { it is ItemAnalysis.Valid && it.existingItem != null },
                unsupportedCount = analyzed.count { it is ItemAnalysis.Unsupported },
                invalidCount = analyzed.count { it is ItemAnalysis.Invalid },
            )
        }
        return FavoriteShareRepository.ImportPreview(
            folderCount = packageData.folders.size,
            itemCount = previews.sumOf { it.itemCount },
            duplicateCount = previews.sumOf { it.duplicateCount },
            unsupportedCount = previews.sumOf { it.unsupportedCount },
            invalidCount = previews.sumOf { it.invalidCount },
            folders = previews,
        )
    }

    override suspend fun importFavorites(
        jsonText: String,
        target: FavoriteShareRepository.ImportTarget,
    ): FavoriteShareRepository.ImportResult {
        val packageData = decode(jsonText)
        return when (target.mode) {
            FavoriteShareRepository.ImportMode.CreateFolders -> importAsNewFolders(packageData)
            FavoriteShareRepository.ImportMode.AddToExistingFolders -> {
                require(target.categoryIds.isNotEmpty()) { "請選擇要載入到哪個收藏夾" }
                importIntoExistingFolders(packageData, target.categoryIds)
            }
        }
    }

    private suspend fun importAsNewFolders(
        packageData: FavoriteShareRepository.Package,
    ): FavoriteShareRepository.ImportResult {
        var createdFolders = 0
        var createdItems = 0
        var reusedItems = 0
        var unsupported = 0
        var invalid = 0

        packageData.folders.forEach { sharedFolder ->
            val category = favoriteRepository.createCategory(uniqueCategoryName(sharedFolder.name))
            createdFolders += 1

            sharedFolder.items.forEach { item ->
                when (val analysis = analyzeItem(item)) {
                    is ItemAnalysis.Invalid -> invalid += 1
                    is ItemAnalysis.Unsupported -> unsupported += 1
                    is ItemAnalysis.Valid -> {
                        if (analysis.existingItem == null) {
                            createFavoriteItem(item, analysis, categoryIds = listOf(category.id))
                            createdItems += 1
                        } else {
                            favoriteRepository.addItemsToLocations(
                                itemIds = setOf(analysis.existingItem.id),
                                categoryIds = setOf(category.id),
                            )
                            reusedItems += 1
                        }
                    }
                }
            }
        }

        return FavoriteShareRepository.ImportResult(
            createdFolderCount = createdFolders,
            createdItemCount = createdItems,
            reusedItemCount = reusedItems,
            skippedDuplicateCount = 0,
            unsupportedCount = unsupported,
            invalidCount = invalid,
        )
    }

    private suspend fun importIntoExistingFolders(
        packageData: FavoriteShareRepository.Package,
        categoryIds: Set<Long>,
    ): FavoriteShareRepository.ImportResult {
        var createdItems = 0
        var skippedDuplicates = 0
        var unsupported = 0
        var invalid = 0

        packageData.folders.flatMap { it.items }.distinctBy { itemKey(it) }.forEach { item ->
            when (val analysis = analyzeItem(item)) {
                is ItemAnalysis.Invalid -> invalid += 1
                is ItemAnalysis.Unsupported -> unsupported += 1
                is ItemAnalysis.Valid -> {
                    if (analysis.existingItem != null) {
                        skippedDuplicates += 1
                    } else {
                        createFavoriteItem(item, analysis, categoryIds = categoryIds.toList())
                        createdItems += 1
                    }
                }
            }
        }

        return FavoriteShareRepository.ImportResult(
            createdFolderCount = 0,
            createdItemCount = createdItems,
            reusedItemCount = 0,
            skippedDuplicateCount = skippedDuplicates,
            unsupportedCount = unsupported,
            invalidCount = invalid,
        )
    }

    private suspend fun FavoriteStoreRepository.FavoriteItem.toShareItem(): FavoriteShareRepository.Item {
        val rssSubscription = if (targetType == FavoriteTargetType.RssSearch) {
            rssRepository.getSubscription(targetId)
        } else {
            null
        }
        return FavoriteShareRepository.Item(
            targetType = targetType.name,
            targetId = targetId,
            authorId = authorId?.value?.toLong(),
            title = title,
            coverUrl = coverUrl,
            lastUpdatedTime = lastUpdatedTime,
            forumId = forumId?.value?.toLong(),
            forumName = forumName,
            rssQuery = rssSubscription?.query,
            rssForumId = rssSubscription?.forumId?.value?.toLong(),
            rssForumName = rssSubscription?.forumName,
        )
    }

    private suspend fun analyzeItem(item: FavoriteShareRepository.Item): ItemAnalysis {
        val title = item.title.trim()
        if (title.isBlank()) return ItemAnalysis.Invalid
        val targetId = item.targetId.takeIf { it > 0 } ?: return ItemAnalysis.Invalid
        val type = FavoriteTargetType.entries.firstOrNull { it.name == item.targetType }
            ?: return ItemAnalysis.Unsupported
        val authorId = item.authorId?.takeIf { it > 0 }?.toInt()?.let(::UserId)
        val existing = when (type) {
            FavoriteTargetType.RssSearch -> {
                val query = item.rssQuery?.trim()?.takeIf { it.isNotBlank() }
                    ?: title.takeIf { it.isNotBlank() }
                    ?: return ItemAnalysis.Invalid
                val rssForumId = item.rssForumId?.takeIf { it > 0 }?.toInt()?.let(::ForumId)
                val localSubscription = rssRepository.findBySearch(query, rssForumId)
                localSubscription?.let {
                    favoriteRepository.getFavoriteItem(FavoriteTargetType.RssSearch, it.id)
                }
            }
            else -> favoriteRepository.getFavoriteItem(type, targetId, authorId)
        }
        return ItemAnalysis.Valid(type, existing)
    }

    private suspend fun createFavoriteItem(
        item: FavoriteShareRepository.Item,
        analysis: ItemAnalysis.Valid,
        categoryIds: List<Long> = emptyList(),
        collectionIds: List<Long> = emptyList(),
    ): Long {
        when (analysis.targetType) {
            FavoriteTargetType.ThreadNormal -> favoriteRepository.addNormalThreadFavorite(
                tid = ThreadId(item.targetId.toInt()),
                title = item.title.trim(),
                coverUrl = item.coverUrl,
                lastUpdatedTime = item.lastUpdatedTime,
                forumId = item.forumId?.takeIf { it > 0 }?.toInt()?.let(::ForumId),
                forumName = item.forumName,
                categoryIds = categoryIds,
                collectionIds = collectionIds,
            )
            FavoriteTargetType.ThreadNovel -> favoriteRepository.addNovelThreadFavorite(
                tid = ThreadId(item.targetId.toInt()),
                title = item.title.trim(),
                authorId = item.authorId?.takeIf { it > 0 }?.toInt()?.let(::UserId),
                coverUrl = item.coverUrl,
                lastUpdatedTime = item.lastUpdatedTime,
                forumId = item.forumId?.takeIf { it > 0 }?.toInt()?.let(::ForumId),
                forumName = item.forumName,
                categoryIds = categoryIds,
                collectionIds = collectionIds,
            )
            FavoriteTargetType.TagManga -> favoriteRepository.addTagMangaFavorite(
                tagId = TagId(item.targetId.toInt()),
                tagName = item.title.trim(),
                coverUrl = item.coverUrl,
                categoryIds = categoryIds,
                collectionIds = collectionIds,
            )
            FavoriteTargetType.RssSearch -> {
                val query = item.rssQuery?.trim()?.takeIf { it.isNotBlank() } ?: item.title.trim()
                val rssForumId = item.rssForumId?.takeIf { it > 0 }?.toInt()?.let(::ForumId)
                val subscriptionId = when (val result = rssRepository.ensureSubscription(
                    query = query,
                    forumId = rssForumId,
                    forumName = item.rssForumName,
                )) {
                    is YamiboResult.Success -> result.value
                    else -> throw IllegalArgumentException(result.message())
                }
                favoriteRepository.addRssSearchFavorite(
                    subscriptionId = subscriptionId,
                    title = item.title.trim(),
                    coverUrl = item.coverUrl,
                    lastUpdatedTime = item.lastUpdatedTime,
                    categoryIds = categoryIds,
                    collectionIds = collectionIds,
                )
                return favoriteRepository.getFavoriteItem(FavoriteTargetType.RssSearch, subscriptionId)?.id
                    ?: throw IllegalStateException("RSS 收藏建立失敗")
            }
        }

        return favoriteRepository.getFavoriteItem(
            targetType = analysis.targetType,
            targetId = item.targetId,
            authorId = item.authorId?.takeIf { it > 0 }?.toInt()?.let(::UserId),
        )?.id ?: throw IllegalStateException("收藏建立失敗")
    }

    private suspend fun uniqueCategoryName(baseName: String): String {
        return uniqueName(baseName) { name ->
            favoriteRepository.getCategories().any { it.name.equals(name, ignoreCase = true) } ||
                favoriteRepository.getAllCollections().any { it.name.equals(name, ignoreCase = true) }
        }
    }

    private suspend fun uniqueName(baseName: String, exists: suspend (String) -> Boolean): String {
        val normalized = baseName.trim().ifBlank { "匯入收藏夾" }
        if (!exists(normalized)) return normalized
        var suffix = 2
        while (true) {
            val candidate = "$normalized ($suffix)"
            if (!exists(candidate)) return candidate
            suffix += 1
        }
    }

    private fun validatePackageShape(packageData: FavoriteShareRepository.Package) {
        require(packageData.schema == FavoriteShareRepository.Schema) { "不支援的收藏分享檔案：${packageData.schema}" }
        require(packageData.schemaVersion == FavoriteShareRepository.SchemaVersion) {
            "不支援的收藏分享版本：${packageData.schemaVersion}"
        }
        require(packageData.folders.isNotEmpty()) { "收藏分享檔案沒有收藏夾" }
        packageData.folders.forEachIndexed { index, folder ->
            require(folder.name.isNotBlank()) { "第 ${index + 1} 個收藏夾名稱為空白" }
        }
    }

    private fun itemKey(item: FavoriteShareRepository.Item): String {
        return listOf(item.targetType, item.targetId, item.authorId ?: "").joinToString("|")
    }

    private sealed interface ItemAnalysis {
        data class Valid(
            val targetType: FavoriteTargetType,
            val existingItem: FavoriteStoreRepository.FavoriteItem?,
        ) : ItemAnalysis
        data object Unsupported : ItemAnalysis
        data object Invalid : ItemAnalysis
    }

}
