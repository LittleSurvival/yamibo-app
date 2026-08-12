package me.thenano.yamibo.yamibo_app.i18n

import me.thenano.yamibo.yamibo_app.util.time.FixedScheduleInterval
import me.thenano.yamibo.yamibo_app.util.time.FixedScheduleUnit

fun FixedScheduleInterval.localizedLabel(): String = when (displayUnit) {
    FixedScheduleUnit.Hours -> i18n("{} 小時", displayValue)
    FixedScheduleUnit.Days -> i18n("{} 天", displayValue)
    FixedScheduleUnit.Weeks -> i18n("{} 週", displayValue)
}
