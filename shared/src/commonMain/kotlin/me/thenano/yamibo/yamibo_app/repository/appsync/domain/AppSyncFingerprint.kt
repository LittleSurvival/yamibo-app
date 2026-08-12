package me.thenano.yamibo.yamibo_app.repository.appsync.domain

/**
 * Stable corruption-detection fingerprint used by the cloud envelope.
 *
 * This is deliberately not described as encryption or a cryptographic MAC.
 */
internal fun stableAppSyncFingerprint(value: String): String {
    var hash = -0x340d631b7bdddcdbL
    value.encodeToByteArray().forEach { byte ->
        hash = hash xor (byte.toLong() and 0xffL)
        hash *= 0x100000001b3L
    }
    return hash.toULong().toString(16).padStart(16, '0')
}
