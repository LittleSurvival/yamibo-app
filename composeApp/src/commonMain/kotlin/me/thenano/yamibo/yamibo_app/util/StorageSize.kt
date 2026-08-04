package me.thenano.yamibo.yamibo_app.util

import kotlin.math.roundToInt

private const val ONE_KB: Long = 1024L
private const val ONE_MB: Long = ONE_KB * 1024L
private const val ONE_GB: Long = ONE_MB * 1024L

fun formatStorageSize(size: Long): String {
    return formatByteSize(
        bytes = size,
        includeBytesUnit = false,
        oneDecimalMode = DecimalMode.RoundTwo,
        kilobyteLabel = "kB",
    )
}

fun formatDownloadedByteSize(bytes: Long): String {
    return formatByteSize(
        bytes = bytes,
        includeBytesUnit = true,
        oneDecimalMode = DecimalMode.TruncateOne,
        kilobyteLabel = "KB",
    )
}

private enum class DecimalMode {
    RoundTwo,
    TruncateOne,
}

private fun formatByteSize(
    bytes: Long,
    includeBytesUnit: Boolean,
    oneDecimalMode: DecimalMode,
    kilobyteLabel: String,
): String {
    if (includeBytesUnit && bytes < ONE_KB) return "$bytes B"
    return when {
        bytes >= ONE_GB -> "${formatDecimal(bytes / ONE_GB.toFloat(), oneDecimalMode)} GB"
        bytes >= ONE_MB -> "${formatDecimal(bytes / ONE_MB.toFloat(), oneDecimalMode)} MB"
        else -> "${formatDecimal(bytes / ONE_KB.toFloat(), oneDecimalMode)} $kilobyteLabel"
    }
}

private fun formatDecimal(value: Float, mode: DecimalMode): String = when (mode) {
    DecimalMode.RoundTwo -> ((value * 100).roundToInt() / 100f).toString()
    DecimalMode.TruncateOne -> (((value * 10).toInt() / 10.0).toString())
}
