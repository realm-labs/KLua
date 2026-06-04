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

internal class LuaNativeFunction(
    val yieldable: Boolean = false,
    private val contextualFunction: ((LuaNativeCallContext) -> List<LuaValue>)? = null,
    private val function: (List<LuaValue>) -> List<LuaValue>,
) : LuaValue {
    fun call(context: LuaNativeCallContext): List<LuaValue> {
        return contextualFunction?.invoke(context) ?: function(context.arguments)
    }

    fun withYieldable(yieldable: Boolean): LuaNativeFunction {
        return LuaNativeFunction(yieldable, contextualFunction, function)
    }

    fun copy(yieldable: Boolean = this.yieldable): LuaNativeFunction {
        return withYieldable(yieldable)
    }
}

internal data class LuaNativeCallContext(
    val arguments: List<LuaValue>,
    val luaFrames: List<LuaNativeStackFrame>,
    private val setLocalValue: (level: Int, index: Int, value: LuaValue) -> String? = { _, _, _ -> null },
) {
    fun setLocal(level: Int, index: Int, value: LuaValue): String? {
        return setLocalValue(level, index, value)
    }
}

internal data class LuaNativeStackFrame(
    val sourceName: String,
    val line: Int,
    val lineDefined: Int,
    val lastLineDefined: Int,
    val locals: List<LuaNativeLocalVariable> = emptyList(),
)

internal data class LuaNativeLocalVariable(
    val name: String,
    val value: LuaValue,
)

internal class LuaUpvalue(
    var value: LuaValue,
)
