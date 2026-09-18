package me.thenano.yamibo.yamibo_app.repository.appsync.schema

/** Wire tags are explicit, not enum ordinals. Never renumber or reuse a tag. */
internal enum class AppSyncValueType(val tag: Int) {
    Text(1), Identifier(2), Enum(3), Integer(4), Decimal(5), Boolean(6),
}

internal sealed interface AppSyncCanonicalValue {
    data object Null : AppSyncCanonicalValue
    data class Text(val value: String) : AppSyncCanonicalValue
    data class Identifier(val value: String) : AppSyncCanonicalValue
    data class Enum(val value: String) : AppSyncCanonicalValue
    data class Integer(val value: Long) : AppSyncCanonicalValue
    data class Decimal(val value: Double) : AppSyncCanonicalValue {
        init { require(value.isFinite() && (value != 0.0 || value.toBits() == 0L)) { "Noncanonical decimal" } }
    }
    data class Boolean(val value: kotlin.Boolean) : AppSyncCanonicalValue

    fun legacyValue(): String? = when (this) {
        Null -> null
        is Text -> value
        is Identifier -> value
        is Enum -> value
        is Integer -> value.toString()
        is Decimal -> value.toString()
        is Boolean -> value.toString()
    }
}

internal fun parseCanonicalValue(type: AppSyncValueType, value: String): AppSyncCanonicalValue? = when (type) {
    AppSyncValueType.Text -> AppSyncCanonicalValue.Text(value)
    AppSyncValueType.Identifier -> value.takeIf(String::isNotBlank)?.let(AppSyncCanonicalValue::Identifier)
    AppSyncValueType.Enum -> value.takeIf { it.isNotBlank() && it.none(Char::isWhitespace) }
        ?.let(AppSyncCanonicalValue::Enum)
    AppSyncValueType.Integer -> value.toLongOrNull()?.let(AppSyncCanonicalValue::Integer)
    AppSyncValueType.Decimal -> value.toDoubleOrNull()?.takeIf(Double::isFinite)?.let {
        AppSyncCanonicalValue.Decimal(if (it == 0.0) 0.0 else it)
    }
    AppSyncValueType.Boolean -> value.toBooleanStrictOrNull()?.let(AppSyncCanonicalValue::Boolean)
}
