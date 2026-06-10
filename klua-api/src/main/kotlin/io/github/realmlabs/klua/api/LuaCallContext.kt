package io.github.realmlabs.klua.api

interface LuaCallContext {
    val argumentCount: Int

    val luaFrames: List<LuaStackFrame>
        get() = emptyList()

    fun isNil(index: Int): Boolean

    fun isNone(index: Int): Boolean

    fun isTable(index: Int): Boolean

    fun isTableValue(value: Any?): Boolean = false

    fun isFunctionValue(value: Any?): Boolean = value is LuaFunction

    fun valueTypeName(value: Any?): String {
        return when (value) {
            null -> "nil"
            is Boolean -> "boolean"
            is Byte,
            is Short,
            is Int,
            is Long,
            is Float,
            is Double,
            -> "number"
            is CharSequence -> "string"
            is LuaFunction -> "function"
            is Map<*, *> -> "table"
            is LuaTypedValue -> value.luaTypeName
            else -> "userdata"
        }
    }

    fun typeName(index: Int): String

    fun get(index: Int): Any?

    fun getLuaValue(index: Int): Any? = get(index)

    fun lessThan(leftIndex: Int, rightIndex: Int): Boolean? = null

    fun lessThanValues(left: Any?, right: Any?): Boolean? = null

    fun call(index: Int, arguments: List<Any?>): LuaReturn

    fun call(function: Any?, arguments: List<Any?>): LuaReturn

    fun yield(values: List<Any?>): Nothing

    fun load(source: String, chunkName: String): LuaReturn

    fun getFunctionDebugInfo(index: Int): LuaFunctionDebugInfo? = null

    fun getUpvalue(index: Int, upvalueIndex: Int): LuaReturn? = null

    fun getUpvalueId(index: Int, upvalueIndex: Int): Any? = null

    fun joinUpvalue(index: Int, upvalueIndex: Int, otherIndex: Int, otherUpvalueIndex: Int): Boolean = false

    fun setUpvalue(index: Int, upvalueIndex: Int, value: Any?): String? = null

    fun setLocal(level: Int, index: Int, value: Any?): String? = null

    fun setDebugHook(index: Int, mask: String, count: Int): Boolean = false

    fun getDebugHook(): LuaReturn = LuaReturn.of(null)

    fun getTable(index: Int): Any?

    fun getTableValue(index: Int, key: Any?): Any?

    fun getTableField(table: Any?, key: Any?): Any?

    fun setTableValue(index: Int, key: Any?, value: Any?)

    fun setTableField(table: Any?, key: Any?, value: Any?) {
        throw IllegalArgumentException("value is not a table")
    }

    fun getMetatable(index: Int): Any?

    fun getTableMetatable(table: Any?): Any? = null

    fun setMetatable(index: Int, metatable: Any?)

    fun nextTableEntry(index: Int, key: Any?): List<Any?>?

    fun tableLength(index: Int): Long?

    fun toBoolean(index: Int): Boolean

    fun toInteger(index: Int): Long?

    fun toNumber(index: Int): Double?

    fun toString(index: Int): String?

    fun toUserData(index: Int): Any?

    fun <T : Any> toUserData(index: Int, type: Class<T>): T?
}
