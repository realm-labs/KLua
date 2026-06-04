package io.github.realmlabs.klua.api

import io.github.realmlabs.klua.core.KLuaCoreChunk
import io.github.realmlabs.klua.core.KLuaCoreCallResult
import io.github.realmlabs.klua.core.KLuaCoreContinuation
import io.github.realmlabs.klua.core.KLuaCoreCoroutine
import io.github.realmlabs.klua.core.KLuaCoreCoroutineExecution
import io.github.realmlabs.klua.core.KLuaCoreDebugHook
import io.github.realmlabs.klua.core.KLuaCoreExecution
import io.github.realmlabs.klua.core.KLuaCoreGlobals
import io.github.realmlabs.klua.core.KLuaCoreLoad
import io.github.realmlabs.klua.core.KLuaCoreRuntime
import io.github.realmlabs.klua.core.KLuaCoreStackFrame
import io.github.realmlabs.klua.core.KLuaCoreUserDataGetter
import io.github.realmlabs.klua.core.KLuaCoreUserDataMethod
import io.github.realmlabs.klua.core.KLuaCoreUserDataSetter
import io.github.realmlabs.klua.core.KLuaCoreValue
import java.util.IdentityHashMap
import java.util.function.Consumer

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

    fun installGlobalTable(name: String) {
        globals.fields[LuaStackValue.StringValue(name)] = globals
        coreGlobals.setGlobalTable(name)
    }

    fun <T : Any> registerType(type: Class<T>, configure: Consumer<LuaUserDataType<T>>) {
        val userDataType = LuaUserDataType<T>(
            registerMethod = { name, method ->
                coreGlobals.setUserDataMethod(
                    type = type,
                    name = name,
                    method = KLuaCoreUserDataMethod { receiver, arguments ->
                        callHostUserDataMethod(
                            method,
                            receiver,
                            arguments.map { it.toStackValue() },
                            nativeFrameName = "${type.luaDebugName()}.$name",
                        )
                    },
                )
            },
            registerProperty = { name, getter, setter ->
                coreGlobals.setUserDataProperty(
                    type = type,
                    name = name,
                    getter = getter?.let { propertyGetter ->
                        KLuaCoreUserDataGetter { receiver ->
                            callHostUserDataGetter(
                                propertyGetter,
                                receiver,
                                nativeFrameName = "${type.luaDebugName()}.$name.get",
                            )
                        }
                    },
                    setter = setter?.let { propertySetter ->
                        KLuaCoreUserDataSetter { receiver, value ->
                            callHostUserDataSetter(
                                propertySetter,
                                receiver,
                                value.toStackValue(),
                                nativeFrameName = "${type.luaDebugName()}.$name.set",
                            )
                        }
                    },
                )
            },
        )
        configure.accept(userDataType)
    }

    private fun pcallChunk(
        functionIndex: Int,
        chunk: LuaStackValue.ChunkValue,
        resultCount: Int,
    ): LuaStatus {
        val tableCache = IdentityHashMap<LuaStackValue.TableValue, KLuaCoreValue.TableValue>()
        val arguments = stack.subList(functionIndex + 1, stack.size).map { it.toCoreValue(tableCache) }
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
                lastError = LuaRuntimeException(
                    result.message,
                    result.cause,
                    sourceName = result.sourceName,
                    line = result.line,
                    luaFrames = toApiStackFrames(result.luaFrames),
                )
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

    private fun loadLuaFunction(source: String, chunkName: String): LuaReturn {
        return when (val load = KLuaCoreRuntime.compile(source, chunkName)) {
            is KLuaCoreLoad.Success -> {
                val function = LuaFunction { context ->
                    val arguments = (1..context.argumentCount).map { index -> context.argumentToCoreValue(index) }
                    when (val result = KLuaCoreRuntime.execute(load.chunk, arguments, coreGlobals)) {
                        is KLuaCoreExecution.Success -> LuaReturn.ofValues(
                            result.values.map { value -> value.toStackValue().toPublicCallReturnValue() },
                        )
                        is KLuaCoreExecution.SyntaxError -> throw LuaSyntaxException(result.message)
                        is KLuaCoreExecution.RuntimeError -> throw LuaRuntimeException(
                            result.message,
                            result.cause,
                            sourceName = result.sourceName,
                            line = result.line,
                            luaFrames = toApiStackFrames(result.luaFrames),
                        )
                    }
                }
                LuaReturn.of(function)
            }
            is KLuaCoreLoad.SyntaxError -> LuaReturn.of(null, load.message)
        }
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
        stack += table.fields[LuaStackValue.StringValue(key)] ?: LuaStackValue.Nil
    }

    fun setField(index: Int, key: String) {
        val table = requireTable(index)
        val value = requireValue(-1)
        stack.removeAt(stack.lastIndex)
        if (value == LuaStackValue.Nil) {
            table.fields.remove(LuaStackValue.StringValue(key))
        } else {
            table.fields[LuaStackValue.StringValue(key)] = value
        }
    }

    fun getGlobal(name: String) {
        val key = LuaStackValue.StringValue(name)
        if (name in coreBackedNativeGlobals) {
            val value = coreGlobals.get(name)
            if (value is KLuaCoreValue.UnsupportedValue && value.typeName == "function") {
                stack += globals.fields[key] ?: value.toStackValue()
            } else {
                globals.fields.remove(key)
                coreBackedNativeGlobals.remove(name)
                stack += value.toStackValue()
            }
            return
        }
        stack += globals.fields[key] ?: coreGlobals.get(name).toStackValue()
    }

    fun setGlobal(name: String) {
        val key = LuaStackValue.StringValue(name)
        val value = requireValue(-1)
        stack.removeAt(stack.lastIndex)
        val coreValue = value.toCoreValue()
        if (coreGlobals.set(name, coreValue)) {
            globals.fields.remove(key)
            coreBackedNativeGlobals.remove(name)
        } else {
            when (value) {
                is LuaStackValue.NativeFunctionValue -> setNativeGlobal(name, value)
                else -> {
                    coreGlobals.set(name, KLuaCoreValue.Nil)
                    globals.fields[key] = value
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

    fun pushUserData(value: Any) {
        stack += LuaStackValue.UserDataValue(value)
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

    fun isUserData(index: Int): Boolean = valueAt(index) is LuaStackValue.UserDataValue

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

    fun toUserData(index: Int): Any? {
        return (valueAt(index) as? LuaStackValue.UserDataValue)?.value
    }

    fun <T : Any> toUserData(index: Int, type: Class<T>): T? {
        val value = toUserData(index) ?: return null
        return if (type.isInstance(value)) type.cast(value) else null
    }

    internal fun toAny(index: Int): Any? {
        return when (val value = valueAt(index)) {
            null,
            LuaStackValue.Nil,
            -> null
            is LuaStackValue.BooleanValue -> value.value
            is LuaStackValue.IntegerValue -> value.value
            is LuaStackValue.NumberValue -> value.value
            is LuaStackValue.StringValue -> value.value
            is LuaStackValue.NativeFunctionValue -> value.function
            is LuaStackValue.UserDataValue -> value.value
            else -> throw LuaRuntimeException("stack value $index is ${stackTypeName(value)}")
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
        coreGlobals.set(
            name,
            KLuaCoreRuntime.createContextFunctionValue(
                function = { context ->
                    callHostFunction(
                        function.function,
                        context.arguments,
                        context.luaFrames,
                        nativeFrameName = name,
                        setLocal = { level, index, value ->
                            context.setLocal(level, index, value.toCoreReturnValue())
                        },
                        setDebugHook = { index, mask, count ->
                            context.setDebugHook(index, mask, count)
                        },
                        getDebugHook = {
                            context.getDebugHook()?.toLuaReturn() ?: LuaReturn.of(null)
                        },
                    )
                },
                yieldable = function.function is LuaYieldableFunction,
            ),
        )
        coreBackedNativeGlobals += name
        globals.fields[LuaStackValue.StringValue(name)] = function
    }

    private fun callHostFunction(
        function: LuaFunction,
        arguments: List<KLuaCoreValue>,
        luaFrames: List<KLuaCoreStackFrame> = emptyList(),
        nativeFrameName: String? = null,
        setLocal: ((level: Int, index: Int, value: Any?) -> String?)? = null,
        setDebugHook: ((index: Int, mask: String, count: Int) -> Boolean)? = null,
        getDebugHook: (() -> LuaReturn)? = null,
    ): KLuaCoreCallResult {
        val stackTableCache = IdentityHashMap<KLuaCoreValue.TableValue, LuaStackValue.TableValue>()
        val stackArguments = arguments.map { it.toStackValue(stackTableCache) }
        return try {
            val result = function.call(
                DefaultLuaCallContext(
                    stackArguments,
                    toApiStackFrames(luaFrames),
                    setLocal,
                    setDebugHook,
                    getDebugHook,
                ),
            )
            syncStackArgumentsToCore(arguments, stackArguments)
            KLuaCoreCallResult.Success(
                result.values.map { value -> value.toCoreReturnValue(stackArguments, arguments) },
            )
        } catch (yield: LuaYieldException) {
            yield.toCoreYieldResult(stackArguments, arguments)
        } catch (exception: LuaException) {
            KLuaCoreCallResult.RuntimeError(
                exception.message ?: exception::class.java.simpleName,
                nativeFrames = nativeFrameName.toNativeFrames(),
            )
        } catch (exception: RuntimeException) {
            KLuaCoreCallResult.RuntimeError(
                exception.message ?: exception::class.java.simpleName,
                exception,
                nativeFrames = nativeFrameName.toNativeFrames(),
            )
        }
    }

    private fun <T : Any> callHostUserDataMethod(
        method: LuaUserDataMethod<T>,
        receiver: T,
        arguments: List<LuaStackValue>,
        nativeFrameName: String? = null,
    ): KLuaCoreCallResult {
        return try {
            val result = method.call(receiver, DefaultLuaCallContext(arguments))
            KLuaCoreCallResult.Success(result.values.map { it.toCoreReturnValue() })
        } catch (yield: LuaYieldException) {
            yield.toCoreYieldResult()
        } catch (exception: LuaException) {
            KLuaCoreCallResult.RuntimeError(
                exception.message ?: exception::class.java.simpleName,
                nativeFrames = nativeFrameName.toNativeFrames(),
            )
        } catch (exception: RuntimeException) {
            KLuaCoreCallResult.RuntimeError(
                exception.message ?: exception::class.java.simpleName,
                exception,
                nativeFrames = nativeFrameName.toNativeFrames(),
            )
        }
    }

    private fun <T : Any> callHostUserDataGetter(
        getter: LuaUserDataGetter<T>,
        receiver: T,
        nativeFrameName: String? = null,
    ): KLuaCoreCallResult {
        return try {
            val result = getter.get(receiver)
            KLuaCoreCallResult.Success(result.values.map { it.toCoreReturnValue() })
        } catch (yield: LuaYieldException) {
            yield.toCoreYieldResult()
        } catch (exception: LuaException) {
            KLuaCoreCallResult.RuntimeError(
                exception.message ?: exception::class.java.simpleName,
                nativeFrames = nativeFrameName.toNativeFrames(),
            )
        } catch (exception: RuntimeException) {
            KLuaCoreCallResult.RuntimeError(
                exception.message ?: exception::class.java.simpleName,
                exception,
                nativeFrames = nativeFrameName.toNativeFrames(),
            )
        }
    }

    private fun <T : Any> callHostUserDataSetter(
        setter: LuaUserDataSetter<T>,
        receiver: T,
        value: LuaStackValue,
        nativeFrameName: String? = null,
    ): KLuaCoreCallResult {
        return try {
            setter.set(receiver, value.toAnyValue())
            KLuaCoreCallResult.Success(emptyList())
        } catch (yield: LuaYieldException) {
            yield.toCoreYieldResult()
        } catch (exception: LuaException) {
            KLuaCoreCallResult.RuntimeError(
                exception.message ?: exception::class.java.simpleName,
                nativeFrames = nativeFrameName.toNativeFrames(),
            )
        } catch (exception: RuntimeException) {
            KLuaCoreCallResult.RuntimeError(
                exception.message ?: exception::class.java.simpleName,
                exception,
                nativeFrames = nativeFrameName.toNativeFrames(),
            )
        }
    }

    private fun KLuaCoreValue?.toStackValue(): LuaStackValue {
        return toStackValue(IdentityHashMap())
    }

    private fun KLuaCoreValue?.toStackValue(
        tableCache: MutableMap<KLuaCoreValue.TableValue, LuaStackValue.TableValue>,
    ): LuaStackValue {
        return when (this) {
            null,
            KLuaCoreValue.Nil,
            -> LuaStackValue.Nil
            is KLuaCoreValue.BooleanValue -> LuaStackValue.BooleanValue(value)
            is KLuaCoreValue.IntegerValue -> LuaStackValue.IntegerValue(value)
            is KLuaCoreValue.NumberValue -> LuaStackValue.NumberValue(value)
            is KLuaCoreValue.StringValue -> LuaStackValue.StringValue(value)
            is KLuaCoreValue.FunctionValue -> LuaStackValue.NativeFunctionValue(toLuaFunctionValue(this), this)
            is KLuaCoreValue.TableValue -> {
                val cached = tableCache[this]
                if (cached != null) {
                    return cached
                }
                val tableValue = LuaStackValue.TableValue(coreValue = this)
                tableCache[this] = tableValue
                tableValue.fields.putAll(
                    fields.map { (fieldKey, fieldValue) ->
                        fieldKey.toStackValue(tableCache) to fieldValue.toStackValue(tableCache)
                    },
                )
                tableValue.metatable = metatable?.toStackValue(tableCache) as? LuaStackValue.TableValue
                tableValue
            }
            is KLuaCoreValue.UserDataValue -> LuaStackValue.UserDataValue(value)
            is KLuaCoreValue.UnsupportedValue -> LuaStackValue.UnsupportedValue(typeName)
        }
    }

    private fun toLuaFunctionValue(functionValue: KLuaCoreValue.FunctionValue): LuaFunction {
        return if (KLuaCoreRuntime.canCreateCoroutine(functionValue)) {
            object : LuaCoroutineFunction {
                override fun call(context: LuaCallContext): LuaReturn {
                    return callCoreFunctionValue(functionValue, context)
                }

                override fun createCoroutine(): LuaCoroutineHandle {
                    val coroutine = KLuaCoreRuntime.createCoroutine(functionValue, coreGlobals)
                        ?: throw LuaRuntimeException("cannot create coroutine from function")
                    return CoreBackedLuaCoroutine(coroutine)
                }
            }
        } else {
            LuaFunction { context -> callCoreFunctionValue(functionValue, context) }
        }
    }

    private fun callCoreFunctionValue(
        functionValue: KLuaCoreValue.FunctionValue,
        context: LuaCallContext,
    ): LuaReturn {
        val arguments = (1..context.argumentCount).map { index -> context.argumentToCoreValue(index) }
        return when (val result = functionValue.function.call(arguments)) {
            is KLuaCoreCallResult.Success -> LuaReturn.ofValues(
                result.values.map { it.toStackValue().toPublicCallReturnValue() },
            )
            is KLuaCoreCallResult.Yielded -> throw result.toLuaYieldException()
            is KLuaCoreCallResult.RuntimeError -> throw LuaRuntimeException(result.message)
        }
    }

    private fun KLuaCoreCallResult.Yielded.toLuaYieldException(): LuaYieldException {
        return LuaYieldException(
            values.map { it.toStackValue().toPublicCallReturnValue() },
            continuation?.let { continuation ->
                { arguments -> continueCoreYield(continuation, arguments) }
            },
        )
    }

    private fun continueCoreYield(continuation: KLuaCoreContinuation, arguments: List<Any?>): LuaReturn {
        val tableCache = IdentityHashMap<LuaStackValue.TableValue, KLuaCoreValue.TableValue>()
        val coreArguments = arguments.map { argument -> argument.toStackValue().toCoreValue(tableCache) }
        return when (val result = continuation.resume(coreArguments)) {
            is KLuaCoreCallResult.Success -> LuaReturn.ofValues(
                result.values.map { it.toStackValue().toPublicCallReturnValue() },
            )
            is KLuaCoreCallResult.Yielded -> throw result.toLuaYieldException()
            is KLuaCoreCallResult.RuntimeError -> throw LuaRuntimeException(result.message)
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
            is LuaFunction -> LuaStackValue.NativeFunctionValue(this)
            is Map<*, *> -> toStackTableValue()
            is LuaStackValue -> this
            else -> LuaStackValue.UserDataValue(this)
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
            is LuaFunction -> toCoreFunctionValue()
            is Map<*, *> -> toCoreTableValue()
            is LuaStackValue -> toCoreValue()
            else -> KLuaCoreValue.UserDataValue(this)
        }
    }

    private fun LuaFunction.toCoreFunctionValue(): KLuaCoreValue.FunctionValue {
        return KLuaCoreRuntime.createContextFunctionValue(
            function = { context ->
                callHostFunction(
                    this,
                    context.arguments,
                    context.luaFrames,
                    setLocal = { level, index, value ->
                        context.setLocal(level, index, value.toCoreReturnValue())
                    },
                    setDebugHook = { index, mask, count ->
                        context.setDebugHook(index, mask, count)
                    },
                    getDebugHook = {
                        context.getDebugHook()?.toLuaReturn() ?: LuaReturn.of(null)
                    },
                )
            },
            yieldable = this is LuaYieldableFunction,
        )
    }

    private fun LuaCallContext.argumentToCoreValue(index: Int): KLuaCoreValue {
        return when (typeName(index)) {
            "table" -> getTable(index).toCoreReturnValue()
            else -> get(index).toCoreReturnValue()
        }
    }

    private fun KLuaCoreDebugHook.toLuaReturn(): LuaReturn {
        return LuaReturn.of(function.toStackValue(), mask, count.toLong())
    }

    private fun Any?.toCoreReturnValue(
        stackArguments: List<LuaStackValue>,
        coreArguments: List<KLuaCoreValue>,
    ): KLuaCoreValue {
        val stackTable = this as? LuaStackValue.TableValue
        if (stackTable != null) {
            val tableCache = seedCoreTableCache(stackArguments, coreArguments)
            return stackTable.toCoreTableValue(tableCache)
        }
        return toCoreReturnValue()
    }

    private fun LuaYieldException.toCoreYieldResult(): KLuaCoreCallResult.Yielded {
        return KLuaCoreCallResult.Yielded(
            values.map { it.toCoreReturnValue() },
            KLuaCoreContinuation { arguments ->
                val publicArguments = arguments.map { value -> value.toStackValue().toPublicCallReturnValue() }
                continueYieldAsCore(publicArguments)
            },
        )
    }

    private fun LuaYieldException.toCoreYieldResult(
        stackArguments: List<LuaStackValue>,
        coreArguments: List<KLuaCoreValue>,
    ): KLuaCoreCallResult.Yielded {
        return KLuaCoreCallResult.Yielded(
            values.map { value -> value.toCoreReturnValue(stackArguments, coreArguments) },
            KLuaCoreContinuation { arguments ->
                val publicArguments = arguments.map { value -> value.toStackValue().toPublicCallReturnValue() }
                continueYieldAsCore(publicArguments, stackArguments, coreArguments)
            },
        )
    }

    private fun LuaYieldException.continueYieldAsCore(
        arguments: List<Any?>,
        stackArguments: List<LuaStackValue>? = null,
        coreArguments: List<KLuaCoreValue>? = null,
    ): KLuaCoreCallResult {
        return try {
            val result = continueWith(arguments)
            KLuaCoreCallResult.Success(
                result.values.map { value ->
                    if (stackArguments != null && coreArguments != null) {
                        value.toCoreReturnValue(stackArguments, coreArguments)
                    } else {
                        value.toCoreReturnValue()
                    }
                },
            )
        } catch (yield: LuaYieldException) {
            if (stackArguments != null && coreArguments != null) {
                yield.toCoreYieldResult(stackArguments, coreArguments)
            } else {
                yield.toCoreYieldResult()
            }
        } catch (exception: LuaException) {
            KLuaCoreCallResult.RuntimeError(exception.message ?: exception::class.java.simpleName)
        } catch (exception: RuntimeException) {
            KLuaCoreCallResult.RuntimeError(exception.message ?: exception::class.java.simpleName, exception)
        }
    }

    private fun Map<*, *>.toStackTableValue(): LuaStackValue.TableValue {
        val fields = linkedMapOf<LuaStackValue, LuaStackValue>()
        for ((key, value) in this) {
            if (key == null) {
                throw LuaRuntimeException("table index is nil")
            }
            fields[key.toStackValue()] = value.toStackValue()
        }
        return LuaStackValue.TableValue(fields)
    }

    private fun Map<*, *>.toCoreTableValue(): KLuaCoreValue.TableValue {
        val fields = linkedMapOf<KLuaCoreValue, KLuaCoreValue>()
        for ((key, value) in this) {
            if (key == null) {
                throw LuaRuntimeException("table index is nil")
            }
            fields[key.toCoreReturnValue()] = value.toCoreReturnValue()
        }
        return KLuaCoreValue.TableValue(fields)
    }

    private fun LuaStackValue.toAnyValue(): Any? {
        return when (this) {
            LuaStackValue.Nil -> null
            is LuaStackValue.BooleanValue -> value
            is LuaStackValue.IntegerValue -> value
            is LuaStackValue.NumberValue -> value
            is LuaStackValue.StringValue -> value
            is LuaStackValue.NativeFunctionValue -> function
            is LuaStackValue.UserDataValue -> value
            else -> throw IllegalArgumentException("cannot pass ${stackTypeName(this)} as host value")
        }
    }

    private fun LuaStackValue.toPublicCallReturnValue(): Any? {
        return when (this) {
            is LuaStackValue.TableValue,
            is LuaStackValue.NativeFunctionValue,
            -> this
            else -> toAnyValue()
        }
    }

    private fun LuaStackValue.toCoreValue(): KLuaCoreValue {
        return toCoreValue(IdentityHashMap())
    }

    private fun LuaStackValue.toCoreValue(
        tableCache: MutableMap<LuaStackValue.TableValue, KLuaCoreValue.TableValue>,
    ): KLuaCoreValue {
        return when (this) {
            LuaStackValue.Nil -> KLuaCoreValue.Nil
            is LuaStackValue.BooleanValue -> KLuaCoreValue.BooleanValue(value)
            is LuaStackValue.IntegerValue -> KLuaCoreValue.IntegerValue(value)
            is LuaStackValue.NumberValue -> KLuaCoreValue.NumberValue(value)
            is LuaStackValue.StringValue -> KLuaCoreValue.StringValue(value)
            is LuaStackValue.ChunkValue -> KLuaCoreValue.UnsupportedValue("function")
            is LuaStackValue.NativeFunctionValue -> coreFunction ?: function.toCoreFunctionValue()
            is LuaStackValue.TableValue -> toCoreTableValue(tableCache)
            is LuaStackValue.UserDataValue -> KLuaCoreValue.UserDataValue(value)
            is LuaStackValue.UnsupportedValue -> KLuaCoreValue.UnsupportedValue(typeName)
        }
    }

    private fun LuaStackValue.TableValue.toCoreTableValue(
        tableCache: MutableMap<LuaStackValue.TableValue, KLuaCoreValue.TableValue>,
    ): KLuaCoreValue.TableValue {
        val cached = tableCache[this]
        if (cached != null) {
            return cached
        }
        if (coreValue != null) {
            syncStackTableToCore(this, coreValue, tableCache)
            return coreValue
        }
        val tableValue = KLuaCoreValue.TableValue(mutableMapOf())
        tableCache[this] = tableValue
        tableValue.fields.putAll(
            fields.map { (fieldKey, fieldValue) ->
                fieldKey.toCoreTableFieldValue(tableCache) to fieldValue.toCoreTableFieldValue(tableCache)
            },
        )
        tableValue.metatable = metatable?.toCoreTableValue(tableCache)
        return tableValue
    }

    private fun LuaStackValue.toCoreTableFieldValue(
        tableCache: MutableMap<LuaStackValue.TableValue, KLuaCoreValue.TableValue>,
    ): KLuaCoreValue {
        return when (this) {
            LuaStackValue.Nil -> KLuaCoreValue.Nil
            is LuaStackValue.BooleanValue -> KLuaCoreValue.BooleanValue(value)
            is LuaStackValue.IntegerValue -> KLuaCoreValue.IntegerValue(value)
            is LuaStackValue.NumberValue -> KLuaCoreValue.NumberValue(value)
            is LuaStackValue.StringValue -> KLuaCoreValue.StringValue(value)
            is LuaStackValue.NativeFunctionValue -> coreFunction ?: function.toCoreFunctionValue()
            is LuaStackValue.TableValue -> toCoreTableValue(tableCache)
            is LuaStackValue.UserDataValue -> KLuaCoreValue.UserDataValue(value)
            is LuaStackValue.ChunkValue -> KLuaCoreValue.UnsupportedValue("function")
            is LuaStackValue.UnsupportedValue -> KLuaCoreValue.UnsupportedValue(typeName)
        }
    }

    private fun syncStackArgumentsToCore(
        coreArguments: List<KLuaCoreValue>,
        stackArguments: List<LuaStackValue>,
    ) {
        val tableCache = seedCoreTableCache(stackArguments, coreArguments)
        for (index in coreArguments.indices) {
            val coreTable = coreArguments[index] as? KLuaCoreValue.TableValue ?: continue
            val stackTable = stackArguments.getOrNull(index) as? LuaStackValue.TableValue ?: continue
            syncStackTableToCore(stackTable, coreTable, tableCache)
        }
    }

    private fun seedCoreTableCache(
        stackValues: List<LuaStackValue>,
        coreValues: List<KLuaCoreValue>,
    ): MutableMap<LuaStackValue.TableValue, KLuaCoreValue.TableValue> {
        val tableCache = IdentityHashMap<LuaStackValue.TableValue, KLuaCoreValue.TableValue>()
        for (index in stackValues.indices) {
            val stackTable = stackValues[index] as? LuaStackValue.TableValue ?: continue
            val coreTable = coreValues.getOrNull(index) as? KLuaCoreValue.TableValue ?: continue
            seedCoreTableCache(stackTable, coreTable, tableCache)
        }
        return tableCache
    }

    private fun seedCoreTableCache(
        stackTable: LuaStackValue.TableValue,
        coreTable: KLuaCoreValue.TableValue,
        tableCache: MutableMap<LuaStackValue.TableValue, KLuaCoreValue.TableValue>,
    ) {
        if (tableCache.put(stackTable, coreTable) != null) {
            return
        }
        val stackMetatable = stackTable.metatable
        val coreMetatable = coreTable.metatable
        if (stackMetatable != null && coreMetatable != null) {
            seedCoreTableCache(stackMetatable, coreMetatable, tableCache)
        }
    }

    private fun syncStackTableToCore(
        stackTable: LuaStackValue.TableValue,
        coreTable: KLuaCoreValue.TableValue,
        tableCache: MutableMap<LuaStackValue.TableValue, KLuaCoreValue.TableValue>,
    ) {
        tableCache[stackTable] = coreTable
        coreTable.fields.clear()
        coreTable.fields.putAll(
            stackTable.fields.map { (fieldKey, fieldValue) ->
                fieldKey.toCoreTableFieldValue(tableCache) to fieldValue.toCoreTableFieldValue(tableCache)
            },
        )
        coreTable.metatable = stackTable.metatable?.toCoreTableValue(tableCache)
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
            is LuaStackValue.UserDataValue -> (value.value as? LuaTypedValue)?.luaTypeName ?: "userdata"
            is LuaStackValue.UnsupportedValue -> value.typeName
        }
    }

    private fun toApiStackFrames(frames: List<KLuaCoreStackFrame>): List<LuaStackFrame> {
        return frames.map { frame ->
            LuaStackFrame(
                frame.sourceName,
                frame.line,
                frame.lineDefined,
                frame.lastLineDefined,
                frame.locals.map { local ->
                    LuaLocalVariable(
                        local.name,
                        local.value.toStackValue().toPublicCallReturnValue(),
                    )
                },
            )
        }
    }

    private inner class CoreBackedLuaCoroutine(
        private val coroutine: KLuaCoreCoroutine,
    ) : LuaCoroutineHandle {
        override fun resume(arguments: List<Any?>): LuaCoroutineResult {
            val tableCache = IdentityHashMap<LuaStackValue.TableValue, KLuaCoreValue.TableValue>()
            val coreArguments = arguments.map { argument -> argument.toStackValue().toCoreValue(tableCache) }
            return when (val result = coroutine.resume(coreArguments)) {
                is KLuaCoreCoroutineExecution.Returned -> LuaCoroutineResult.Returned(
                    result.values.map { value -> value.toStackValue().toPublicCallReturnValue() },
                )
                is KLuaCoreCoroutineExecution.Yielded -> LuaCoroutineResult.Yielded(
                    result.values.map { value -> value.toStackValue().toPublicCallReturnValue() },
                )
                is KLuaCoreCoroutineExecution.RuntimeError -> LuaCoroutineResult.RuntimeError(
                    result.message,
                    sourceName = result.sourceName,
                    line = result.line,
                    cause = result.cause,
                    luaFrames = toApiStackFrames(result.luaFrames),
                )
            }
        }
    }

    private inner class DefaultLuaCallContext(
        private val arguments: List<LuaStackValue>,
        override val luaFrames: List<LuaStackFrame> = emptyList(),
        private val setLocalValue: ((level: Int, index: Int, value: Any?) -> String?)? = null,
        private val setDebugHookValue: ((index: Int, mask: String, count: Int) -> Boolean)? = null,
        private val getDebugHookValue: (() -> LuaReturn)? = null,
    ) : LuaCallContext {
        override val argumentCount: Int = arguments.size

        override fun isNil(index: Int): Boolean = valueAt(index) == LuaStackValue.Nil

        override fun isNone(index: Int): Boolean = valueAt(index) == null

        override fun isTable(index: Int): Boolean = valueAt(index) is LuaStackValue.TableValue

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
                is LuaStackValue.UserDataValue -> value.value
                else -> throw IllegalArgumentException("argument $index is ${stackTypeName(value)}")
            }
        }

        override fun call(index: Int, arguments: List<Any?>): LuaReturn {
            val function = valueAt(index) as? LuaStackValue.NativeFunctionValue
                ?: throw IllegalArgumentException("argument $index is ${typeName(index)}")
            return callFunction(function, arguments)
        }

        override fun call(function: Any?, arguments: List<Any?>): LuaReturn {
            val stackFunction = function.toStackValue()
            val nativeFunction = stackFunction as? LuaStackValue.NativeFunctionValue
                ?: throw IllegalArgumentException("value is ${stackTypeName(stackFunction)}")
            return callFunction(nativeFunction, arguments)
        }

        override fun yield(values: List<Any?>): Nothing {
            throw LuaYieldException(values)
        }

        override fun load(source: String, chunkName: String): LuaReturn {
            return loadLuaFunction(source, chunkName)
        }

        override fun getUpvalue(index: Int, upvalueIndex: Int): LuaReturn? {
            val function = valueAt(index) as? LuaStackValue.NativeFunctionValue ?: return null
            val coreFunction = function.coreFunction ?: return null
            val upvalue = KLuaCoreRuntime.getUpvalue(coreFunction, upvalueIndex, coreGlobals) ?: return null
            return LuaReturn.of(
                upvalue.name,
                upvalue.value.toStackValue().toPublicCallReturnValue(),
            )
        }

        override fun setUpvalue(index: Int, upvalueIndex: Int, value: Any?): String? {
            val function = valueAt(index) as? LuaStackValue.NativeFunctionValue ?: return null
            val coreFunction = function.coreFunction ?: return null
            return KLuaCoreRuntime.setUpvalue(
                coreFunction,
                upvalueIndex,
                value.toCoreReturnValue(),
                coreGlobals,
            )
        }

        override fun setLocal(level: Int, index: Int, value: Any?): String? {
            return setLocalValue?.invoke(level, index, value)
        }

        override fun setDebugHook(index: Int, mask: String, count: Int): Boolean {
            return setDebugHookValue?.invoke(index, mask, count) ?: false
        }

        override fun getDebugHook(): LuaReturn {
            return getDebugHookValue?.invoke() ?: LuaReturn.of(null)
        }

        private fun callFunction(function: LuaStackValue.NativeFunctionValue, arguments: List<Any?>): LuaReturn {
            return function.function.call(
                DefaultLuaCallContext(
                    arguments.map { argument -> argument.toStackValue() },
                    luaFrames,
                    setLocalValue,
                    setDebugHookValue,
                    getDebugHookValue,
                ),
            )
        }

        override fun getTable(index: Int): Any? {
            return valueAt(index) as? LuaStackValue.TableValue
        }

        override fun getTableValue(index: Int, key: Any?): Any? {
            val table = valueAt(index) as? LuaStackValue.TableValue ?: return null
            return table.fields[key.toStackValue()]?.toPublicCallReturnValue()
        }

        override fun getTableField(table: Any?, key: Any?): Any? {
            val tableValue = table as? LuaStackValue.TableValue ?: return null
            return tableValue.fields[key.toStackValue()]?.toPublicCallReturnValue()
        }

        override fun setTableValue(index: Int, key: Any?, value: Any?) {
            val table = valueAt(index) as? LuaStackValue.TableValue
                ?: throw IllegalArgumentException("argument $index is ${typeName(index)}")
            val stackKey = key.toStackValue()
            val stackValue = value.toStackValue()
            if (stackValue == LuaStackValue.Nil) {
                table.fields.remove(stackKey)
            } else {
                table.fields[stackKey] = stackValue
            }
        }

        override fun getMetatable(index: Int): Any? {
            val table = valueAt(index) as? LuaStackValue.TableValue
                ?: throw IllegalArgumentException("argument $index is ${typeName(index)}")
            return table.metatable
        }

        override fun setMetatable(index: Int, metatable: Any?) {
            val table = valueAt(index) as? LuaStackValue.TableValue
                ?: throw IllegalArgumentException("argument $index is ${typeName(index)}")
            table.metatable = when (val stackMetatable = metatable.toStackValue()) {
                LuaStackValue.Nil -> null
                is LuaStackValue.TableValue -> stackMetatable
                else -> throw IllegalArgumentException("metatable is ${stackTypeName(stackMetatable)}")
            }
        }

        override fun nextTableEntry(index: Int, key: Any?): List<Any?>? {
            val table = valueAt(index) as? LuaStackValue.TableValue
                ?: throw IllegalArgumentException("argument $index is ${typeName(index)}")
            val entries = table.fields.entries.toList()
            val nextIndex = if (key == null) {
                0
            } else {
                val stackKey = key.toStackValue()
                val currentIndex = entries.indexOfFirst { entry -> entry.key == stackKey }
                if (currentIndex < 0) {
                    throw IllegalArgumentException("invalid key to 'next'")
                }
                currentIndex + 1
            }
            val entry = entries.getOrNull(nextIndex) ?: return null
            return listOf(entry.key.toPublicCallReturnValue(), entry.value.toPublicCallReturnValue())
        }

        override fun tableLength(index: Int): Long? {
            val table = valueAt(index) as? LuaStackValue.TableValue ?: return null
            var length = 0L
            while (table.fields[LuaStackValue.IntegerValue(length + 1L)] != null) {
                length++
            }
            return length
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

        override fun toUserData(index: Int): Any? {
            return (valueAt(index) as? LuaStackValue.UserDataValue)?.value
        }

        override fun <T : Any> toUserData(index: Int, type: Class<T>): T? {
            val value = toUserData(index) ?: return null
            return if (type.isInstance(value)) type.cast(value) else null
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
            val fields: MutableMap<LuaStackValue, LuaStackValue> = linkedMapOf(),
            val coreValue: KLuaCoreValue.TableValue? = null,
        ) : LuaStackValue {
            var metatable: TableValue? = null
        }

        data class ChunkValue(
            val chunk: KLuaCoreChunk,
        ) : LuaStackValue

        data class NativeFunctionValue(
            val function: LuaFunction,
            val coreFunction: KLuaCoreValue.FunctionValue? = null,
        ) : LuaStackValue

        data class UserDataValue(
            val value: Any,
        ) : LuaStackValue

        data class UnsupportedValue(
            val typeName: String,
        ) : LuaStackValue
    }
}

private fun String?.toNativeFrames(): List<String> {
    return if (this == null) emptyList() else listOf(this)
}

private fun Class<*>.luaDebugName(): String {
    return simpleName.takeIf { it.isNotEmpty() } ?: name
}

class LuaYieldException internal constructor(
    val values: List<Any?>,
    internal val continuation: ((List<Any?>) -> LuaReturn)? = null,
) : RuntimeException(null, null, false, false)

fun LuaYieldException.withContinuation(continuation: (List<Any?>) -> LuaReturn): LuaYieldException {
    return LuaYieldException(values, continuation)
}

fun LuaYieldException.continueWith(arguments: List<Any?>): LuaReturn {
    return continuation?.invoke(arguments) ?: LuaReturn.ofValues(arguments)
}
