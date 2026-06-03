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
) : LuaValue

internal class LuaTableKeyException(
    message: String,
) : RuntimeException(message)

internal class LuaTable : LuaValue {
    private val values = mutableMapOf<LuaValue, LuaValue>()

    fun get(key: LuaValue): LuaValue {
        requireValidKey(key)
        return values[key] ?: LuaNil
    }

    fun set(key: LuaValue, value: LuaValue) {
        requireValidKey(key)
        if (value == LuaNil) {
            values.remove(key)
        } else {
            values[key] = value
        }
    }

    fun rawLength(): Long {
        var length = 0L
        while (length < Long.MAX_VALUE && get(LuaInteger(length + 1L)) != LuaNil) {
            length++
        }
        return length
    }

    private fun requireValidKey(key: LuaValue) {
        when {
            key == LuaNil -> throw LuaTableKeyException("table index is nil")
            key is LuaFloat && key.value.isNaN() -> throw LuaTableKeyException("table index is NaN")
        }
    }
}
