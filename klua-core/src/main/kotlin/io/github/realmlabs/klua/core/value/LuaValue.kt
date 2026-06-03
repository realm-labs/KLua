package io.github.realmlabs.klua.core.value

import io.github.realmlabs.klua.core.bytecode.Prototype

internal sealed interface LuaValue

internal data object LuaNil : LuaValue

internal data class LuaBoolean(
    val value: Boolean,
) : LuaValue

internal data class LuaInteger(
    val value: Long,
) : LuaValue

internal data class LuaFloat(
    val value: Double,
) : LuaValue

internal data class LuaString(
    val value: String,
) : LuaValue

internal data class LuaClosure(
    val prototype: Prototype,
    val upvalues: List<LuaUpvalue> = emptyList(),
) : LuaValue

internal class LuaUpvalue(
    var value: LuaValue,
)

internal class LuaTableKeyException(
    message: String,
) : RuntimeException(message)

internal class LuaTable : LuaValue {
    private val values = mutableMapOf<LuaValue, LuaValue>()

    fun get(key: LuaValue): LuaValue {
        return values[canonicalKey(key)] ?: LuaNil
    }

    fun set(key: LuaValue, value: LuaValue) {
        val canonicalKey = canonicalKey(key)
        if (value == LuaNil) {
            values.remove(canonicalKey)
        } else {
            values[canonicalKey] = value
        }
    }

    fun rawLength(): Long {
        var length = 0L
        while (length < Long.MAX_VALUE && get(LuaInteger(length + 1L)) != LuaNil) {
            length++
        }
        return length
    }

    private fun canonicalKey(key: LuaValue): LuaValue {
        return when {
            key == LuaNil -> throw LuaTableKeyException("table index is nil")
            key is LuaFloat && key.value.isNaN() -> throw LuaTableKeyException("table index is NaN")
            key is LuaFloat && key.value.isIntegralLong() -> LuaInteger(key.value.toLong())
            else -> key
        }
    }
}

private const val LONG_MAX_EXCLUSIVE = 9223372036854775808.0

private fun Double.isIntegralLong(): Boolean {
    return java.lang.Double.isFinite(this) &&
        this % 1.0 == 0.0 &&
        this >= Long.MIN_VALUE.toDouble() &&
        this < LONG_MAX_EXCLUSIVE
}
