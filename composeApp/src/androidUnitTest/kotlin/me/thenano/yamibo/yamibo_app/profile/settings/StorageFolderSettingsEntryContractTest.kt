package me.thenano.yamibo.yamibo_app.profile.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageFolderSettingsEntryContractTest {
    @Test
    fun storagePageOwnsTheSharedFolderPickerAndPersistence() {
        val source = source("profile/settings/SettingsCategoryScreen.kt")
        val settingsSource = source("profile/settings/SettingsScreen.kt")

        assertTrue(settingsSource.contains("下載、備份資料夾與緩存清理設定"))
        assertTrue(source.contains("rememberBackupFileActions("))
        assertTrue(source.contains("backupRepository.setSelectedFolder(uri)"))
        assertTrue(source.contains("SharedStorageFolderCard("))
        assertTrue(source.contains("onSelectFolder = folderActions.selectFolder"))
        assertTrue(source.contains("下載與備份資料夾"))
        assertTrue(source.contains("下載內容與本地備份共用此資料夾"))
        assertTrue(source.contains("if (folderLabel == null) \"選擇資料夾\" else \"變更資料夾\""))
    }

    @Test
    fun backupPageLinksToStorageInsteadOfSelectingTheFolderDirectly() {
        val source = source("profile/settings/backup/BackupSettingsScreen.kt")

        assertTrue(source.contains("ISettingsCategoryScreen(\"storage\")"))
        assertTrue(source.contains("onFolderSelected = {}"))
        assertTrue(source.contains("前往儲存空間"))
        assertFalse(source.contains("onClick = fileActions.selectFolder"))
        assertFalse(source.contains("repository.setSelectedFolder(uri)"))
    }

    @Test
    fun downloadPageAlwaysLinksItsFolderCardToStorage() {
        val source = source("profile/download/DownloadQueueScreen.kt")

        assertTrue(source.contains("navigator.navigate(ISettingsCategoryScreen(\"storage\"))"))
        assertTrue(source.contains("item { DownloadFolderSettingsCard(folderReady, onOpenStorageSettings) }"))
        assertTrue(source.contains("前往儲存空間"))
        assertFalse(source.contains("IBackupSettingsScreen"))
    }

    @Test
    fun missingDownloadFolderActionsLinkToStorageInsteadOfBackup() {
        val downloadEntrySources = listOf(
            "thread/reader/ThreadReaderScreen.kt",
            "favorite/FavoritePage.kt",
            "thread/detail/tag/TagDetailScreen.kt",
            "thread/detail/rss/RssSearchSubscriptionDetailScreen.kt",
        ).map(::source)

        downloadEntrySources.forEach { source ->
            assertTrue(source.contains("ISettingsCategoryScreen(\"storage\")"))
            assertFalse(source.contains("IBackupSettingsScreen"))
        }
    }

    private fun source(relativePath: String): String = repoRoot()
        .resolve("composeApp/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/$relativePath")
        .readText(Charsets.UTF_8)

    private fun repoRoot(): File =
        generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .first { it.resolve("settings.gradle.kts").isFile }
}
