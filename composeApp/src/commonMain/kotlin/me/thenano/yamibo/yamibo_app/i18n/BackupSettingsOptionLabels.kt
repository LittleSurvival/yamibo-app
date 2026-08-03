package me.thenano.yamibo.yamibo_app.i18n

import me.thenano.yamibo.yamibo_app.repository.settings.BackupInterval

fun BackupInterval.localizedLabel(): String = when (this) {
    BackupInterval.NEVER -> i18n("永不")
    else -> requireNotNull(fixedInterval).localizedLabel()
}
