package io.github.realmlabs.klua.api

import io.github.realmlabs.klua.core.KLuaCoreChunk
import io.github.realmlabs.klua.core.KLuaCoreCallResult
import io.github.realmlabs.klua.core.KLuaCoreExecution
import io.github.realmlabs.klua.core.KLuaCoreGlobals
import io.github.realmlabs.klua.core.KLuaCoreLoad
import io.github.realmlabs.klua.core.KLuaCoreRuntime
import io.github.realmlabs.klua.core.KLuaCoreValue

class LuaState private constructor(
    val config: LuaConfig,
) {
    private val stack = mutableListOf<LuaStackValue>()
    private val globals = LuaStackValue.TableValue()
    private val coreGlobals = KLuaCoreGlobals.create()
    private val coreBackedNativeGlobals = mutableSetOf<String>()
    private var lastError: LuaException? = null

    companion object {
        @JvmStatic
        @JvmOverloads
        fun create(config: LuaConfig = LuaConfig()): LuaState = LuaState(config)
    }

    fun getTop(): Int = stack.size

    fun getLastError(): LuaException? = lastError

    @JvmOverloads
    fun load(source: String, chunkName: String = "chunk"): LuaStatus {
        return when (val result = KLuaCoreRuntime.compile(source, chunkName)) {
            is KLuaCoreLoad.Success -> {
                lastError = null
                stack += LuaStackValue.ChunkValue(result.chunk)
                LuaStatus.OK
            }
            is KLuaCoreLoad.SyntaxError -> {
                lastError = LuaSyntaxException(result.message)
                stack += LuaStackValue.StringValue(result.message)
                LuaStatus.SYNTAX_ERROR
            }
        }
    }

    @JvmOverloads
    fun pcall(argumentCount: Int, resultCount: Int = -1): LuaStatus {
        require(argumentCount >= 0) { "argumentCount must be non-negative" }
        require(resultCount >= -1) { "resultCount must be -1 or non-negative" }
        val functionIndex = stack.size - argumentCount - 1
        require(functionIndex in stack.indices) { "stack does not contain a callable value" }

        return when (val callable = stack[functionIndex]) {
            is LuaStackValue.ChunkValue -> pcallChunk(functionIndex, callable, resultCount)
            is LuaStackValue.NativeFunctionValue -> pcallNativeFunction(functionIndex, callable, resultCount)
            else -> runtimeCallError(functionIndex, "attempt to call ${stackTypeName(callable)}")
        }
    }

    fun pushFunction(function: LuaFunction) {
        stack += LuaStackValue.NativeFunctionValue(function)
    }

    fun register(name: String, function: LuaFunction) {
        setNativeGlobal(name, LuaStackValue.NativeFunctionValue(function))
    }

    private fun pcallChunk(
        functionIndex: Int,
        chunk: LuaStackValue.ChunkValue,
        resultCount: Int,
    ): LuaStatus {
        val arguments = stack.subList(functionIndex + 1, stack.size).map { it.toCoreValue() }
        return when (val result = KLuaCoreRuntime.execute(chunk.chunk, arguments, coreGlobals)) {
            is KLuaCoreExecution.Success -> {
                lastError = null
                removeCallFrame(functionIndex)
                pushResults(result.values, resultCount)
                LuaStatus.OK
            }
            is KLuaCoreExecution.SyntaxError -> {
                lastError = LuaSyntaxException(result.message)
                removeCallFrame(functionIndex)
                stack += LuaStackValue.StringValue(result.message)
                LuaStatus.SYNTAX_ERROR
            }
            is KLuaCoreExecution.RuntimeError -> {
                lastError = LuaRuntimeException(result.message)
                removeCallFrame(functionIndex)
                stack += LuaStackValue.StringValue(result.message)
                LuaStatus.RUNTIME_ERROR
            }
        }
    }

    private fun pcallNativeFunction(
        functionIndex: Int,
        function: LuaStackValue.NativeFunctionValue,
        resultCount: Int,
    ): LuaStatus {
        val arguments = stack.subList(functionIndex + 1, stack.size).toList()
        return try {
            val result = function.function.call(DefaultLuaCallContext(arguments))
            lastError = null
            removeCallFrame(functionIndex)
            pushHostResults(result.values, resultCount)
            LuaStatus.OK
        } catch (exception: LuaException) {
            runtimeCallError(functionIndex, exception.message ?: exception::class.java.simpleName, exception)
        } catch (exception: RuntimeException) {
            runtimeCallError(functionIndex, exception.message ?: exception::class.java.simpleName, exception)
        }
    }

    private fun runtimeCallError(
        functionIndex: Int,
        message: String,
        cause: Throwable? = null,
    ): LuaStatus {
        lastError = LuaRuntimeException(message, cause)
        removeCallFrame(functionIndex)
        stack += LuaStackValue.StringValue(message)
        return LuaStatus.RUNTIME_ERROR
    }

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

    fun newTable() {
        stack += LuaStackValue.TableValue()
    }

    fun getField(index: Int, key: String) {
        val table = requireTable(index)
        stack += table.fields[key] ?: LuaStackValue.Nil
    }

    fun setField(index: Int, key: String) {
        val table = requireTable(index)
        val value = requireValue(-1)
        stack.removeAt(stack.lastIndex)
        if (value == LuaStackValue.Nil) {
            table.fields.remove(key)
        } else {
            table.fields[key] = value
        }
    }

    fun getGlobal(name: String) {
        if (name in coreBackedNativeGlobals) {
            val value = coreGlobals.get(name)
            if (value is KLuaCoreValue.UnsupportedValue && value.typeName == "function") {
                stack += globals.fields[name] ?: value.toStackValue()
            } else {
                globals.fields.remove(name)
                coreBackedNativeGlobals.remove(name)
                stack += value.toStackValue()
            }
            return
        }
        stack += globals.fields[name] ?: coreGlobals.get(name).toStackValue()
    }

    fun setGlobal(name: String) {
        val value = requireValue(-1)
        stack.removeAt(stack.lastIndex)
        val coreValue = value.toCoreValue()
        if (coreGlobals.set(name, coreValue)) {
            globals.fields.remove(name)
            coreBackedNativeGlobals.remove(name)
        } else {
            when (value) {
                is LuaStackValue.NativeFunctionValue -> setNativeGlobal(name, value)
                else -> {
                    coreGlobals.set(name, KLuaCoreValue.Nil)
                    globals.fields[name] = value
                    coreBackedNativeGlobals.remove(name)
                }
            }
        }
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

    fun isTable(index: Int): Boolean = valueAt(index) is LuaStackValue.TableValue

    fun typeName(index: Int): String {
        return stackTypeName(valueAt(index))
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

    private fun removeCallFrame(functionIndex: Int) {
        while (stack.size > functionIndex) {
            stack.removeAt(stack.lastIndex)
        }
    }

    private fun pushResults(values: List<KLuaCoreValue>, resultCount: Int) {
        val count = if (resultCount == -1) values.size else resultCount
        for (index in 0 until count) {
            stack += values.getOrNull(index).toStackValue()
        }
    }

    private fun pushHostResults(values: List<Any?>, resultCount: Int) {
        val count = if (resultCount == -1) values.size else resultCount
        for (index in 0 until count) {
            stack += values.getOrNull(index).toStackValue()
        }
    }

    private fun setNativeGlobal(name: String, function: LuaStackValue.NativeFunctionValue) {
        coreGlobals.setFunction(name) { arguments ->
            callHostFunction(function.function, arguments.map { it.toStackValue() })
        }
        coreBackedNativeGlobals += name
        globals.fields[name] = function
    }

    private fun callHostFunction(
        function: LuaFunction,
        arguments: List<LuaStackValue>,
    ): KLuaCoreCallResult {
        return try {
            val result = function.call(DefaultLuaCallContext(arguments))
            KLuaCoreCallResult.Success(result.values.map { it.toCoreReturnValue() })
        } catch (exception: LuaException) {
            KLuaCoreCallResult.RuntimeError(exception.message ?: exception::class.java.simpleName)
        } catch (exception: RuntimeException) {
            KLuaCoreCallResult.RuntimeError(exception.message ?: exception::class.java.simpleName)
        }
    }

    private fun KLuaCoreValue?.toStackValue(): LuaStackValue {
        return when (this) {
            null,
            KLuaCoreValue.Nil,
            -> LuaStackValue.Nil
            is KLuaCoreValue.BooleanValue -> LuaStackValue.BooleanValue(value)
            is KLuaCoreValue.IntegerValue -> LuaStackValue.IntegerValue(value)
            is KLuaCoreValue.NumberValue -> LuaStackValue.NumberValue(value)
            is KLuaCoreValue.StringValue -> LuaStackValue.StringValue(value)
            is KLuaCoreValue.UnsupportedValue -> LuaStackValue.UnsupportedValue(typeName)
        }
    }

    private fun Any?.toStackValue(): LuaStackValue {
        return when (this) {
            null -> LuaStackValue.Nil
            is Boolean -> LuaStackValue.BooleanValue(this)
            is Byte -> LuaStackValue.IntegerValue(toLong())
            is Short -> LuaStackValue.IntegerValue(toLong())
            is Int -> LuaStackValue.IntegerValue(toLong())
            is Long -> LuaStackValue.IntegerValue(this)
            is Float -> LuaStackValue.NumberValue(toDouble())
            is Double -> LuaStackValue.NumberValue(this)
            is CharSequence -> LuaStackValue.StringValue(toString())
            else -> throw IllegalArgumentException("cannot return ${this::class.java.name} as Lua value")
        }
    }

    private fun Any?.toCoreReturnValue(): KLuaCoreValue {
        return when (this) {
            null -> KLuaCoreValue.Nil
            is Boolean -> KLuaCoreValue.BooleanValue(this)
            is Byte -> KLuaCoreValue.IntegerValue(toLong())
            is Short -> KLuaCoreValue.IntegerValue(toLong())
            is Int -> KLuaCoreValue.IntegerValue(toLong())
            is Long -> KLuaCoreValue.IntegerValue(this)
            is Float -> KLuaCoreValue.NumberValue(toDouble())
            is Double -> KLuaCoreValue.NumberValue(this)
            is CharSequence -> KLuaCoreValue.StringValue(toString())
            else -> throw IllegalArgumentException("cannot return ${this::class.java.name} as Lua value")
        }
    }

    private fun LuaStackValue.toCoreValue(): KLuaCoreValue {
        return when (this) {
            LuaStackValue.Nil -> KLuaCoreValue.Nil
            is LuaStackValue.BooleanValue -> KLuaCoreValue.BooleanValue(value)
            is LuaStackValue.IntegerValue -> KLuaCoreValue.IntegerValue(value)
            is LuaStackValue.NumberValue -> KLuaCoreValue.NumberValue(value)
            is LuaStackValue.StringValue -> KLuaCoreValue.StringValue(value)
            is LuaStackValue.ChunkValue -> KLuaCoreValue.UnsupportedValue("function")
            is LuaStackValue.NativeFunctionValue -> KLuaCoreValue.UnsupportedValue("function")
            is LuaStackValue.TableValue -> KLuaCoreValue.UnsupportedValue("table")
            is LuaStackValue.UnsupportedValue -> KLuaCoreValue.UnsupportedValue(typeName)
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

    private fun requireTable(index: Int): LuaStackValue.TableValue {
        return requireValue(index) as? LuaStackValue.TableValue
            ?: throw IllegalArgumentException("stack index $index is not a table")
    }

    private fun integerFromNumber(value: Double): Long? {
        if (!value.isFinite()) {
            return null
        }
        val integer = value.toLong()
        return if (integer.toDouble() == value) integer else null
    }

    private fun stackTypeName(value: LuaStackValue?): String {
        return when (value) {
            null -> "none"
            LuaStackValue.Nil -> "nil"
            is LuaStackValue.BooleanValue -> "boolean"
            is LuaStackValue.ChunkValue,
            is LuaStackValue.NativeFunctionValue,
            -> "function"
            is LuaStackValue.TableValue -> "table"
            is LuaStackValue.IntegerValue,
            is LuaStackValue.NumberValue,
            -> "number"
            is LuaStackValue.StringValue -> "string"
            is LuaStackValue.UnsupportedValue -> value.typeName
        }
    }

    private inner class DefaultLuaCallContext(
        private val arguments: List<LuaStackValue>,
    ) : LuaCallContext {
        override val argumentCount: Int = arguments.size

        override fun isNil(index: Int): Boolean = valueAt(index) == LuaStackValue.Nil

        override fun isNone(index: Int): Boolean = valueAt(index) == null

        override fun typeName(index: Int): String = stackTypeName(valueAt(index))

        override fun get(index: Int): Any? {
            return when (val value = valueAt(index)) {
                null,
                LuaStackValue.Nil,
                -> null
                is LuaStackValue.BooleanValue -> value.value
                is LuaStackValue.IntegerValue -> value.value
                is LuaStackValue.NumberValue -> value.value
                is LuaStackValue.StringValue -> value.value
                is LuaStackValue.NativeFunctionValue -> value.function
                else -> throw IllegalArgumentException("argument $index is ${stackTypeName(value)}")
            }
        }

        override fun toBoolean(index: Int): Boolean {
            return when (val value = valueAt(index)) {
                null,
                LuaStackValue.Nil,
                LuaStackValue.BooleanValue(false),
                -> false
                else -> true
            }
        }

        override fun toInteger(index: Int): Long? {
            return when (val value = valueAt(index)) {
                is LuaStackValue.IntegerValue -> value.value
                is LuaStackValue.NumberValue -> integerFromNumber(value.value)
                is LuaStackValue.StringValue -> value.value.toLongOrNull()
                else -> null
            }
        }

        override fun toNumber(index: Int): Double? {
            return when (val value = valueAt(index)) {
                is LuaStackValue.IntegerValue -> value.value.toDouble()
                is LuaStackValue.NumberValue -> value.value
                is LuaStackValue.StringValue -> value.value.toDoubleOrNull()
                else -> null
            }
        }

        override fun toString(index: Int): String? {
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
                index < 0 -> arguments.size + index
                else -> return null
            }
            return arguments.getOrNull(resolved)
        }
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

        data class TableValue(
            val fields: MutableMap<String, LuaStackValue> = linkedMapOf(),
        ) : LuaStackValue

        data class ChunkValue(
            val chunk: KLuaCoreChunk,
        ) : LuaStackValue

        data class NativeFunctionValue(
            val function: LuaFunction,
        ) : LuaStackValue

        data class UnsupportedValue(
            val typeName: String,
        ) : LuaStackValue
    }
}
