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
