package me.thenano.yamibo.yamibo_app.util

import kotlin.math.roundToInt

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
    if (includeBytesUnit && bytes < 1024L) return "$bytes B"
    return when {
        bytes >= 1024L * 1024L * 1024L -> "${formatDecimal(bytes / (1024f * 1024f * 1024f), oneDecimalMode)} GB"
        bytes >= 1024L * 1024L -> "${formatDecimal(bytes / (1024f * 1024f), oneDecimalMode)} MB"
        else -> "${formatDecimal(bytes / 1024f, oneDecimalMode)} $kilobyteLabel"
    }
}

private fun formatDecimal(value: Float, mode: DecimalMode): String = when (mode) {
    DecimalMode.RoundTwo -> ((value * 100).roundToInt() / 100f).toString()
    DecimalMode.TruncateOne -> (((value * 10).toInt() / 10.0).toString())
}
