package me.thenano.yamibo.yamibo_app.repository.appsync.operation

import kotlinx.serialization.Serializable

@Serializable
data class SyncReplicaKey(
    val deviceId: SyncDeviceId,
    val deviceEpoch: SyncDeviceEpoch,
) {
    val stableKey: String
        get() = "${deviceId.value}:${deviceEpoch.value}"
}

@Serializable
data class SyncCausalContext(
    private val highWatermarks: Map<String, Long> = emptyMap(),
) {
    init {
        require(highWatermarks.values.all { it >= 0L }) {
            "Causal high-watermarks cannot be negative"
        }
    }

    operator fun get(replica: SyncReplicaKey): Long =
        highWatermarks[replica.stableKey] ?: 0L

    fun includes(operation: SyncOperation): Boolean =
        this[operation.replicaKey] >= operation.sequence.value

    fun advance(replica: SyncReplicaKey, sequence: SyncSequence): SyncCausalContext =
        if (this[replica] >= sequence.value) {
            this
        } else {
            SyncCausalContext(highWatermarks + (replica.stableKey to sequence.value))
        }

    fun merge(other: SyncCausalContext): SyncCausalContext {
        val keys = highWatermarks.keys + other.highWatermarks.keys
        return SyncCausalContext(
            keys.associateWith { key ->
                maxOf(highWatermarks[key] ?: 0L, other.highWatermarks[key] ?: 0L)
            },
        )
    }

    fun asStableMap(): Map<String, Long> =
        highWatermarks.entries.sortedBy { it.key }.associate { it.toPair() }
}

internal enum class SyncCausalRelation {
    Before,
    After,
    Concurrent,
    Same,
}

internal fun compareCausally(
    left: SyncOperation,
    right: SyncOperation,
): SyncCausalRelation {
    if (left.operationId == right.operationId) return SyncCausalRelation.Same
    val rightObservedLeft = right.causalContext.includes(left)
    val leftObservedRight = left.causalContext.includes(right)
    return when {
        rightObservedLeft && !leftObservedRight -> SyncCausalRelation.Before
        leftObservedRight && !rightObservedLeft -> SyncCausalRelation.After
        else -> SyncCausalRelation.Concurrent
    }
}
