package me.thenano.yamibo.yamibo_app.store

import io.github.littlesurvival.dto.page.ProfilePage
import io.github.littlesurvival.dto.model.TimeInfo
import io.github.littlesurvival.dto.value.FormHash
import io.github.littlesurvival.dto.value.UserId
import me.thenano.yamibo.yamibo_app.store.auth.UserStore
import platform.Foundation.NSUserDefaults

class IOSUserStore : UserStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun save(userInfo: ProfilePage) {
        if (defaults.integerForKey("uid").toInt() != userInfo.uid.value) UserStore.accountChanged()
        defaults.setInteger(userInfo.uid.value.toLong(), forKey = "uid")
        defaults.setObject(userInfo.username, forKey = "username")
        defaults.setObject(userInfo.userGroup, forKey = "userGroup")
        defaults.setInteger(userInfo.points.toLong(), forKey = "points")
        defaults.setInteger(userInfo.partner.toLong(), forKey = "partner")
        defaults.setInteger(userInfo.totalPoints.toLong(), forKey = "totalPoints")
        defaults.setObject(userInfo.avatarUrl, forKey = "avatarUrl")
        defaults.setObject(userInfo.avatarBackgroundUrl, forKey = "avatarBackgroundUrl")
        defaults.setObject(userInfo.gender, forKey = "gender")
        defaults.setObject(userInfo.birthday, forKey = "birthday")
        defaults.setInteger(userInfo.onlineHours.toLong(), forKey = "onlineHours")
        defaults.setObject(userInfo.registerTime?.specialText ?: userInfo.registerTime?.text, forKey = "registerTime")
        defaults.setObject(userInfo.lastVisit?.specialText ?: userInfo.lastVisit?.text, forKey = "lastVisit")
        defaults.setObject(userInfo.formHash?.value, forKey = "formHash")
    }

    override fun load(): ProfilePage? {
        val uidLong = defaults.integerForKey("uid")
        if (uidLong == 0L && defaults.objectForKey("uid") == null) return null
        val uid = uidLong.toInt()

        val username = defaults.stringForKey("username") ?: return null
        val userGroup = defaults.stringForKey("userGroup") ?: return null
        val points = defaults.integerForKey("points").toInt()
        val partner = defaults.integerForKey("partner").toInt()
        val totalPoints = defaults.integerForKey("totalPoints").toInt()
        val avatarUrl = defaults.stringForKey("avatarUrl")
        val avatarBackgroundUrl = defaults.stringForKey("avatarBackgroundUrl")
        val gender = defaults.stringForKey("gender")
        val birthday = defaults.stringForKey("birthday")
        val onlineHours = defaults.integerForKey("onlineHours").toInt()
        val registerTime = defaults.stringForKey("registerTime")
        val lastVisit = defaults.stringForKey("lastVisit")
        val formHash = defaults.stringForKey("formHash")

        return ProfilePage(
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

    override fun clear() {
        if (defaults.objectForKey("uid") != null) UserStore.accountChanged()
        listOf(
            "uid",
            "username",
            "userGroup",
            "points",
            "partner",
            "totalPoints",
            "avatarUrl",
            "avatarBackgroundUrl",
            "gender",
            "birthday",
            "onlineHours",
            "registerTime",
            "lastVisit",
            "formHash"
        )
            .forEach { defaults.removeObjectForKey(it) }
    }
}
