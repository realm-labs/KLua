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

internal class LuaUserData(
    val value: Any,
    private val typeResolver: ((Any) -> LuaUserDataType?)? = null,
) : LuaValue {
    val type: LuaUserDataType?
        get() = typeResolver?.invoke(value)
}

internal class LuaUserDataType(
    private val methods: Map<String, LuaNativeFunction>,
    private val properties: Map<String, LuaUserDataProperty>,
) {
    fun method(name: String): LuaNativeFunction? = methods[name]

    fun property(name: String): LuaUserDataProperty? = properties[name]
}

internal data class LuaUserDataProperty(
    val getter: LuaNativeFunction?,
    val setter: LuaNativeFunction?,
)

internal data class LuaClosure(
    val prototype: Prototype,
    val upvalues: List<LuaUpvalue> = emptyList(),
) : LuaValue

internal data class LuaNativeFunction(
    val function: (List<LuaValue>) -> List<LuaValue>,
) : LuaValue

internal class LuaUpvalue(
    var value: LuaValue,
)
