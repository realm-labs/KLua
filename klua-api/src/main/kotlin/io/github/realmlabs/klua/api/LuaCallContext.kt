package io.github.realmlabs.klua.api

interface LuaCallContext {
    val argumentCount: Int

    fun isNil(index: Int): Boolean

    fun isNone(index: Int): Boolean

    fun typeName(index: Int): String

    fun get(index: Int): Any?

    fun toBoolean(index: Int): Boolean

    fun toInteger(index: Int): Long?

    fun toNumber(index: Int): Double?

    fun toString(index: Int): String?
}
