package me.thenano.yamibo.yamibo_app.store

import android.annotation.SuppressLint
import android.content.Context
import io.github.littlesurvival.dto.model.TimeInfo
import io.github.littlesurvival.dto.page.ProfilePage
import io.github.littlesurvival.dto.value.FormHash
import io.github.littlesurvival.dto.value.UserId
import me.thenano.yamibo.yamibo_app.store.auth.UserStore

class AndroidUserStore(context: Context) : UserStore {
    private val prefs = context.encryptedSharedPreferences(prefName)

    @SuppressLint("UseKtx")
    override fun save(userInfo: ProfilePage) {
        prefs.edit().apply {
            putInt("uid", userInfo.uid.value)
            putString("username", userInfo.username)
            putString("userGroup", userInfo.userGroup)
            putInt("points", userInfo.points)
            putInt("partner", userInfo.partner)
            putInt("totalPoints", userInfo.totalPoints)
            putString("avatarUrl", userInfo.avatarUrl)
            putString("avatarBackgroundUrl", userInfo.avatarBackgroundUrl)
            putString("gender", userInfo.gender)
            putString("birthday", userInfo.birthday)
            putInt("onlineHours", userInfo.onlineHours)
            putString("registerTime", userInfo.registerTime?.specialText ?: userInfo.registerTime?.text)
            putString("lastVisit", userInfo.lastVisit?.specialText ?: userInfo.lastVisit?.text)
            putString("formHash", userInfo.formHash?.value)
            apply()
        }
    }

    override fun load(): ProfilePage? =
        with(prefs) {
            val uid = getInt("uid", -1)
            if (uid == -1) return@with null

            val username = getString("username", null) ?: return@with null
            val userGroup = getString("userGroup", null) ?: return@with null
            val points = getInt("points", 0)
            val partner = getInt("partner", 0)
            val totalPoints = getInt("totalPoints", 0)
            val avatarUrl = getString("avatarUrl", null)
            val avatarBackgroundUrl = getString("avatarBackgroundUrl", null)
            val gender = getString("gender", null)
            val birthday = getString("birthday", null)
            val onlineHours = getInt("onlineHours", 0)
            val registerTime = getString("registerTime", null)
            val lastVisit = getString("lastVisit", null)
            val formHash = getString("formHash", null)

            ProfilePage(
                uid = UserId(uid),
                username = username,
                userGroup = userGroup,
                points = points,
                partner = partner,
                totalPoints = totalPoints,
                avatarUrl = avatarUrl,
                avatarBackgroundUrl = avatarBackgroundUrl,
                gender = gender,
                birthday = birthday,
                onlineHours = onlineHours,
                registerTime = registerTime?.let(TimeInfo::parse),
                lastVisit = lastVisit?.let(TimeInfo::parse),
                formHash = formHash?.let { FormHash(it) }
            )
        }

    @SuppressLint("UseKtx")
    override fun clear() {
        prefs.edit().clear().apply()
    }
}
