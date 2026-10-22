package me.thenano.yamibo.yamibo_app.network

import android.content.Context
import me.thenano.yamibo.yamibo_app.repository.ServerNoticeRepository
import me.thenano.yamibo.yamibo_app.repository.servernotice.DefaultServerNoticeRepository
import me.thenano.yamibo.yamibo_app.repository.servernotice.ServerNoticeStore
import me.thenano.yamibo.yamibo_app.store.settings.AndroidSettingsStore

/** Foreground UI and workers share the same cache and in-flight refresh. */
internal object AndroidServerNoticeProvider {
    private var repository: ServerNoticeRepository? = null

    @Synchronized
    fun get(context: Context): ServerNoticeRepository = repository ?: DefaultServerNoticeRepository(
        ServerNoticeStore(AndroidSettingsStore(context.applicationContext)),
    ).also { repository = it }
}
