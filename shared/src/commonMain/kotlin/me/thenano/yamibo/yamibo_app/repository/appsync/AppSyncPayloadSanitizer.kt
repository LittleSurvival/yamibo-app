package me.thenano.yamibo.yamibo_app.repository.appsync

import me.thenano.yamibo.yamibo_app.repository.backup.YamiboBackupFile

/**
 * AppSync must only transport remotely fetchable thread covers. Local data URIs are cache
 * payloads, not portable reading-history metadata, and can be large enough to overflow a journal.
 */
internal fun appSyncThreadCoverOrNull(value: String?): String? {
    return portableRemoteUrlOrNull(value)
}

internal fun YamiboBackupFile.withPortableAppSyncPayloads(): YamiboBackupFile = copy(
    settings = settings.filter { AppSyncPortabilityPolicy.isSettingPortable(it.key) },
    readingState = readingState.copy(
        threadHistory = readingState.threadHistory.map { history ->
            history.copy(threadCover = appSyncThreadCoverOrNull(history.threadCover))
        },
    ),
)
