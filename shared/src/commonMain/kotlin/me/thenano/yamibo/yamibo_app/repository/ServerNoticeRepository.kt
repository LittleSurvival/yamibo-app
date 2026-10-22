package me.thenano.yamibo.yamibo_app.repository

import kotlinx.coroutines.flow.StateFlow
import me.thenano.yamibo.yamibo_app.repository.servernotice.ServerNotice

/** Public outage notices are presentation only; they never change a request's result. */
interface ServerNoticeRepository {
    val notice: StateFlow<ServerNotice?>
    suspend fun refresh(): ServerNotice?
    suspend fun active524Message(): String?
}
