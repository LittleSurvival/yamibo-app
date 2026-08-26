package me.thenano.yamibo.yamibo_app.repository.notification

import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

class MessageNotificationDeliveryStateStore(
    private val settingsStore: SettingsStore,
) {
    data class DailyState(
        val deliveredCount: Int,
        val muted: Boolean,
    )

    fun stateFor(userId: Int, localDate: String): DailyState {
        val prefix = keyPrefix(userId)
        val deliveryDate = settingsStore.getString("$prefix.delivery_date", "")
        val deliveredCount = if (deliveryDate == localDate) {
            settingsStore.getInt("$prefix.delivery_count", 0).coerceAtLeast(0)
        } else {
            0
        }
        return DailyState(
            deliveredCount = deliveredCount,
            muted = settingsStore.getString("$prefix.muted_date", "") == localDate,
        )
    }

    fun recordDelivery(userId: Int, localDate: String) {
        val prefix = keyPrefix(userId)
        val previousDate = settingsStore.getString("$prefix.delivery_date", "")
        val previousCount = if (previousDate == localDate) {
            settingsStore.getInt("$prefix.delivery_count", 0).coerceAtLeast(0)
        } else {
            0
        }
        settingsStore.putString("$prefix.delivery_date", localDate)
        settingsStore.putInt("$prefix.delivery_count", previousCount + 1)
    }

    fun muteToday(userId: Int, localDate: String) {
        settingsStore.putString("${keyPrefix(userId)}.muted_date", localDate)
    }

    private fun keyPrefix(userId: Int): String = "$KEY_PREFIX.$userId"

    private companion object {
        const val KEY_PREFIX = "message_notifications.runtime"
    }
}
