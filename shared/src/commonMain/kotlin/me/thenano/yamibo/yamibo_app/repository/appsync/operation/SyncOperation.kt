package me.thenano.yamibo.yamibo_app.repository.appsync.operation

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.random.Random

internal const val CURRENT_SYNC_OPERATION_SCHEMA_VERSION = 1

@Serializable
@JvmInline
value class SyncDeviceId(val value: String) {
    init {
        require(value.isNotBlank()) { "Device id cannot be blank" }
    }
}
@Serializable
@JvmInline
value class SyncDeviceEpoch(val value: String) {
    init {
        require(value.isNotBlank()) { "Device epoch cannot be blank" }
    }
}

@Serializable
@JvmInline
value class SyncWriterNonce(val value: String) {
    init {
        require(value.isNotBlank()) { "Writer nonce cannot be blank" }
    }
}

@Serializable
@JvmInline
value class SyncSequence(val value: Long) : Comparable<SyncSequence> {
    init {
        require(value > 0L) { "Sequence must be positive" }
    }

    override fun compareTo(other: SyncSequence): Int = value.compareTo(other.value)
}

@Serializable
@JvmInline
value class SyncOperationId(val value: String) : Comparable<SyncOperationId> {
    init {
        require(value.isNotBlank()) { "Operation id cannot be blank" }
    }

    override fun compareTo(other: SyncOperationId): Int = value.compareTo(other.value)
}

@Serializable
@JvmInline
value class SyncDomainId(val value: String) {
    init {
        require(DOMAIN_ID.matches(value)) { "Invalid sync domain id: $value" }
    }

    private companion object {
        val DOMAIN_ID = Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")
    }
}

@Serializable
@JvmInline
value class SyncEntityId(val value: String) {
    init {
        require(value.isNotBlank()) { "Entity id cannot be blank" }
    }
}

@Serializable
@JvmInline
value class SyncAccountBinding(val value: String) {
    init {
        require(value.isNotBlank()) { "Account binding cannot be blank" }
    }
}

@Serializable
enum class SyncOperationKind {
    Put,
    Patch,
    Delete,
    RelationAdd,
    RelationRemove,
}

@Serializable
enum class SyncOperationOrigin {
    UserAction,
    Migration,
    RemoteReplay,
}

@Serializable
data class SyncOperation(
    val operationId: SyncOperationId,
    val deviceId: SyncDeviceId,
    val deviceEpoch: SyncDeviceEpoch,
    val sequence: SyncSequence,
    val accountBinding: SyncAccountBinding,
    val domainId: SyncDomainId,
    val entityId: SyncEntityId,
    val entityGeneration: Long = 1L,
    val kind: SyncOperationKind,
    val fields: Map<String, String?> = emptyMap(),
    val causalContext: SyncCausalContext = SyncCausalContext(),
    val createdAtEpochMillis: Long,
    val origin: SyncOperationOrigin,
    val bulkDeleteAuthorizationId: String? = null,
    val schemaVersion: Int = CURRENT_SYNC_OPERATION_SCHEMA_VERSION,
) {
    init {
        require(entityGeneration > 0L) { "Entity generation must be positive" }
        require(schemaVersion == CURRENT_SYNC_OPERATION_SCHEMA_VERSION) {
            "Unsupported operation schema: $schemaVersion"
        }
        require(operationId == idFor(deviceId, deviceEpoch, sequence)) {
            "Operation id does not match its device epoch and sequence"
        }
        require(fields.keys.all(FIELD_NAME::matches)) { "Invalid operation field name" }
        when (kind) {
            SyncOperationKind.Delete,
            SyncOperationKind.RelationRemove,
            -> require(origin == SyncOperationOrigin.UserAction || origin == SyncOperationOrigin.RemoteReplay) {
                "Destructive operations require explicit user authority"
            }
            else -> Unit
        }
    }

    val replicaKey: SyncReplicaKey
        get() = SyncReplicaKey(deviceId, deviceEpoch)

    companion object {
        private val FIELD_NAME = Regex("[A-Za-z][A-Za-z0-9_.-]*")

        fun idFor(
            deviceId: SyncDeviceId,
            epoch: SyncDeviceEpoch,
            sequence: SyncSequence,
        ): SyncOperationId = SyncOperationId(
            "${deviceId.value}:${epoch.value}:${sequence.value.toString().padStart(20, '0')}",
        )
    }
}

internal object SyncIdentityGenerator {
    fun deviceId(random: Random = Random.Default): SyncDeviceId =
        SyncDeviceId(randomHex(random))

    fun deviceEpoch(random: Random = Random.Default): SyncDeviceEpoch =
        SyncDeviceEpoch(randomHex(random))

    fun writerNonce(random: Random = Random.Default): SyncWriterNonce =
        SyncWriterNonce(randomHex(random))

    fun stableEntityId(random: Random = Random.Default): SyncEntityId =
        SyncEntityId(randomHex(random))

    private fun randomHex(random: Random, byteCount: Int = 16): String =
        ByteArray(byteCount).also(random::nextBytes).joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
}
