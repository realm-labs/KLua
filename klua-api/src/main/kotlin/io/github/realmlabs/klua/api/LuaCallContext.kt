package io.github.realmlabs.klua.api

interface LuaCallContext {
    val argumentCount: Int

    fun isNil(index: Int): Boolean

    fun isNone(index: Int): Boolean

    fun isTable(index: Int): Boolean

    fun typeName(index: Int): String

    fun get(index: Int): Any?

    fun getTable(index: Int): Any?

    fun getTableValue(index: Int, key: Any?): Any?

    fun setTableValue(index: Int, key: Any?, value: Any?)

    fun tableLength(index: Int): Long?

    fun toBoolean(index: Int): Boolean

    fun toInteger(index: Int): Long?

    fun toNumber(index: Int): Double?

    fun toString(index: Int): String?

    fun toUserData(index: Int): Any?

    fun <T : Any> toUserData(index: Int, type: Class<T>): T?
}
