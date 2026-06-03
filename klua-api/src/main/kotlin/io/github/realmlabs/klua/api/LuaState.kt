package io.github.realmlabs.klua.api

class LuaState private constructor(
    val config: LuaConfig,
) {
    private val stack = mutableListOf<LuaStackValue>()

    companion object {
        @JvmStatic
        @JvmOverloads
        fun create(config: LuaConfig = LuaConfig()): LuaState = LuaState(config)
    }

    fun getTop(): Int = stack.size

    fun absIndex(index: Int): Int {
        return when {
            index > 0 -> index
            index < 0 -> stack.size + index + 1
            else -> 0
        }
    }

    @JvmOverloads
    fun pop(count: Int = 1) {
        require(count >= 0) { "count must be non-negative" }
        require(count <= stack.size) { "cannot pop $count values from stack of size ${stack.size}" }
        repeat(count) {
            stack.removeAt(stack.lastIndex)
        }
    }

    fun setTop(index: Int) {
        val newSize = when {
            index >= 0 -> index
            else -> stack.size + index + 1
        }
        require(newSize >= 0) { "stack index out of range: $index" }
        while (stack.size > newSize) {
            stack.removeAt(stack.lastIndex)
        }
        while (stack.size < newSize) {
            stack += LuaStackValue.Nil
        }
    }

    fun pushValue(index: Int) {
        stack += requireValue(index)
    }

    fun copy(fromIndex: Int, toIndex: Int) {
        val value = requireValue(fromIndex)
        val target = requireResolvedIndex(toIndex)
        stack[target] = value
    }

    fun remove(index: Int) {
        stack.removeAt(requireResolvedIndex(index))
    }

    fun pushNil() {
        stack += LuaStackValue.Nil
    }

    fun pushBoolean(value: Boolean) {
        stack += LuaStackValue.BooleanValue(value)
    }

    fun pushInteger(value: Long) {
        stack += LuaStackValue.IntegerValue(value)
    }

    fun pushNumber(value: Double) {
        stack += LuaStackValue.NumberValue(value)
    }

    fun pushString(value: String) {
        stack += LuaStackValue.StringValue(value)
    }

    fun isNil(index: Int): Boolean = valueAt(index) == LuaStackValue.Nil

    fun isNone(index: Int): Boolean = valueAt(index) == null

    fun isBoolean(index: Int): Boolean = valueAt(index) is LuaStackValue.BooleanValue

    fun isNumber(index: Int): Boolean {
        return when (val value = valueAt(index)) {
            is LuaStackValue.IntegerValue,
            is LuaStackValue.NumberValue,
            -> true
            is LuaStackValue.StringValue -> value.value.toDoubleOrNull() != null
            else -> false
        }
    }

    fun isString(index: Int): Boolean {
        return when (valueAt(index)) {
            is LuaStackValue.IntegerValue,
            is LuaStackValue.NumberValue,
            is LuaStackValue.StringValue,
            -> true
            else -> false
        }
    }

    fun typeName(index: Int): String {
        return when (valueAt(index)) {
            null -> "none"
            LuaStackValue.Nil -> "nil"
            is LuaStackValue.BooleanValue -> "boolean"
            is LuaStackValue.IntegerValue,
            is LuaStackValue.NumberValue,
            -> "number"
            is LuaStackValue.StringValue -> "string"
        }
    }

    fun toBoolean(index: Int): Boolean {
        return when (val value = valueAt(index)) {
            null,
            LuaStackValue.Nil,
            LuaStackValue.BooleanValue(false),
            -> false
            else -> true
        }
    }

    fun toInteger(index: Int): Long? {
        return when (val value = valueAt(index)) {
            is LuaStackValue.IntegerValue -> value.value
            is LuaStackValue.NumberValue -> integerFromNumber(value.value)
            is LuaStackValue.StringValue -> value.value.toLongOrNull()
            else -> null
        }
    }

    fun toNumber(index: Int): Double? {
        return when (val value = valueAt(index)) {
            is LuaStackValue.IntegerValue -> value.value.toDouble()
            is LuaStackValue.NumberValue -> value.value
            is LuaStackValue.StringValue -> value.value.toDoubleOrNull()
            else -> null
        }
    }

    fun toString(index: Int): String? {
        return when (val value = valueAt(index)) {
            is LuaStackValue.IntegerValue -> value.value.toString()
            is LuaStackValue.NumberValue -> value.value.toString()
            is LuaStackValue.StringValue -> value.value
            else -> null
        }
    }

    private fun valueAt(index: Int): LuaStackValue? {
        val resolved = when {
            index > 0 -> index - 1
            index < 0 -> stack.size + index
            else -> return null
        }
        return stack.getOrNull(resolved)
    }

    private fun requireValue(index: Int): LuaStackValue {
        return valueAt(index) ?: throw IllegalArgumentException("stack index out of range: $index")
    }

    private fun requireResolvedIndex(index: Int): Int {
        val resolved = when {
            index > 0 -> index - 1
            index < 0 -> stack.size + index
            else -> -1
        }
        require(resolved in stack.indices) { "stack index out of range: $index" }
        return resolved
    }

    private fun integerFromNumber(value: Double): Long? {
        if (!value.isFinite()) {
            return null
        }
        val integer = value.toLong()
        return if (integer.toDouble() == value) integer else null
    }

    private sealed interface LuaStackValue {
        data object Nil : LuaStackValue

        data class BooleanValue(
            val value: Boolean,
        ) : LuaStackValue

        data class IntegerValue(
            val value: Long,
        ) : LuaStackValue

        data class NumberValue(
            val value: Double,
        ) : LuaStackValue

        data class StringValue(
            val value: String,
        ) : LuaStackValue
    }
}
