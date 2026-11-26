package me.thenano.yamibo.yamibo_app.store.auth

import io.github.littlesurvival.dto.page.ProfilePage
import io.github.littlesurvival.dto.model.TimeInfo
import io.github.littlesurvival.dto.value.FormHash
import io.github.littlesurvival.dto.value.UserId

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

interface UserStore {
    val prefName: String
        get() = "session_store"

    fun load(): ProfilePage?
    fun save(userInfo: ProfilePage)
    fun clear()

    companion object {
        private val changes = MutableStateFlow(0L)
        val accountRevisions = changes.asStateFlow()
        val accountRevision: Long get() = changes.value
        internal fun accountChanged() = changes.update { it + 1 }

        val Preview =
            ProfilePage(
                uid = UserId(656626),
                username = "thenano",
                userGroup = "Baihe",
                adminGroup = null,
                points = 239,
                partner = 0,
                totalPoints = 239,
                avatarUrl =
                    "https://bbs.yamibo.com/uc_server/data/avatar/000/65/66/26_avatar_middle.jpg?ts=1725126833",
                avatarBackgroundUrl = null,
                signatureHtml = null,
                gender = "Private",
                birthday = "-",
                birthplace = null,
                education = null,
                customTitle = null,
                homepage = null,
                onlineHours = 172,
                registerTime = TimeInfo.parse("2024-8-14 20:23"),
                lastVisit = TimeInfo.parse("2026-2-24 00:49"),
                formHash = FormHash("dummy")
            )
    }
}
