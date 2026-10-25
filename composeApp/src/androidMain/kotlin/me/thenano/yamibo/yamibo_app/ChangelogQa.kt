package me.thenano.yamibo.yamibo_app

import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.model.TimeInfo
import io.github.littlesurvival.dto.model.User
import io.github.littlesurvival.dto.page.PrivateMessage
import io.github.littlesurvival.dto.page.PrivateMessagePage
import io.github.littlesurvival.dto.page.PrivateMessageType
import io.github.littlesurvival.dto.value.*
import kotlinx.coroutines.delay
import me.thenano.yamibo.yamibo_app.repository.UserSpaceRepository
import me.thenano.yamibo.yamibo_app.repository.servernotice.*
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

// Local emulator adapter only. Every private-message operation is intercepted here.
internal fun changelogQaMessages(base: UserSpaceRepository, scenario: String): UserSpaceRepository {
    val messages = mutableListOf(PrivateMessage(User(UserId(-1), "LOCAL QA"), PrivateMessageType.Friend,
        "Local fixture: original message", TimeInfo("2026-10-26", epoch = 0)))
    var sent = false
    fun page() = PrivateMessagePage(UserId(-1), "LOCAL QA - no messages leave this device", PrivateMessageId(-1), messages.toList())
    return object : UserSpaceRepository by base {
        override fun getCachedPrivateMessagePage(toUser: UserId, page: Int?) = page()
        override suspend fun fetchPrivateMessagePage(toUser: UserId, page: Int?): YamiboResult<PrivateMessagePage> {
            android.util.Log.i("CHANGELOG_QA", "pm_fetch sent=$sent count=${messages.size}")
            delay(1500)
            return if (sent && scenario == "pm-refresh-error") YamiboResult.Failure("LOCAL QA refresh failure")
            else YamiboResult.Success(page())
        }
        override suspend fun sendPrivateMessage(privateMessageId: PrivateMessageId, toUser: UserId, message: String, formHash: FormHash): YamiboResult<String> {
            delay(200)
            sent = true
            messages += PrivateMessage(User(UserId(-2), "LOCAL SELF"), PrivateMessageType.Self, message, TimeInfo("2026-10-26", epoch = 0))
            android.util.Log.i("CHANGELOG_QA", "pm_send_local count=${messages.size}")
            return YamiboResult.Success("LOCAL QA sent")
        }
        override fun clearPrivateMessagePages(toUser: UserId) = Unit
        override fun clearMessagePages() = Unit
    }
}

internal suspend fun changelogQaNotice(scenario: String): String {
    val settings = object : SettingsStore {
        override fun getInt(key: String, defaultValue: Int) = defaultValue
        override fun putInt(key: String, value: Int) = Unit
        override fun getFloat(key: String, defaultValue: Float) = defaultValue
        override fun putFloat(key: String, value: Float) = Unit
        override fun getString(key: String, defaultValue: String) = defaultValue
        override fun putString(key: String, value: String) = Unit
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
        override fun putBoolean(key: String, value: Boolean) = Unit
        override fun remove(key: String) = Unit
        override fun hasKey(key: String) = false
    }
    val now = System.currentTimeMillis()
    val notices = DefaultServerNoticeRepository(
        store = ServerNoticeStore(settings),
        sources = listOf(ServerNoticeSource("old", "https://fixture.invalid/old"), ServerNoticeSource("new", "https://fixture.invalid/new")),
        now = { now },
        fetch = { source ->
            val revision = if (source.name == "new") 2 else 1
            val enabled = !(scenario == "524-cancelled" && revision == 2)
            val end = if (scenario == "524-expired") now - 1 else now + 60000
            """{"schemaVersion":1,"httpStatus":524,"revision":$revision,"enabled":$enabled,"startsAt":${now-60000},"expiresAt":$end,"message":"LOCAL QA: forum maintenance announcement"}"""
        },
    )
    val status = if (scenario == "524-other-status") 503 else 524
    val result = ForumErrorMessageResolver(notices).resolve("[HTTP $status] QA request failed\n  url    : https://bbs.yamibo.com/forum.php\n  body   : LOCAL FIXTURE")
    android.util.Log.i("CHANGELOG_QA", "notice $scenario => $result")
    return result
}

internal fun changelogQaThreads(base: me.thenano.yamibo.yamibo_app.repository.ThreadRepository): me.thenano.yamibo.yamibo_app.repository.ThreadRepository {
    var failed = false
    return object : me.thenano.yamibo.yamibo_app.repository.ThreadRepository by base {
        override fun getCachedThread(tid: ThreadId, authorId: UserId?, page: Int) =
            if (tid.value == 555382 && page == 3 && !failed) null else base.getCachedThread(tid, authorId, page)
        override suspend fun fetchThread(tid: ThreadId, authorId: UserId?, page: Int, reverse: Boolean): YamiboResult<io.github.littlesurvival.dto.page.ThreadPage> {
            if (tid.value == 555382 && page == 3 && !failed) {
                delay(2500)
                failed = true
                return YamiboResult.Failure("LOCAL QA: one catalog request failed")
            }
            delay(2500)
            return base.fetchThread(tid, authorId, page, reverse)
        }
    }
}
