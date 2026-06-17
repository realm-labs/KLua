package io.github.realmlabs.klua.api

class LuaReturn private constructor(
    val values: List<Any?>,
) {
    fun getCount(): Int = values.size

    fun get(index: Int): Any? {
        require(index >= 1) { "return index must be positive: $index" }
        return values.getOrNull(index - 1)
    }

    fun getBoolean(index: Int): Boolean {
        return get(index) as? Boolean
            ?: throw LuaRuntimeException("return value $index is not a boolean")
    }

    fun getLong(index: Int): Long {
        return when (val value = get(index)) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            is Float -> integerFromDouble(value.toDouble(), index)
            is Double -> integerFromDouble(value, index)
            else -> throw LuaRuntimeException("return value $index is not an integer")
        }
    }

    fun getDouble(index: Int): Double {
        return when (val value = get(index)) {
            is Byte -> value.toDouble()
            is Short -> value.toDouble()
            is Int -> value.toDouble()
            is Long -> value.toDouble()
            is Float -> value.toDouble()
            is Double -> value
            else -> throw LuaRuntimeException("return value $index is not a number")
        }
    }

    fun getString(index: Int): String {
        return get(index) as? String
            ?: throw LuaRuntimeException("return value $index is not a string")
    }

    fun getUserData(index: Int): Any {
        val value = get(index)
        return if (value != null && isUserDataValue(value)) {
            value
        } else {
            throw LuaRuntimeException("return value $index is not userdata")
        }
    }

    fun <T : Any> getUserData(index: Int, type: Class<T>): T {
        val value = getUserData(index)
        return if (type.isInstance(value)) {
            type.cast(value)
        } else {
            throw LuaRuntimeException("return value $index is not ${type.name}")
        }
    }

    companion object {
        @JvmStatic
        fun none(): LuaReturn = LuaReturn(emptyList())

        @JvmStatic
        fun of(vararg values: Any?): LuaReturn = LuaReturn(values.toList())

        @JvmStatic
        fun ofValues(values: List<Any?>): LuaReturn = LuaReturn(values.toList())
    }
}

private fun isUserDataValue(value: Any): Boolean {
    return when (value) {
        is Boolean,
        is Byte,
        is Short,
        is Int,
        is Long,
        is Float,
        is Double,
        is CharSequence,
        is LuaFunction,
        -> false
        else -> true
    }
}

private fun integerFromDouble(value: Double, index: Int): Long {
    if (!value.isFinite() || value < Long.MIN_VALUE.toDouble() || value >= LUA_INTEGER_EXCLUSIVE_UPPER_BOUND) {
        throw LuaRuntimeException("return value $index is not an integer")
    }
    val integer = value.toLong()
    if (integer.toDouble() != value) {
        throw LuaRuntimeException("return value $index is not an integer")
    }
    return integer
}

private val LUA_INTEGER_EXCLUSIVE_UPPER_BOUND = -Long.MIN_VALUE.toDouble()
