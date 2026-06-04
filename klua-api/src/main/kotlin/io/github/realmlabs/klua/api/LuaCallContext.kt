package io.github.realmlabs.klua.api

interface LuaCallContext {
    val argumentCount: Int

    val luaFrames: List<LuaStackFrame>
        get() = emptyList()

    fun isNil(index: Int): Boolean

    fun isNone(index: Int): Boolean

    fun isTable(index: Int): Boolean

    fun typeName(index: Int): String

    fun get(index: Int): Any?

    fun call(index: Int, arguments: List<Any?>): LuaReturn

    fun call(function: Any?, arguments: List<Any?>): LuaReturn

    fun yield(values: List<Any?>): Nothing

    fun load(source: String, chunkName: String): LuaReturn

    fun getTable(index: Int): Any?

    fun getTableValue(index: Int, key: Any?): Any?

    fun getTableField(table: Any?, key: Any?): Any?

    fun setTableValue(index: Int, key: Any?, value: Any?)

    fun getMetatable(index: Int): Any?

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
