package me.thenano.yamibo.yamibo_app.repository.appsync.remote

internal data class AppSyncPayloadMeasurement(
    val encodedChars: Int,
    val targetChars: Int,
    val hardLimitChars: Int,
) {
    val fitsTarget: Boolean get() = encodedChars <= targetChars
    val fitsHardLimit: Boolean get() = encodedChars <= hardLimitChars

    fun redactedDiagnostic(): String =
        "encodedChars=$encodedChars,targetChars=$targetChars,hardLimitChars=$hardLimitChars"
}

/** All provider body decisions must measure the final string through this object. */
internal class AppSyncPayloadBudget(
    rolloutTargetChars: Int = DEFAULT_TARGET_CHARS,
) {
    val targetChars: Int = rolloutTargetChars.also {
        require(it in MINIMUM_TARGET_CHARS..HARD_LIMIT_CHARS) {
            "AppSync payload target must be between $MINIMUM_TARGET_CHARS and $HARD_LIMIT_CHARS"
        }
    }

    fun measure(finalBody: String): AppSyncPayloadMeasurement = AppSyncPayloadMeasurement(
        encodedChars = finalBody.length,
        targetChars = targetChars,
        hardLimitChars = HARD_LIMIT_CHARS,
    )

    fun requireWithinTarget(finalBody: String) {
        val measurement = measure(finalBody)
        require(measurement.fitsTarget) {
            "AppSync body exceeds target (${measurement.redactedDiagnostic()})"
        }
    }

    companion object {
        const val HARD_LIMIT_CHARS: Int = 50_000
        const val DEFAULT_TARGET_CHARS: Int = 42_000
        private const val MINIMUM_TARGET_CHARS: Int = 4_096
    }
}
