package me.thenano.yamibo.yamibo_app.task

import kotlin.jvm.JvmInline

@JvmInline
value class AppTaskKey(val value: String) {
    init {
        require(value.isNotBlank()) { "App task key must not be blank" }
    }

    fun withInstance(instanceId: String): AppTaskKey {
        require(instanceId.isNotBlank()) { "Parallel app tasks require a non-blank instance ID" }
        return AppTaskKey("$value#$instanceId")
    }
}

enum class AppTaskDuplicatePolicy {
    KeepExisting,
    ReplacePending,
    AllowParallel,
}
