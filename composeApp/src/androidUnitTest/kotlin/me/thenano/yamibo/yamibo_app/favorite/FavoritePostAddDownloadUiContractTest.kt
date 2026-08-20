package me.thenano.yamibo.yamibo_app.favorite

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FavoritePostAddDownloadUiContractTest {
    @Test
    fun everyInteractiveFavoriteSourceUsesCreationOutcomeAndThePersistedGate() {
        val sources = listOf(
            "thread/reader/ThreadReaderScreen.kt",
            "thread/detail/novel/NovelThreadDetailScreen.kt",
            "thread/detail/tag/TagDetailScreen.kt",
            "thread/detail/rss/RssSearchSubscriptionDetailScreen.kt",
            "history/ReadHistoryPage.kt",
            "forum/search/SearchScreen.kt",
        ).map(::source)

        sources.forEach { text ->
            assertTrue(text.contains("favoriteAddDownloadPromptEnabled"))
            assertTrue(text.contains("FavoriteCreationOutcome.Created"))
        }
        assertTrue(sources.take(5).all { it.contains("saveFavoriteWithOutcome") })
        assertTrue(sources.last().contains("saveRssFavorite"))
    }

    @Test
    fun automaticSheetsExposeSuppressionButManualSheetsDoNot() {
        val suppression = source("thread/detail/components/DownloadPromptSuppression.kt")
        val readerSheet = source("thread/reader/ReaderDownloadSheet.kt")
        val catalogSheet = source("thread/detail/components/CatalogActions.kt")
        val threadSource = source("thread/reader/ThreadReaderScreen.kt")
        val tagSource = source("thread/detail/tag/TagDetailScreen.kt")
        val rssSource = source("thread/detail/rss/RssSearchSubscriptionDetailScreen.kt")

        assertTrue(suppression.contains("不要再詢問"))
        assertTrue(suppression.contains("可在收藏設定重新開啟。"))
        assertTrue(suppression.contains("checkedColor = colors.brownDeep"))
        assertTrue(suppression.contains("checkmarkColor = colors.creamSurface"))
        assertTrue(readerSheet.contains("doNotAskAgain: Boolean? = null"))
        assertTrue(catalogSheet.contains("doNotAskAgain: Boolean? = null"))
        listOf(threadSource, tagSource, rssSource).forEach { text ->
            assertTrue(text.contains("downloadSheetOpenedAfterFavorite = false"))
            assertTrue(text.contains("if (downloadSheetOpenedAfterFavorite)"))
        }
    }

    @Test
    fun existingManualDownloadLabelsAndOrderingRemainInSharedComponents() {
        val reader = source("thread/reader/ReaderDownloadSheet.kt")
        assertInOrder(
            reader,
            "下載目前頁",
            "下載完整 Thread",
            "下載除最後一頁的所有頁面",
            "清除目前頁下載",
            "清除整個 Thread 下載",
        )

        val tag = source("thread/detail/tag/TagDetailScreen.kt")
        val rss = source("thread/detail/rss/RssSearchSubscriptionDetailScreen.kt")
        listOf(tag, rss).forEach { text ->
            assertInOrder(text, "下載目前分頁", "下載全部分頁", "清除目前分頁下載")
        }
    }

    @Test
    fun syncAndSaveMustFinishBeforeTheAutomaticSheetCanOpen() {
        val thread = source("thread/reader/ThreadReaderScreen.kt")
        val novel = source("thread/detail/novel/NovelThreadDetailScreen.kt")
        val history = source("history/ReadHistoryPage.kt")

        listOf(thread, novel).forEach { text ->
            val sync = text.indexOf("completeSavedFavoriteSyncWithFeedback(")
            val open = text.indexOf("if (pendingFavoriteDownloadAfterSync", startIndex = sync)
            assertTrue(sync >= 0 && open > sync)
        }
        val historySync = history.indexOf("syncExistingFavoriteIfRequested(")
        val historyOpen = history.indexOf("if (pendingFavoritePostAddDownloadTarget == target", startIndex = historySync)
        assertTrue(historySync >= 0 && historyOpen > historySync)

        val actions = source("favorite/FavoriteActions.kt")
        val result = actions.indexOf("val result = withContext")
        val feedback = actions.indexOf("feedbackController.post(message", startIndex = result)
        val returned = actions.indexOf("return result", startIndex = feedback)
        assertTrue(result >= 0 && feedback > result && returned > feedback)
        assertFalse(actions.substring(returned).take(80).contains("showDownload"))
    }

    private fun assertInOrder(text: String, vararg labels: String) {
        var cursor = -1
        labels.forEach { label ->
            val next = text.indexOf(label, cursor + 1)
            assertTrue(next > cursor, "Expected '$label' after index $cursor")
            cursor = next
        }
    }

    private fun source(relative: String): String = repoRoot()
        .resolve("composeApp/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/$relative")
        .readText(Charsets.UTF_8)

    private fun repoRoot(): File =
        generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .first { it.resolve("settings.gradle.kts").isFile }
}
