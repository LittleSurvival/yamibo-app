package me.thenano.yamibo.yamibo_app.store.sign

import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.util.time.currentLocalDateKey
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis

data class SignStatusRecord(
    val dateKey: String,
    val isSigned: Boolean,
    val updatedAt: Long,
    val lastMessage: String?,
)

interface SignStatusStore {
    fun getToday(uid: String): SignStatusRecord?
    fun updateToday(uid: String, isSigned: Boolean, message: String? = null)
}

class DatabaseSignStatusStore(db: Database) : SignStatusStore {
    private val queries = db.signDailyRecordQueries

    private fun scopedKey(uid: String): String =
        signStatusStorageKey(uid, currentLocalDateKey())

    override fun getToday(uid: String): SignStatusRecord? =
        queries.getByDateKey(scopedKey(uid)).executeAsOneOrNull()?.let { record ->
            SignStatusRecord(
                dateKey = record.dateKey,
                isSigned = record.isSigned != 0L,
                updatedAt = record.updatedAt,
                lastMessage = record.lastMessage,
            )
        }

    override fun updateToday(uid: String, isSigned: Boolean, message: String?) {
        queries.upsert(
            dateKey = scopedKey(uid),
            isSigned = if (isSigned) 1L else 0L,
            updatedAt = currentTimeMillis(),
            lastMessage = message,
        )
    }
}

internal fun signStatusStorageKey(uid: String, localDateKey: String): String =
    "${uid}_${localDateKey}"
