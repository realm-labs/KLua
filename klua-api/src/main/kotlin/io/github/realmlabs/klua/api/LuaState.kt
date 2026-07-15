package io.github.realmlabs.klua.api

import io.github.realmlabs.klua.core.KLuaCoreChunk
import io.github.realmlabs.klua.core.KLuaCoreCallContext
import io.github.realmlabs.klua.core.KLuaCoreCallResult
import io.github.realmlabs.klua.core.KLuaCoreBytecodeLoad
import io.github.realmlabs.klua.core.KLuaCoreComparison
import io.github.realmlabs.klua.core.KLuaCoreContinuation
import io.github.realmlabs.klua.core.KLuaCoreCoroutine
import io.github.realmlabs.klua.core.KLuaCoreCoroutineExecution
import io.github.realmlabs.klua.core.KLuaCoreDebugObserver
import io.github.realmlabs.klua.core.KLuaCoreDebugHook
import io.github.realmlabs.klua.core.KLuaCoreExecution
import io.github.realmlabs.klua.core.KLuaCoreExecutionLimits
import io.github.realmlabs.klua.core.KLuaCoreGlobals
import io.github.realmlabs.klua.core.KLuaCoreLoad
import io.github.realmlabs.klua.core.KLuaCoreRuntime
import io.github.realmlabs.klua.core.KLuaCoreStackFrame
import io.github.realmlabs.klua.core.KLuaCoreUserDataGetter
import io.github.realmlabs.klua.core.KLuaCoreUserDataMethod
import io.github.realmlabs.klua.core.KLuaCoreUserDataSetter
import io.github.realmlabs.klua.core.KLuaCoreValue
import java.math.BigInteger
import java.text.DecimalFormatSymbols
import java.util.IdentityHashMap
import java.util.Locale
import java.util.function.Consumer

private const val MAX_CALL_METAMETHOD_DEPTH = 16

