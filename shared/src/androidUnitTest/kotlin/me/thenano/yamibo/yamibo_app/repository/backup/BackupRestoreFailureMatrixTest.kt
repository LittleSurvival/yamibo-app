package me.thenano.yamibo.yamibo_app.repository.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.repository.BackupRepository

class BackupRestoreFailureMatrixTest {
    @Test
    fun sourceLoadFailureLeavesEveryPortableDomainUnchanged() = runBlocking {
        assertUnchangedAfterFailure { fixture ->
            fixture.repository.restoreBackup("missing", BackupRepository.RestoreMode.Overwrite)
        }
    }

    @Test
    fun validationFailureLeavesEveryPortableDomainUnchanged() = runBlocking {
        assertUnchangedAfterFailure { fixture ->
            val invalid = expandedBackup().copy(
                readingState = expandedBackup().readingState.copy(
                    chapterState = expandedBackup().readingState.chapterState.map {
                        it.copy(progressPercent = 101)
                    },
                ),
            )
            fixture.put("invalid", invalid)
            fixture.repository.restoreBackup("invalid", BackupRepository.RestoreMode.Overwrite)
        }
    }

    @Test
    fun failureAfterClearRollsBackSqlAndSettings() = runBlocking {
        assertUnchangedAfterFailure { fixture ->
            fixture.put("import", expandedBackup(progressTime = 900))
            fixture.repositoryWithInjector { phase ->
                if (phase == "after-clear") error("injected after clear")
            }.restoreBackup("import", BackupRepository.RestoreMode.Overwrite)
        }
    }

    @Test
    fun failureAfterDomainWritesRollsBackSqlAndSettings() = runBlocking {
        assertUnchangedAfterFailure { fixture ->
            fixture.put("import", expandedBackup(progressTime = 900))
            fixture.repositoryWithInjector { phase ->
                if (phase == "before-commit") error("injected after domain writes")
            }.restoreBackup("import", BackupRepository.RestoreMode.Overwrite)
        }
    }

    @Test
    fun settingsApplyFailureRollsBackSqlAndPreRestoreSettings() = runBlocking {
        assertUnchangedAfterFailure { fixture ->
            fixture.put("import", expandedBackup(progressTime = 900))
            fixture.settings.failNextPutKey = "validation.count"
            fixture.repository.restoreBackup("import", BackupRepository.RestoreMode.Overwrite)
        }
    }

    private suspend fun assertUnchangedAfterFailure(
        operation: suspend (BackupValidationHarness) -> Result<BackupRepository.RestoreSummary>,
    ) {
        val fixture = BackupValidationHarness()
        fixture.put("baseline", expandedBackup())
        fixture.repository.restoreBackup("baseline", BackupRepository.RestoreMode.Overwrite).getOrThrow()
        val before = fixture.repository.createSnapshot(1_000, PortableSnapshotScope.LocalBackup)

        val result = operation(fixture)

        assertTrue(result.isFailure)
        val after = fixture.repository.createSnapshot(1_000, PortableSnapshotScope.LocalBackup)
        assertEquals(before, after)
    }
}