class LuaState private constructor(
    val config: LuaConfig,
) {
    private val stack = mutableListOf<LuaStackValue>()
    private val globals = LuaStackValue.TableValue()
    private val coreGlobals = KLuaCoreGlobals.create()
    private val registryCore = KLuaCoreRuntime.createTable(coreGlobals).also { registry ->
        registry.fields[KLuaCoreValue.IntegerValue(1)] = KLuaCoreValue.BooleanValue(false)
    }
    private val registry = LuaStackValue.TableValue(coreValue = registryCore)
    private val userMetatables = IdentityHashMap<Any, LuaStackValue.TableValue>()
    private val coreBackedNativeGlobals = mutableSetOf<String>()
    private val userValues = IdentityHashMap<Any, MutableMap<Int, LuaStackValue>>()
    private val callMetamethodKey = LuaStackValue.StringValue("__call")
    private val indexMetamethodKey = LuaStackValue.StringValue("__index")
    private val newIndexMetamethodKey = LuaStackValue.StringValue("__newindex")
    private var lastError: LuaException? = null

    companion object {
        @JvmStatic
        @JvmOverloads
        fun create(config: LuaConfig = LuaConfig()): LuaState = LuaState(config.snapshot())
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

    fun loadBytecode(bytes: ByteArray): LuaStatus {
        return when (val result = KLuaCoreRuntime.loadBytecode(bytes)) {
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
    fun loadBytecodeResource(
        resourceName: String,
        classLoader: ClassLoader = defaultBytecodeResourceClassLoader(),
    ): LuaStatus {
        val bytes = try {
            readBytecodeResource(resourceName, classLoader)
        } catch (error: LuaSyntaxException) {
            lastError = error
            stack += LuaStackValue.StringValue(error.message ?: "failed to load KLua bytecode resource")
            return LuaStatus.SYNTAX_ERROR
        }
        return loadBytecode(bytes)
    }

    @JvmOverloads
    fun compileBytecode(source: String, chunkName: String = "chunk"): ByteArray {
        return when (val result = KLuaCoreRuntime.compileBytecode(source, chunkName)) {
            is KLuaCoreBytecodeLoad.Success -> result.bytes.copyOf()
            is KLuaCoreBytecodeLoad.SyntaxError -> throw LuaSyntaxException(result.message)
        }
    }

    @JvmOverloads
    fun pcall(argumentCount: Int, resultCount: Int = -1): LuaStatus {
        require(argumentCount >= 0) { "argumentCount must be non-negative" }
        require(resultCount >= -1) { "resultCount must be -1 or non-negative" }
        val functionIndex = stack.size - argumentCount - 1
        require(functionIndex in stack.indices) { "stack does not contain a callable value" }

        return pcallStackValue(functionIndex, stack[functionIndex], resultCount)
    }

    private fun pcallStackValue(
        functionIndex: Int,
        callable: LuaStackValue,
        resultCount: Int,
        depth: Int = 0,
    ): LuaStatus {
        return when (callable) {
            is LuaStackValue.ChunkValue -> pcallChunk(functionIndex, callable, resultCount)
            is LuaStackValue.NativeFunctionValue -> pcallNativeFunction(functionIndex, callable, resultCount)
            else -> {
                val callTarget = callMetamethod(callable)
                if (callTarget != null && depth < MAX_CALL_METAMETHOD_DEPTH) {
                    stack[functionIndex] = callTarget
                    stack.add(functionIndex + 1, callable)
                    pcallStackValue(functionIndex, callTarget, resultCount, depth + 1)
                } else {
                    runtimeCallError(functionIndex, attemptToCallMessage(callTarget ?: callable))
                }
            }
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
        val limits = KLuaCoreExecutionLimits(instructionLimit = config.instructionLimit)
        return when (val result = KLuaCoreRuntime.execute(chunk.chunk, arguments, coreGlobals, limits)) {
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
                    errorObject = result.errorObject?.toStackValue()?.toPublicCallReturnValue(),
                    hasErrorObject = result.errorObject != null,
                )
                removeCallFrame(functionIndex)
                stack += result.errorObject?.toStackValue() ?: LuaStackValue.StringValue(result.message)
                LuaStatus.RUNTIME_ERROR
            }
        }
    }

    private fun pcallNativeFunction(
        functionIndex: Int,
        function: LuaStackValue.NativeFunctionValue,
        resultCount: Int,
    ): LuaStatus {
        val coreFunction = function.coreFunction
        if (coreFunction != null && KLuaCoreRuntime.canCreateCoroutine(coreFunction)) {
            return pcallCoreLuaFunction(functionIndex, coreFunction, resultCount)
        }
        val arguments = stack.subList(functionIndex + 1, stack.size).toList()
        return try {
            val result = function.function.call(DefaultLuaCallContext(arguments))
            lastError = null
            removeCallFrame(functionIndex)
            pushHostResults(result.values, resultCount)
            LuaStatus.OK
        } catch (exit: LuaExitException) {
            throw exit
        } catch (exception: LuaException) {
            val runtimeException = exception as? LuaRuntimeException
            val hasErrorObject = runtimeException?.hasErrorObject == true
            val errorObject = if (hasErrorObject) {
                runtimeException.errorObject
            } else {
                exception.message ?: exception::class.java.simpleName
            }
            runtimeCallError(
                functionIndex,
                exception.message ?: exception::class.java.simpleName,
                exception,
                errorObject,
                hasErrorObject,
            )
        } catch (exception: RuntimeException) {
            runtimeCallError(functionIndex, exception.message ?: exception::class.java.simpleName, exception)
        }
    }

    private fun pcallCoreLuaFunction(
        functionIndex: Int,
        function: KLuaCoreValue.FunctionValue,
        resultCount: Int,
    ): LuaStatus {
        return try {
            val arguments = coreCallArguments(functionIndex)
            when (
                val result = KLuaCoreRuntime.callFunction(
                    function,
                    arguments,
                    coreGlobals,
                    isYieldable = false,
                    limits = KLuaCoreExecutionLimits(config.instructionLimit),
                )
            ) {
                is KLuaCoreCallResult.Success -> {
                    lastError = null
                    removeCallFrame(functionIndex)
                    pushResults(result.values, resultCount)
                    LuaStatus.OK
                }
                is KLuaCoreCallResult.Yielded -> throw result.toLuaYieldException()
                is KLuaCoreCallResult.RuntimeError -> throw coreRuntimeError(result)
            }
        } catch (exit: LuaExitException) {
            throw exit
        } catch (exception: LuaException) {
            val runtimeException = exception as? LuaRuntimeException
            val hasErrorObject = runtimeException?.hasErrorObject == true
            val errorObject = if (hasErrorObject) {
                runtimeException.errorObject
            } else {
                exception.message ?: exception::class.java.simpleName
            }
            runtimeCallError(
                functionIndex,
                exception.message ?: exception::class.java.simpleName,
                exception,
                errorObject,
                hasErrorObject,
            )
        } catch (exception: RuntimeException) {
            runtimeCallError(functionIndex, exception.message ?: exception::class.java.simpleName, exception)
        }
    }

    private fun coreCallArguments(functionIndex: Int): List<KLuaCoreValue> {
        val argumentCount = stack.size - functionIndex - 1
        if (argumentCount == 0) {
            return emptyList()
        }
        if (argumentCount == 1) {
            return listOf(stack[functionIndex + 1].toCoreCallArgument(1))
        }
        return ArrayList<KLuaCoreValue>(argumentCount).also { arguments ->
            for (index in 0 until argumentCount) {
                arguments += stack[functionIndex + 1 + index].toCoreCallArgument(index + 1)
            }
        }
    }

    private fun LuaStackValue.toCoreCallArgument(index: Int): KLuaCoreValue {
        return when (this) {
            is LuaStackValue.NativeFunctionValue -> function.toCoreFunctionValue()
            is LuaStackValue.ChunkValue,
            is LuaStackValue.UnsupportedValue,
            -> throw IllegalArgumentException("argument $index is ${stackTypeName(this)}")
            else -> toCoreValue()
        }
    }

    private fun runtimeCallError(
        functionIndex: Int,
        message: String,
        cause: Throwable? = null,
        errorObject: Any? = message,
        hasErrorObject: Boolean = false,
    ): LuaStatus {
        lastError = LuaRuntimeException(
            message,
            cause,
            errorObject = if (hasErrorObject) errorObject else null,
            hasErrorObject = hasErrorObject,
        )
        removeCallFrame(functionIndex)
        stack += if (hasErrorObject) errorObject.toStackValue() else message.toStackValue()
        return LuaStatus.RUNTIME_ERROR
    }

    private fun loadLuaFunction(
        source: String,
        chunkName: String,
        environment: Any? = null,
        environmentProvided: Boolean = false,
    ): LuaReturn {
        return when (val load = KLuaCoreRuntime.compile(source, chunkName)) {
            is KLuaCoreLoad.Success -> {
                val function = if (!environmentProvided) {
                    KLuaCoreRuntime.createChunkFunctionValue(load.chunk, coreGlobals)
                } else {
                    KLuaCoreRuntime.createChunkFunctionValue(load.chunk, coreGlobals, environment.toCoreReturnValue())
                        ?: return LuaReturn.of(null, "unsupported chunk environment")
                }
                LuaReturn.of(function.toStackValue().toPublicCallReturnValue())
            }
            is KLuaCoreLoad.SyntaxError -> LuaReturn.of(null, load.message)
        }
    }

    internal fun loadCoroutineFunction(source: String, chunkName: String): LuaCoroutineFunction {
        return when (val result = KLuaCoreRuntime.compile(source, chunkName)) {
            is KLuaCoreLoad.Success -> result.chunk.toCoroutineFunction()
            is KLuaCoreLoad.SyntaxError -> throw LuaSyntaxException(result.message)
        }
    }

    internal fun loadBytecodeCoroutineFunction(bytes: ByteArray): LuaCoroutineFunction {
        return when (val result = KLuaCoreRuntime.loadBytecode(bytes)) {
            is KLuaCoreLoad.Success -> result.chunk.toCoroutineFunction()
            is KLuaCoreLoad.SyntaxError -> throw LuaSyntaxException(result.message)
        }
    }

    private fun loadBytecodeFunction(bytes: ByteArray, environment: KLuaCoreValue?): LuaReturn {
        return when (val load = KLuaCoreRuntime.loadBytecode(bytes)) {
            is KLuaCoreLoad.Success -> {
                val functionValue = if (environment == null) {
                    KLuaCoreRuntime.createChunkFunctionValue(load.chunk, coreGlobals)
                } else {
                    KLuaCoreRuntime.createChunkFunctionValue(load.chunk, coreGlobals, environment)
                        ?: return LuaReturn.of(null, "unsupported chunk environment")
                }
                LuaReturn.of(LuaStackValue.NativeFunctionValue(toLuaFunctionValue(functionValue), functionValue))
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

    fun pushRegistryTable() {
        refreshRegistryCoreValue()
        registryCore.fields[KLuaCoreValue.IntegerValue(2)] = KLuaCoreRuntime.getGlobalTable(coreGlobals)
        refreshRegistryStackValue()
        stack += registry
    }

    fun setRegistryInteger(index: Long) {
        val value = requireValue(-1)
        stack.removeAt(stack.lastIndex)
        refreshRegistryCoreValue()
        val key = KLuaCoreValue.IntegerValue(index)
        if (value == LuaStackValue.Nil) {
            registryCore.fields.remove(key)
        } else {
            registryCore.fields[key] = value.toCoreValue()
        }
        KLuaCoreRuntime.syncTable(registryCore, coreGlobals)
        refreshRegistryStackValue()
    }

    fun pushRegistryInteger(index: Long) {
        refreshRegistryCoreValue()
        stack += registryCore.fields[KLuaCoreValue.IntegerValue(index)]?.toStackValue() ?: LuaStackValue.Nil
    }

    fun pushRegistrySubtable(name: String) {
        refreshRegistryCoreValue()
        val key = KLuaCoreValue.StringValue(name)
        val table = registryCore.fields[key] as? KLuaCoreValue.TableValue
            ?: KLuaCoreRuntime.createTable(coreGlobals).also {
                registryCore.fields[key] = it
                KLuaCoreRuntime.syncTable(registryCore, coreGlobals)
            }
        stack += table.toStackValue()
    }

    private fun refreshRegistryCoreValue() {
        val refreshed = KLuaCoreRuntime.refreshTable(registryCore, coreGlobals)
        if (refreshed !== registryCore) {
            registryCore.fields.clear()
            registryCore.fields.putAll(refreshed.fields)
            registryCore.metatable = refreshed.metatable
        }
    }

    private fun refreshRegistryStackValue() {
        val refreshed = registryCore.toStackValue() as LuaStackValue.TableValue
        registry.fields.clear()
        registry.fields.putAll(refreshed.fields)
        registry.metatable = refreshed.metatable
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

    fun setMetatable(index: Int) {
        val target = requireResolvedIndex(index)
        val metatable = requireValue(-1)
        stack.removeAt(stack.lastIndex)
        setStackMetatableAt(target, metatable.toMetatableValue(), index)
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
            is LuaStackValue.StringValue -> numberFromString(value.value) != null
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
            is LuaStackValue.StringValue -> integerFromString(value.value)
            else -> null
        }
    }

    fun toNumber(index: Int): Double? {
        return when (val value = valueAt(index)) {
            is LuaStackValue.IntegerValue -> value.value.toDouble()
            is LuaStackValue.NumberValue -> value.value
            is LuaStackValue.StringValue -> numberFromString(value.value)
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
                        context,
                        nativeFrameName = name,
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
        coreContext: KLuaCoreCallContext,
        nativeFrameName: String? = null,
    ): KLuaCoreCallResult {
        val arguments = coreContext.arguments
        val stackTableCache = if (arguments.any { value -> value is KLuaCoreValue.TableValue }) {
            IdentityHashMap<KLuaCoreValue.TableValue, LuaStackValue.TableValue>()
        } else {
            null
        }
        val stackArguments = arguments.map { it.toStackValue(stackTableCache) }
        return try {
            val result = function.call(
                CoreBackedLuaCallContext(stackArguments, coreContext),
            )
            syncStackArgumentsToCore(arguments, stackArguments)
            KLuaCoreCallResult.Success(
                result.values.map { value -> value.toCoreReturnValue(stackArguments, arguments) },
            )
        } catch (yield: LuaYieldException) {
            yield.toCoreYieldResult(stackArguments, arguments)
        } catch (exit: LuaExitException) {
            throw exit
        } catch (exception: LuaException) {
            syncStackArgumentsToCore(arguments, stackArguments)
            val errorObject = if (exception is LuaRuntimeException && exception.hasErrorObject) {
                exception.errorObject.toCoreReturnValue(stackArguments, arguments)
            } else {
                null
            }
            KLuaCoreCallResult.RuntimeError(
                exception.message ?: exception::class.java.simpleName,
                nativeFrames = nativeFrameName.toNativeFrames(),
                errorObject = errorObject,
            )
        } catch (exception: RuntimeException) {
            syncStackArgumentsToCore(arguments, stackArguments)
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
        } catch (exit: LuaExitException) {
            throw exit
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
        } catch (exit: LuaExitException) {
            throw exit
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
        } catch (exit: LuaExitException) {
            throw exit
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
        return toStackValue(null)
    }

    private fun KLuaCoreValue?.toStackValue(
        tableCache: MutableMap<KLuaCoreValue.TableValue, LuaStackValue.TableValue>?,
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
                val resolvedTableCache = tableCache ?: IdentityHashMap()
                val cached = resolvedTableCache[this]
                if (cached != null) {
                    return cached
                }
                val tableValue = LuaStackValue.TableValue(coreValue = this)
                resolvedTableCache[this] = tableValue
                tableValue.fields.putAll(
                    fields.map { (fieldKey, fieldValue) ->
                        fieldKey.toStackValue(resolvedTableCache) to fieldValue.toStackValue(resolvedTableCache)
                    },
                )
                tableValue.metatable = metatable?.toStackValue(resolvedTableCache) as? LuaStackValue.TableValue
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
                    val coroutine = KLuaCoreRuntime.createCoroutine(
                        functionValue,
                        coreGlobals,
                        KLuaCoreExecutionLimits(config.instructionLimit),
                    )
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
        return when (
            val result = KLuaCoreRuntime.callFunction(
                functionValue,
                arguments,
                coreGlobals,
                context.isYieldable,
                KLuaCoreExecutionLimits(config.instructionLimit),
            )
        ) {
            is KLuaCoreCallResult.Success -> LuaReturn.ofValues(
                result.values.map { it.toStackValue().toPublicCallReturnValue() },
            )
            is KLuaCoreCallResult.Yielded -> throw result.toLuaYieldException()
            is KLuaCoreCallResult.RuntimeError -> throw coreRuntimeError(result)
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
            is KLuaCoreCallResult.RuntimeError -> throw coreRuntimeError(result)
        }
    }

    private fun coreRuntimeError(result: KLuaCoreCallResult.RuntimeError): LuaRuntimeException {
        val errorObject = result.errorObject?.toStackValue()?.toPublicCallReturnValue()
        return if (result.errorObject == null) {
            LuaRuntimeException(result.message)
        } else {
            LuaRuntimeException(result.message, errorObject = errorObject, hasErrorObject = true)
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
                    context,
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
            val tableCache = seedCoreTableCache(stackArguments, coreArguments) ?: IdentityHashMap()
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
        } catch (exit: LuaExitException) {
            throw exit
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
            if (value != null) {
                fields[key.toStackValue()] = value.toStackValue()
            }
        }
        return LuaStackValue.TableValue(fields)
    }

    private fun Map<*, *>.toCoreTableValue(): KLuaCoreValue.TableValue {
        val fields = linkedMapOf<KLuaCoreValue, KLuaCoreValue>()
        for ((key, value) in this) {
            if (key == null) {
                throw LuaRuntimeException("table index is nil")
            }
            if (value != null) {
                fields[key.toCoreReturnValue()] = value.toCoreReturnValue()
            }
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
        return toCoreValue(null)
    }

    private fun LuaStackValue.toCoreValue(
        tableCache: MutableMap<LuaStackValue.TableValue, KLuaCoreValue.TableValue>?,
    ): KLuaCoreValue {
        return when (this) {
            LuaStackValue.Nil -> KLuaCoreValue.Nil
            is LuaStackValue.BooleanValue -> KLuaCoreValue.BooleanValue(value)
            is LuaStackValue.IntegerValue -> KLuaCoreValue.IntegerValue(value)
            is LuaStackValue.NumberValue -> KLuaCoreValue.NumberValue(value)
            is LuaStackValue.StringValue -> KLuaCoreValue.StringValue(value)
            is LuaStackValue.ChunkValue -> KLuaCoreValue.UnsupportedValue("function")
            is LuaStackValue.NativeFunctionValue -> coreFunction ?: function.toCoreFunctionValue()
            is LuaStackValue.TableValue -> toCoreTableValue(tableCache ?: IdentityHashMap())
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
        val tableCache = seedCoreTableCache(stackArguments, coreArguments) ?: return
        for (index in coreArguments.indices) {
            val coreTable = coreArguments[index] as? KLuaCoreValue.TableValue ?: continue
            val stackTable = stackArguments.getOrNull(index) as? LuaStackValue.TableValue ?: continue
            syncStackTableToCore(stackTable, coreTable, tableCache)
        }
    }

    private fun seedCoreTableCache(
        stackValues: List<LuaStackValue>,
        coreValues: List<KLuaCoreValue>,
    ): MutableMap<LuaStackValue.TableValue, KLuaCoreValue.TableValue>? {
        var tableCache: MutableMap<LuaStackValue.TableValue, KLuaCoreValue.TableValue>? = null
        for (index in stackValues.indices) {
            val stackTable = stackValues[index] as? LuaStackValue.TableValue ?: continue
            val coreTable = coreValues.getOrNull(index) as? KLuaCoreValue.TableValue ?: continue
            val resolvedTableCache = tableCache
                ?: IdentityHashMap<LuaStackValue.TableValue, KLuaCoreValue.TableValue>().also {
                    tableCache = it
                }
            seedCoreTableCache(stackTable, coreTable, resolvedTableCache)
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
        if (stackTable.coreValue == null) {
            coreTable.fields.clear()
        }
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
        if (!value.isFinite() || value < Long.MIN_VALUE.toDouble() || value >= LUA_INTEGER_EXCLUSIVE_UPPER_BOUND) {
            return null
        }
        val integer = value.toLong()
        return if (integer.toDouble() == value) integer else null
    }

    private fun integerFromString(value: String): Long? {
        val trimmed = value.trimLuaAsciiWhitespace()
        if (trimmed.isEmpty()) {
            return null
        }
        val normalized = trimmed.normalizeLuaNumberDecimalPoint()
        return hexIntegerFromString(trimmed)
            ?: normalized.toLongOrNull()
            ?: normalized.luaFloatFromString()?.let(::integerFromNumber)
    }

    private fun KLuaCoreChunk.toCoroutineFunction(): LuaCoroutineFunction {
        val functionValue = KLuaCoreRuntime.createChunkFunctionValue(this, coreGlobals)
        return toLuaFunctionValue(functionValue) as? LuaCoroutineFunction
            ?: throw LuaRuntimeException("loaded Lua chunk is not coroutine-capable")
    }

    private fun numberFromString(value: String): Double? {
        val trimmed = value.trimLuaAsciiWhitespace()
        if (trimmed.isEmpty()) {
            return null
        }
        val normalized = trimmed.normalizeLuaNumberDecimalPoint()
        val parsed = hexIntegerFromString(trimmed)?.toDouble()
            ?: normalized.luaFloatFromString()
            ?: return null
        return parsed.takeIf { number -> number.isFinite() }
    }

    private fun String.luaFloatFromString(): Double? {
        val parseable = if (isHexNumeral() && indexOf('p', ignoreCase = true) < 0) "${this}p0" else this
        return parseable.toDoubleOrNull()
    }

    private fun String.normalizeLuaNumberDecimalPoint(): String {
        val decimalPoint = luaLocaleDecimalPoint()
        if (decimalPoint == '.' || decimalPoint !in this || '.' in this) {
            return this
        }
        return replace(decimalPoint, '.')
    }

    private fun luaLocaleDecimalPoint(): Char {
        return DecimalFormatSymbols.getInstance(Locale.getDefault()).decimalSeparator
    }

    private fun String.trimLuaAsciiWhitespace(): String {
        return trim { char -> char == ' ' || char == '\u000C' || char == '\n' || char == '\r' || char == '\t' || char == '\u000B' }
    }

    private fun hexIntegerFromString(value: String): Long? {
        val sign = when {
            value.startsWith("-") -> -1
            value.startsWith("+") -> 1
            else -> 1
        }
        val digitsStart = if (value.startsWith("-") || value.startsWith("+")) 1 else 0
        if (!value.regionMatches(digitsStart, "0x", 0, 2, ignoreCase = true)) {
            return null
        }
        val digits = value.substring(digitsStart + 2)
        if (digits.isEmpty() || digits.any { digit -> digit.asciiDigitToIntOrNull(16) == null }) {
            return null
        }
        var parsed = BigInteger.ZERO
        val radix = BigInteger.valueOf(16L)
        for (digit in digits) {
            val hexDigit = digit.asciiDigitToIntOrNull(16) ?: return null
            parsed = parsed.multiply(radix).add(BigInteger.valueOf(hexDigit.toLong()))
        }
        if (sign < 0) {
            parsed = parsed.negate()
        }
        return parsed.mod(UINT64_MODULUS).toLong()
    }

    private fun String.isHexNumeral(): Boolean {
        val digitsStart = if (startsWith("-") || startsWith("+")) 1 else 0
        return regionMatches(digitsStart, "0x", 0, 2, ignoreCase = true)
    }

    private fun Char.asciiDigitToIntOrNull(base: Int): Int? {
        val value = when (this) {
            in '0'..'9' -> code - '0'.code
            in 'a'..'z' -> code - 'a'.code + 10
            in 'A'..'Z' -> code - 'A'.code + 10
            else -> return null
        }
        return value.takeIf { it < base }
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

    private fun LuaStackValue.toMetatableValue(): LuaStackValue.TableValue? {
        return when (this) {
            LuaStackValue.Nil -> null
            is LuaStackValue.TableValue -> this
            else -> throw IllegalArgumentException("metatable is ${stackTypeName(this)}")
        }
    }

    private fun setStackMetatableAt(
        target: Int,
        newMetatable: LuaStackValue.TableValue?,
        originalIndex: Int,
    ) {
        setValueMetatable(stack.getOrNull(target), newMetatable, originalIndex)
    }

    private fun setValueMetatable(
        value: LuaStackValue?,
        newMetatable: LuaStackValue.TableValue?,
        originalIndex: Int,
    ) {
        if (value == null) {
            throw IllegalArgumentException("argument $originalIndex is none")
        }
        when (value) {
            is LuaStackValue.TableValue -> value.metatable = newMetatable
            is LuaStackValue.UserDataValue -> {
                if (newMetatable == null) {
                    userMetatables.remove(value.value)
                    coreGlobals.setUserDataMetatable(value.value, null)
                } else {
                    userMetatables[value.value] = newMetatable
                    coreGlobals.setUserDataMetatable(
                        value.value,
                        newMetatable.toCoreValue() as KLuaCoreValue.TableValue,
                    )
                }
                KLuaCoreRuntime.setUserDataMetatable(
                    coreGlobals,
                    value.value,
                    newMetatable?.toCoreTableValue(IdentityHashMap()),
                )
            }
            else -> {
                val typeName = stackTypeName(value)
                if (typeName !in RAW_TYPE_METATABLE_TYPES) {
                    throw IllegalArgumentException("argument $originalIndex is $typeName")
                }
                KLuaCoreRuntime.setRawTypeMetatable(
                    coreGlobals,
                    typeName,
                    newMetatable?.toCoreTableValue(IdentityHashMap()),
                )
            }
        }
    }

    private fun rawEqualStackValues(left: LuaStackValue?, right: LuaStackValue?): Boolean {
        if (stackTypeName(left) != stackTypeName(right)) {
            return false
        }
        return when (left) {
            LuaStackValue.Nil -> true
            is LuaStackValue.BooleanValue -> left.value == (right as LuaStackValue.BooleanValue).value
            is LuaStackValue.IntegerValue -> rawNumberEqual(left, right)
            is LuaStackValue.NumberValue -> rawNumberEqual(left, right)
            is LuaStackValue.StringValue -> left.value == (right as LuaStackValue.StringValue).value
            is LuaStackValue.TableValue -> left === right
            is LuaStackValue.ChunkValue -> left === right
            is LuaStackValue.NativeFunctionValue -> {
                val rightFunction = right as LuaStackValue.NativeFunctionValue
                when {
                    left.coreFunction != null && rightFunction.coreFunction != null ->
                        KLuaCoreRuntime.sameFunctionIdentity(left.coreFunction, rightFunction.coreFunction)
                    else -> left.function === rightFunction.function
                }
            }
            is LuaStackValue.UserDataValue -> left.value === (right as LuaStackValue.UserDataValue).value
            is LuaStackValue.UnsupportedValue -> left === right
            null -> right == null
        }
    }

    private fun rawNumberEqual(left: LuaStackValue?, right: LuaStackValue?): Boolean {
        return when (left) {
            is LuaStackValue.IntegerValue -> when (right) {
                is LuaStackValue.IntegerValue -> left.value == right.value
                is LuaStackValue.NumberValue -> integerFromNumber(right.value)?.let { left.value == it } ?: false
                else -> false
            }
            is LuaStackValue.NumberValue -> when (right) {
                is LuaStackValue.IntegerValue -> integerFromNumber(left.value)?.let { it == right.value } ?: false
                is LuaStackValue.NumberValue -> left.value == right.value
                else -> false
            }
            else -> false
        }
    }

    private fun attemptToCallMessage(value: LuaStackValue?): String {
        return "attempt to call a ${stackTypeName(value)} value"
    }

    private fun callMetamethod(value: LuaStackValue?): LuaStackValue? {
        val metatable = when (value) {
            is LuaStackValue.TableValue -> value.metatable
            is LuaStackValue.UserDataValue -> userMetatables[value.value]
            null -> null
            else -> KLuaCoreRuntime.getRawTypeMetatable(coreGlobals, stackTypeName(value))?.toStackValue()
        }
        return (metatable as? LuaStackValue.TableValue)?.fields?.get(callMetamethodKey)
    }

    private fun toApiStackFrames(frames: List<KLuaCoreStackFrame>): List<LuaStackFrame> {
        return frames.map { frame ->
            LuaStackFrame(
                sourceName = frame.sourceName,
                line = frame.line,
                lineDefined = frame.lineDefined,
                lastLineDefined = frame.lastLineDefined,
                upvalueCount = frame.upvalueCount,
                parameterCount = frame.parameterCount,
                isVararg = frame.isVararg,
                activeLines = frame.activeLines,
                function = frame.function?.toStackValue()?.toPublicCallReturnValue(),
                varargs = frame.varargs.map { value -> value.toStackValue().toPublicCallReturnValue() },
                locals = frame.locals.map { local ->
                    LuaLocalVariable(
                        local.name,
                        local.value.toStackValue().toPublicCallReturnValue(),
                    )
                },
                upvalues = frame.upvalues.map { upvalue ->
                    LuaUpvalueVariable(
                        upvalue.name,
                        upvalue.value.toStackValue().toPublicCallReturnValue(),
                    )
                },
                globals = frame.globals.map { global ->
                    LuaLocalVariable(
                        global.name,
                        global.value.toStackValue().toPublicCallReturnValue(),
                    )
                },
                callSiteName = frame.callSiteName,
                callSiteNameWhat = frame.callSiteNameWhat,
                transferStart = frame.transferStart,
                transferCount = frame.transferCount,
            )
        }
    }

    private inner class CoreBackedLuaCoroutine(
        private val coroutine: KLuaCoreCoroutine,
    ) : LuaDebuggableCoroutineHandle {
        override fun resume(arguments: List<Any?>): LuaCoroutineResult {
            val stackArguments = arguments.map { argument -> argument.toStackValue() }
            val tableCache = if (stackArguments.any { value -> value is LuaStackValue.TableValue }) {
                IdentityHashMap<LuaStackValue.TableValue, KLuaCoreValue.TableValue>()
            } else {
                null
            }
            val coreArguments = stackArguments.map { argument -> argument.toCoreValue(tableCache) }
            return when (val result = coroutine.resume(coreArguments)) {
                is KLuaCoreCoroutineExecution.Returned -> LuaCoroutineResult.Returned(
                    result.values.map { value -> value.toStackValue().toPublicCallReturnValue() },
                )
                is KLuaCoreCoroutineExecution.Yielded -> LuaCoroutineResult.Yielded(
                    result.values.map { value -> value.toStackValue().toPublicCallReturnValue() },
                )
                KLuaCoreCoroutineExecution.DebugSuspended -> LuaCoroutineResult.DebugSuspended
                is KLuaCoreCoroutineExecution.RuntimeError -> LuaCoroutineResult.RuntimeError(
                    result.message,
                    sourceName = result.sourceName,
                    line = result.line,
                    cause = result.cause,
                    luaFrames = toApiStackFrames(result.luaFrames),
                    errorObject = result.errorObject?.toStackValue()?.toPublicCallReturnValue(),
                    hasErrorObject = result.errorObject != null,
                )
            }
        }

        override fun close(): LuaCoroutineResult {
            return when (val result = coroutine.close()) {
                is KLuaCoreCoroutineExecution.Returned -> LuaCoroutineResult.Returned(
                    result.values.map { value -> value.toStackValue().toPublicCallReturnValue() },
                )
                is KLuaCoreCoroutineExecution.Yielded -> LuaCoroutineResult.RuntimeError(
                    "attempt to yield while closing coroutine",
                )
                KLuaCoreCoroutineExecution.DebugSuspended -> LuaCoroutineResult.RuntimeError(
                    "attempt to suspend while closing coroutine",
                )
                is KLuaCoreCoroutineExecution.RuntimeError -> LuaCoroutineResult.RuntimeError(
                    result.message,
                    sourceName = result.sourceName,
                    line = result.line,
                    cause = result.cause,
                    luaFrames = toApiStackFrames(result.luaFrames),
                    errorObject = result.errorObject?.toStackValue()?.toPublicCallReturnValue(),
                    hasErrorObject = result.errorObject != null,
                )
            }
        }

        override val luaFrames: List<LuaStackFrame>
            get() = toApiStackFrames(coroutine.luaFrames)

        override fun setLocal(level: Int, index: Int, value: Any?): String? {
            return coroutine.setLocal(level, index, value.toCoreReturnValue())
        }

        override fun setDebugHook(function: Any?, mask: String, count: Int): Boolean {
            return coroutine.setDebugHook(function?.toCoreReturnValue(), mask, count)
        }

        override fun getDebugHook(): LuaReturn {
            return coroutine.getDebugHook()?.toLuaReturn() ?: LuaReturn.of(null)
        }

        override fun setDebugObserver(observer: LuaDebugObserver?): Boolean {
            if (!config.debugEnabled && observer != null) {
                return false
            }
            coroutine.setDebugObserver(
                observer?.let { publicObserver ->
                    KLuaCoreDebugObserver { event, sourceId, line, callDepth ->
                        publicObserver.shouldSuspend(LuaDebugEvent.valueOf(event.name), sourceId, line, callDepth)
                    }
                },
            )
            return true
        }
    }

    private open inner class DefaultLuaCallContext(
        private val arguments: List<LuaStackValue>,
        private val luaFramesProvider: () -> List<LuaStackFrame> = { emptyList() },
        private val setLocalValue: ((level: Int, index: Int, value: Any?) -> String?)? = null,
        private val setDebugHookValue: ((index: Int, mask: String, count: Int) -> Boolean)? = null,
        private val getDebugHookValue: (() -> LuaReturn)? = null,
        override val isYieldable: Boolean = false,
    ) : LuaCallContext {
        private var cachedLuaFrames: List<LuaStackFrame>? = null

        open override val luaFrames: List<LuaStackFrame>
            get() = cachedLuaFrames ?: luaFramesProvider().also { frames -> cachedLuaFrames = frames }

        override val argumentCount: Int = arguments.size

        override fun isNil(index: Int): Boolean = valueAt(index) == LuaStackValue.Nil

        override fun isNone(index: Int): Boolean = valueAt(index) == null

        override fun isTable(index: Int): Boolean = valueAt(index) is LuaStackValue.TableValue

        override fun isTableValue(value: Any?): Boolean = value is LuaStackValue.TableValue

        override fun isFunctionValue(value: Any?): Boolean = value is LuaStackValue.NativeFunctionValue || value is LuaFunction

        override fun valueTypeName(value: Any?): String {
            return when (value) {
                is LuaStackValue.TableValue -> "table"
                is LuaStackValue.NativeFunctionValue -> "function"
                is LuaStackValue.UserDataValue -> (value.value as? LuaTypedValue)?.luaTypeName ?: "userdata"
                is LuaStackValue -> stackTypeName(value)
                else -> super.valueTypeName(value)
            }
        }

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

        override fun getLuaValue(index: Int): Any? {
            return valueAt(index)?.toPublicCallReturnValue()
        }

        override fun rawEquals(leftIndex: Int, rightIndex: Int): Boolean {
            return rawStackEquals(valueAt(leftIndex), valueAt(rightIndex))
        }

        override fun luaEquals(leftIndex: Int, rightIndex: Int): Boolean {
            val tableCache = IdentityHashMap<LuaStackValue.TableValue, KLuaCoreValue.TableValue>()
            val left = valueAt(leftIndex)?.toCoreValue(tableCache) ?: KLuaCoreValue.Nil
            val right = valueAt(rightIndex)?.toCoreValue(tableCache) ?: KLuaCoreValue.Nil
            return luaEqualsCoreValues(left, right)
        }

        private fun rawStackEquals(left: LuaStackValue?, right: LuaStackValue?): Boolean {
            if (left == null || right == null) {
                return false
            }
            return when {
                left.isNumberValue() && right.isNumberValue() -> rawNumberEqual(left, right)
                left is LuaStackValue.TableValue && right is LuaStackValue.TableValue -> left === right
                left is LuaStackValue.UserDataValue && right is LuaStackValue.UserDataValue -> left.value === right.value
                left is LuaStackValue.NativeFunctionValue && right is LuaStackValue.NativeFunctionValue -> {
                    val leftCoreFunction = left.coreFunction
                    val rightCoreFunction = right.coreFunction
                    if (leftCoreFunction != null && rightCoreFunction != null) {
                        KLuaCoreRuntime.sameFunctionIdentity(leftCoreFunction, rightCoreFunction)
                    } else if (leftCoreFunction != null || rightCoreFunction != null) {
                        false
                    } else {
                        left.function === right.function
                    }
                }
                else -> left == right
            }
        }

        private fun LuaStackValue?.isNumberValue(): Boolean {
            return this is LuaStackValue.IntegerValue || this is LuaStackValue.NumberValue
        }

        override fun equal(leftIndex: Int, rightIndex: Int): Boolean? {
            val tableCache = IdentityHashMap<LuaStackValue.TableValue, KLuaCoreValue.TableValue>()
            val left = valueAt(leftIndex)?.toCoreValue(tableCache) ?: KLuaCoreValue.Nil
            val right = valueAt(rightIndex)?.toCoreValue(tableCache) ?: KLuaCoreValue.Nil
            return equalCoreValues(left, right)
        }

        override fun lessThan(leftIndex: Int, rightIndex: Int): Boolean? {
            val tableCache = IdentityHashMap<LuaStackValue.TableValue, KLuaCoreValue.TableValue>()
            val left = valueAt(leftIndex)?.toCoreValue(tableCache) ?: KLuaCoreValue.Nil
            val right = valueAt(rightIndex)?.toCoreValue(tableCache) ?: KLuaCoreValue.Nil
            return lessThanCoreValues(left, right)
        }

        override fun lessThanValues(left: Any?, right: Any?): Boolean? {
            val tableCache = IdentityHashMap<LuaStackValue.TableValue, KLuaCoreValue.TableValue>()
            return lessThanCoreValues(
                left.toStackValue().toCoreValue(tableCache),
                right.toStackValue().toCoreValue(tableCache),
            )
        }

        private fun equalCoreValues(left: KLuaCoreValue, right: KLuaCoreValue): Boolean {
            return when (val comparison = KLuaCoreRuntime.equal(left, right, coreGlobals)) {
                is KLuaCoreComparison.Success -> comparison.value
                is KLuaCoreComparison.RuntimeError -> throw nativeComparisonError(comparison)
            }
        }

        private fun lessThanCoreValues(left: KLuaCoreValue, right: KLuaCoreValue): Boolean {
            return when (val comparison = KLuaCoreRuntime.lessThan(left, right, coreGlobals)) {
                is KLuaCoreComparison.Success -> comparison.value
                is KLuaCoreComparison.RuntimeError -> throw nativeComparisonError(comparison)
            }
        }

        private fun luaEqualsCoreValues(left: KLuaCoreValue, right: KLuaCoreValue): Boolean {
            return when (val comparison = KLuaCoreRuntime.luaEquals(left, right, coreGlobals)) {
                is KLuaCoreComparison.Success -> comparison.value
                is KLuaCoreComparison.RuntimeError -> throw nativeComparisonError(comparison)
            }
        }

        private fun nativeComparisonError(comparison: KLuaCoreComparison.RuntimeError): LuaRuntimeException {
            val message = if (comparison.message == "attempt to yield from outside a coroutine") {
                "attempt to yield across a C-call boundary"
            } else {
                comparison.message
            }
            return LuaRuntimeException(
                message,
                sourceName = comparison.sourceName,
                line = comparison.line,
                cause = comparison.cause,
                luaFrames = toApiStackFrames(comparison.luaFrames),
                errorObject = comparison.errorObject?.toStackValue()?.toPublicCallReturnValue(),
                hasErrorObject = comparison.errorObject != null,
            )
        }

        override fun call(index: Int, arguments: List<Any?>): LuaReturn {
            return callStackValue(valueAt(index), arguments)
        }

        override fun call(function: Any?, arguments: List<Any?>): LuaReturn {
            return callStackValue(function.toStackValue(), arguments)
        }

        override fun yield(values: List<Any?>): Nothing {
            throw LuaYieldException(values)
        }

        override fun load(
            source: String,
            chunkName: String,
            environment: Any?,
            environmentProvided: Boolean,
        ): LuaReturn {
            return loadLuaFunction(source, chunkName, environment, environmentProvided)
        }

        override fun loadBytecode(bytes: ByteArray, chunkName: String): LuaReturn {
            return loadBytecodeFunction(bytes, environment = null)
        }

        override fun loadBytecode(bytes: ByteArray, chunkName: String, environment: Any?): LuaReturn {
            val tableCache = IdentityHashMap<LuaStackValue.TableValue, KLuaCoreValue.TableValue>()
            val environmentValue = environment.toStackValue().toCoreValue(tableCache)
            return loadBytecodeFunction(bytes, environmentValue)
        }

        override fun getFunctionDebugInfo(index: Int): LuaFunctionDebugInfo? {
            val function = valueAt(index) as? LuaStackValue.NativeFunctionValue ?: return null
            val coreFunction = function.coreFunction ?: return null
            val info = KLuaCoreRuntime.getFunctionDebugInfo(coreFunction) ?: return null
            return LuaFunctionDebugInfo(
                sourceName = info.sourceName,
                lineDefined = info.lineDefined,
                lastLineDefined = info.lastLineDefined,
                upvalueCount = info.upvalueCount,
                parameterCount = info.parameterCount,
                isVararg = info.isVararg,
                activeLines = info.activeLines,
                parameterNames = info.parameterNames,
                localNames = info.localNames,
            )
        }

        override fun dumpFunctionBytecode(index: Int, strip: Boolean): ByteArray? {
            val function = valueAt(index) as? LuaStackValue.NativeFunctionValue ?: return null
            val coreFunction = function.coreFunction ?: return null
            return KLuaCoreRuntime.dumpFunctionBytecode(coreFunction, strip)
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

        override fun getUpvalueId(index: Int, upvalueIndex: Int): Any? {
            val function = valueAt(index) as? LuaStackValue.NativeFunctionValue ?: return null
            val coreFunction = function.coreFunction ?: return null
            return KLuaCoreRuntime.getUpvalueId(coreFunction, upvalueIndex)
                ?.toStackValue()
                ?.toPublicCallReturnValue()
        }

        override fun joinUpvalue(index: Int, upvalueIndex: Int, otherIndex: Int, otherUpvalueIndex: Int): Boolean {
            val function = valueAt(index) as? LuaStackValue.NativeFunctionValue ?: return false
            val otherFunction = valueAt(otherIndex) as? LuaStackValue.NativeFunctionValue ?: return false
            val coreFunction = function.coreFunction ?: return false
            val otherCoreFunction = otherFunction.coreFunction ?: return false
            return KLuaCoreRuntime.joinUpvalue(coreFunction, upvalueIndex, otherCoreFunction, otherUpvalueIndex)
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

        open override fun setLocal(level: Int, index: Int, value: Any?): String? {
            return setLocalValue?.invoke(level, index, value)
        }

        open override fun setDebugHook(index: Int, mask: String, count: Int): Boolean {
            return setDebugHookValue?.invoke(index, mask, count) ?: false
        }

        open override fun getDebugHook(): LuaReturn {
            return getDebugHookValue?.invoke() ?: LuaReturn.of(null)
        }

        private fun callFunction(function: LuaStackValue.NativeFunctionValue, arguments: List<Any?>): LuaReturn {
            function.coreFunction?.let { coreFunction ->
                return callCoreFunctionValue(
                    coreFunction,
                    DefaultLuaCallContext(
                        arguments.map { argument -> argument.toStackValue() },
                        { luaFrames },
                        setLocalValue,
                        setDebugHookValue,
                        getDebugHookValue,
                        isYieldable,
                    ),
                )
            }
            return function.function.call(
                DefaultLuaCallContext(
                    arguments.map { argument -> argument.toStackValue() },
                    { luaFrames },
                    setLocalValue,
                    setDebugHookValue,
                    getDebugHookValue,
                    isYieldable,
                ),
            )
        }

        private fun callStackValue(
            value: LuaStackValue?,
            arguments: List<Any?>,
            depth: Int = 0,
        ): LuaReturn {
            if (value is LuaStackValue.NativeFunctionValue) {
                return callFunction(value, arguments)
            }
            val callTarget = callMetamethod(value)
            if (callTarget != null && depth < MAX_CALL_METAMETHOD_DEPTH) {
                return callStackValue(
                    callTarget,
                    listOf(value?.toPublicCallReturnValue()) + arguments,
                    depth + 1,
                )
            }
            throw IllegalArgumentException(attemptToCallMessage(callTarget ?: value))
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

        override fun getValueField(value: Any?, key: Any?): Any? {
            return stackIndexValue(
                value.toStackValue(),
                key.toStackValue(),
                java.util.Collections.newSetFromMap(IdentityHashMap()),
            ).toPublicCallReturnValue()
        }

        private fun stackIndexValue(
            receiver: LuaStackValue,
            key: LuaStackValue,
            visited: MutableSet<LuaStackValue.TableValue>,
        ): LuaStackValue {
            return when (receiver) {
                is LuaStackValue.TableValue -> stackTableValue(receiver, key, visited)
                else -> {
                    val metatable = stackValueMetatable(receiver) ?: throw IllegalArgumentException(
                        attemptToIndexMessage(receiver),
                    )
                    stackMetamethodIndexValue(receiver, key, metatable.fields[indexMetamethodKey], visited)
                }
            }
        }

        private fun stackTableValue(
            table: LuaStackValue.TableValue,
            key: LuaStackValue,
            visited: MutableSet<LuaStackValue.TableValue>,
        ): LuaStackValue {
            val rawValue = table.fields[key]
            if (rawValue != null) {
                return rawValue
            }
            if (!visited.add(table)) {
                throw IllegalArgumentException("cycle in __index chain")
            }
            return stackMetamethodIndexValue(table, key, table.metatable?.fields?.get(indexMetamethodKey), visited)
        }

        private fun stackMetamethodIndexValue(
            receiver: LuaStackValue,
            key: LuaStackValue,
            index: LuaStackValue?,
            visited: MutableSet<LuaStackValue.TableValue>,
        ): LuaStackValue {
            return when (index) {
                null,
                LuaStackValue.Nil,
                -> LuaStackValue.Nil
                is LuaStackValue.TableValue -> stackTableValue(index, key, visited)
                is LuaStackValue.NativeFunctionValue -> callFunction(
                    index,
                    listOf(receiver.toPublicCallReturnValue(), key.toPublicCallReturnValue()),
                ).values.getOrNull(0).toStackValue()
                else -> stackIndexValue(index, key, visited)
            }
        }

        private fun stackValueMetatable(value: LuaStackValue): LuaStackValue.TableValue? {
            return when (value) {
                is LuaStackValue.TableValue -> value.metatable
                is LuaStackValue.UserDataValue -> {
                    val typeName = stackTypeName(value)
                    if (typeName in RAW_TYPE_METATABLE_TYPES) {
                        KLuaCoreRuntime.getRawTypeMetatable(coreGlobals, typeName)?.toStackValue()
                    } else {
                        userMetatables[value.value]
                    }
                }
                else -> KLuaCoreRuntime.getRawTypeMetatable(coreGlobals, stackTypeName(value))?.toStackValue()
            } as? LuaStackValue.TableValue
        }

        private fun attemptToIndexMessage(value: LuaStackValue): String {
            return "attempt to index a ${stackTypeName(value)} value"
        }

        override fun setTableValue(index: Int, key: Any?, value: Any?) {
            val table = valueAt(index) as? LuaStackValue.TableValue
                ?: throw IllegalArgumentException("argument $index is ${typeName(index)}")
            setStackTableValue(table, key, value)
        }

        override fun setTableField(table: Any?, key: Any?, value: Any?) {
            val tableValue = table as? LuaStackValue.TableValue
                ?: throw IllegalArgumentException("value is not a table")
            setStackTableValue(tableValue, key, value)
        }

        override fun setValueField(value: Any?, key: Any?, fieldValue: Any?) {
            stackSetValue(
                value.toStackValue(),
                key.toStackValue(),
                fieldValue.toStackValue(),
                java.util.Collections.newSetFromMap(IdentityHashMap()),
                depth = 0,
            )
        }

        private fun stackSetValue(
            receiver: LuaStackValue,
            key: LuaStackValue,
            value: LuaStackValue,
            visited: MutableSet<LuaStackValue.TableValue>,
            depth: Int,
        ) {
            if (depth >= MAX_CALL_METAMETHOD_DEPTH) {
                throw IllegalArgumentException("'__newindex' chain too long; possible loop")
            }
            when (receiver) {
                is LuaStackValue.TableValue -> stackTableSetValue(receiver, key, value, visited, depth)
                else -> {
                    val metatable = stackValueMetatable(receiver) ?: throw IllegalArgumentException(
                        attemptToIndexMessage(receiver),
                    )
                    val newIndex = metatable.fields[newIndexMetamethodKey] ?: throw IllegalArgumentException(
                        attemptToIndexMessage(receiver),
                    )
                    stackMetamethodSetValue(receiver, key, value, newIndex, visited, depth)
                }
            }
        }

        private fun stackTableSetValue(
            table: LuaStackValue.TableValue,
            key: LuaStackValue,
            value: LuaStackValue,
            visited: MutableSet<LuaStackValue.TableValue>,
            depth: Int,
        ) {
            if (table.fields.containsKey(key)) {
                setStackTableValue(table, key.toPublicCallReturnValue(), value.toPublicCallReturnValue())
                return
            }
            if (!visited.add(table)) {
                throw IllegalArgumentException("'__newindex' chain too long; possible loop")
            }
            val newIndex = table.metatable?.fields?.get(newIndexMetamethodKey)
            if (newIndex == null || newIndex == LuaStackValue.Nil) {
                setStackTableValue(table, key.toPublicCallReturnValue(), value.toPublicCallReturnValue())
                return
            }
            stackMetamethodSetValue(table, key, value, newIndex, visited, depth)
        }

        private fun stackMetamethodSetValue(
            receiver: LuaStackValue,
            key: LuaStackValue,
            value: LuaStackValue,
            newIndex: LuaStackValue,
            visited: MutableSet<LuaStackValue.TableValue>,
            depth: Int,
        ) {
            when (newIndex) {
                is LuaStackValue.NativeFunctionValue -> callFunction(
                    newIndex,
                    listOf(
                        receiver.toPublicCallReturnValue(),
                        key.toPublicCallReturnValue(),
                        value.toPublicCallReturnValue(),
                    ),
                )
                else -> stackSetValue(newIndex, key, value, visited, depth + 1)
            }
        }

        private fun setStackTableValue(table: LuaStackValue.TableValue, key: Any?, value: Any?) {
            val stackKey = key.toStackValue()
            val stackValue = value.toStackValue()
            if (stackValue == LuaStackValue.Nil) {
                table.fields.remove(stackKey)
            } else {
                table.fields[stackKey] = stackValue
            }
            val coreTable = table.coreValue ?: return
            val tableCache = IdentityHashMap<LuaStackValue.TableValue, KLuaCoreValue.TableValue>()
            tableCache[table] = coreTable
            coreGlobals.setTableField(
                coreTable,
                stackKey.toCoreTableFieldValue(tableCache),
                stackValue.toCoreTableFieldValue(tableCache),
            )
        }

        override fun getMetatable(index: Int): Any? {
            return when (val value = valueAt(index)) {
                null -> throw IllegalArgumentException("argument $index is none")
                is LuaStackValue.TableValue -> value.metatable
                is LuaStackValue.UserDataValue -> userMetatables[value.value]
                else -> {
                    val typeName = stackTypeName(value)
                    if (typeName in RAW_TYPE_METATABLE_TYPES) {
                        KLuaCoreRuntime.getRawTypeMetatable(coreGlobals, typeName)?.toStackValue()
                    } else {
                        null
                    }
                }
            }
        }

        override fun getRawMetatable(index: Int): Any? {
            return when (val value = valueAt(index)) {
                is LuaStackValue.TableValue -> getMetatable(index)
                is LuaStackValue.UserDataValue -> {
                    val typeName = stackTypeName(value)
                    if (typeName in RAW_TYPE_METATABLE_TYPES) {
                        KLuaCoreRuntime.getRawTypeMetatable(coreGlobals, typeName)?.toStackValue()
                    } else {
                        userMetatables[value.value]
                    }
                }
                else -> {
                    val typeName = typeName(index)
                    if (typeName in RAW_TYPE_METATABLE_TYPES) {
                        KLuaCoreRuntime.getRawTypeMetatable(coreGlobals, typeName)?.toStackValue()
                    } else {
                        null
                    }
                }
            }
        }

        override fun getTableMetatable(table: Any?): Any? {
            val tableValue = table as? LuaStackValue.TableValue ?: return null
            return tableValue.metatable
        }

        override fun setMetatable(index: Int, metatable: Any?) {
            setValueMetatable(valueAt(index), metatable.toStackValue().toMetatableValue(), index)
        }

        override fun setRawMetatable(index: Int, metatable: Any?) {
            when (val value = valueAt(index)) {
                is LuaStackValue.TableValue -> setMetatable(index, metatable)
                is LuaStackValue.UserDataValue -> {
                    val typeName = stackTypeName(value)
                    if (typeName in RAW_TYPE_METATABLE_TYPES) {
                        KLuaCoreRuntime.setRawTypeMetatable(coreGlobals, typeName, metatable.toCoreMetatable())
                    } else {
                        setUserDataMetatable(value, metatable)
                    }
                }
                else -> {
                    val typeName = typeName(index)
                    if (typeName !in RAW_TYPE_METATABLE_TYPES) {
                        throw IllegalArgumentException("argument $index is $typeName")
                    }
                    KLuaCoreRuntime.setRawTypeMetatable(coreGlobals, typeName, metatable.toCoreMetatable())
                }
            }
        }

        private fun setUserDataMetatable(userData: LuaStackValue.UserDataValue, metatable: Any?) {
            val coreMetatable = when (val stackMetatable = metatable.toStackValue()) {
                LuaStackValue.Nil -> {
                    userMetatables.remove(userData.value)
                    null
                }
                is LuaStackValue.TableValue -> {
                    userMetatables[userData.value] = stackMetatable
                    stackMetatable.toCoreTableValue(IdentityHashMap())
                }
                else -> throw IllegalArgumentException("metatable is ${stackTypeName(stackMetatable)}")
            }
            KLuaCoreRuntime.setUserDataMetatable(coreGlobals, userData.value, coreMetatable)
        }

        private fun Any?.toCoreMetatable(): KLuaCoreValue.TableValue? {
            return when (val stackMetatable = toStackValue()) {
                LuaStackValue.Nil -> null
                is LuaStackValue.TableValue -> stackMetatable.toCoreTableValue(IdentityHashMap())
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
                is LuaStackValue.StringValue -> integerFromString(value.value)
                else -> null
            }
        }

        override fun toNumber(index: Int): Double? {
            return when (val value = valueAt(index)) {
                is LuaStackValue.IntegerValue -> value.value.toDouble()
                is LuaStackValue.NumberValue -> value.value
                is LuaStackValue.StringValue -> numberFromString(value.value)
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

        override fun getUserValue(index: Int, userValueIndex: Int): LuaReturn? {
            if (userValueIndex <= 0) {
                return null
            }
            val userData = toUserData(index) ?: return null
            val value = userValues[userData]?.get(userValueIndex) ?: LuaStackValue.Nil
            return LuaReturn.of(value.toPublicCallReturnValue(), true)
        }

        override fun setUserValue(index: Int, userValueIndex: Int, value: Any?): Boolean {
            if (userValueIndex <= 0) {
                return false
            }
            val userData = toUserData(index) ?: return false
            val values = userValues.getOrPut(userData) { linkedMapOf() }
            values[userValueIndex] = value.toStackValue()
            return true
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

    private inner class CoreBackedLuaCallContext(
        arguments: List<LuaStackValue>,
        private val coreContext: KLuaCoreCallContext,
    ) : DefaultLuaCallContext(arguments, isYieldable = coreContext.isYieldable) {
        private var cachedCoreFrames: List<LuaStackFrame>? = null

        override val luaFrames: List<LuaStackFrame>
            get() = cachedCoreFrames
                ?: toApiStackFrames(coreContext.luaFrames).also { frames -> cachedCoreFrames = frames }

        override fun setLocal(level: Int, index: Int, value: Any?): String? {
            return coreContext.setLocal(level, index, value.toCoreReturnValue())
        }

        override fun setDebugHook(index: Int, mask: String, count: Int): Boolean {
            return coreContext.setDebugHook(index, mask, count)
        }

        override fun getDebugHook(): LuaReturn {
            return coreContext.getDebugHook()?.toLuaReturn() ?: LuaReturn.of(null)
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

private val LUA_INTEGER_EXCLUSIVE_UPPER_BOUND = -Long.MIN_VALUE.toDouble()
private val UINT64_MODULUS: BigInteger = BigInteger.ONE.shiftLeft(Long.SIZE_BITS)
private val RAW_TYPE_METATABLE_TYPES = setOf("nil", "boolean", "number", "string", "function", "thread")

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
