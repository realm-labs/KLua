package io.github.realmlabs.klua.core

import io.github.realmlabs.klua.core.compiler.Compiler
import io.github.realmlabs.klua.core.compiler.CompilerException
import io.github.realmlabs.klua.core.bytecode.Prototype
import io.github.realmlabs.klua.core.lexer.LexerException
import io.github.realmlabs.klua.core.parser.ParserException
import io.github.realmlabs.klua.core.value.LuaBoolean
import io.github.realmlabs.klua.core.value.LuaClosure
import io.github.realmlabs.klua.core.value.LuaFloat
import io.github.realmlabs.klua.core.value.LuaInteger
import io.github.realmlabs.klua.core.value.LuaNil
import io.github.realmlabs.klua.core.value.LuaNativeCallContext
import io.github.realmlabs.klua.core.value.LuaNativeFunction
import io.github.realmlabs.klua.core.value.LuaNativeStackFrame
import io.github.realmlabs.klua.core.value.LuaString
import io.github.realmlabs.klua.core.value.LuaTable
import io.github.realmlabs.klua.core.value.LuaUserData
import io.github.realmlabs.klua.core.value.LuaUserDataProperty
import io.github.realmlabs.klua.core.value.LuaUserDataType
import io.github.realmlabs.klua.core.value.LuaValue
import io.github.realmlabs.klua.core.vm.LuaExecutionResult
import io.github.realmlabs.klua.core.vm.LuaVm
import io.github.realmlabs.klua.core.vm.LuaVmException
import io.github.realmlabs.klua.core.vm.LuaVmStackFrame
import io.github.realmlabs.klua.core.vm.LuaYieldContinuation
import io.github.realmlabs.klua.core.vm.LuaYieldSignal
import io.github.realmlabs.klua.core.vm.LuaYieldSignalContinuation
import java.util.IdentityHashMap

public object KLuaCoreRuntime {
    public fun compile(source: String, chunkName: String): KLuaCoreLoad {
        return try {
            KLuaCoreLoad.Success(KLuaCoreChunk(Compiler.compile(source, chunkName, isVarargChunk = true)))
        } catch (error: LexerException) {
            KLuaCoreLoad.SyntaxError(error.message ?: "lexer error")
        } catch (error: ParserException) {
            KLuaCoreLoad.SyntaxError(error.message ?: "parser error")
        } catch (error: CompilerException) {
            KLuaCoreLoad.SyntaxError(error.message ?: "compiler error")
        }
    }

    public fun execute(source: String, chunkName: String): KLuaCoreExecution {
        return when (val load = compile(source, chunkName)) {
            is KLuaCoreLoad.Success -> execute(load.chunk)
            is KLuaCoreLoad.SyntaxError -> KLuaCoreExecution.SyntaxError(load.message)
        }
    }

    public fun execute(chunk: KLuaCoreChunk): KLuaCoreExecution {
        return execute(chunk, emptyList())
    }

    public fun execute(chunk: KLuaCoreChunk, arguments: List<KLuaCoreValue>): KLuaCoreExecution {
        return execute(chunk, arguments, KLuaCoreGlobals())
    }

    public fun execute(
        chunk: KLuaCoreChunk,
        arguments: List<KLuaCoreValue>,
        globals: KLuaCoreGlobals,
    ): KLuaCoreExecution {
        val vmArguments = arguments.map { value ->
            value.toLuaValueOrNull(globals)
                ?: run {
                    return KLuaCoreExecution.RuntimeError("cannot pass ${value.publicTypeName()} as Lua argument")
                }
        }
        return try {
            KLuaCoreExecution.Success(LuaVm(globals.table).execute(chunk.prototype, vmArguments).map { value ->
                toPublicValue(value, globals)
            })
        } catch (error: LuaVmException) {
            KLuaCoreExecution.RuntimeError(
                error.message ?: "runtime error",
                error.sourceName,
                error.line,
                error.rootCause(),
                error.luaFrames.toCoreStackFrames(),
            )
        }
    }

    public fun canCreateCoroutine(function: KLuaCoreValue.FunctionValue): Boolean {
        return function.sourceFunction != null
    }

    public fun createCoroutine(
        function: KLuaCoreValue.FunctionValue,
        globals: KLuaCoreGlobals,
    ): KLuaCoreCoroutine? {
        val sourceFunction = function.sourceFunction ?: return null
        return KLuaCoreCoroutine(sourceFunction, globals)
    }

    public fun createFunctionValue(
        function: KLuaCoreFunction,
        yieldable: Boolean = false,
    ): KLuaCoreValue.FunctionValue {
        return KLuaCoreValue.FunctionValue(function).also { functionValue ->
            functionValue.yieldable = yieldable
        }
    }

    public fun createContextFunctionValue(
        function: KLuaCoreContextFunction,
        yieldable: Boolean = false,
    ): KLuaCoreValue.FunctionValue {
        return KLuaCoreValue.FunctionValue { arguments ->
            function.call(KLuaCoreCallContext(arguments, emptyList()))
        }.also { functionValue ->
            functionValue.contextFunction = function
            functionValue.yieldable = yieldable
        }
    }

    public fun getUpvalue(
        function: KLuaCoreValue.FunctionValue,
        index: Int,
        globals: KLuaCoreGlobals,
    ): KLuaCoreUpvalue? {
        if (index <= 0) {
            return null
        }
        val closure = function.sourceFunction as? LuaClosure ?: return null
        val zeroIndex = index - 1
        val name = closure.prototype.upvalueNames.getOrNull(zeroIndex) ?: return null
        val upvalue = closure.upvalues.getOrNull(zeroIndex) ?: return null
        return KLuaCoreUpvalue(name, toPublicValue(upvalue.value, globals))
    }
}

public class KLuaCoreChunk internal constructor(
    internal val prototype: Prototype,
)

public class KLuaCoreGlobals internal constructor(
    internal val table: LuaTable = LuaTable(),
) {
    private val userDataTypes = linkedMapOf<Class<*>, MutableMap<String, LuaNativeFunction>>()
    private val userDataProperties = linkedMapOf<Class<*>, MutableMap<String, LuaUserDataProperty>>()

    public companion object {
        @JvmStatic
        public fun create(): KLuaCoreGlobals = KLuaCoreGlobals()
    }

    public fun get(name: String): KLuaCoreValue = toPublicValue(table.rawGet(LuaString(name)), this)

    public fun set(name: String, value: KLuaCoreValue): Boolean {
        val luaValue = value.toLuaValueOrNull(this) ?: return false
        table.rawSet(LuaString(name), luaValue)
        return true
    }

    public fun setGlobalTable(name: String) {
        table.rawSet(LuaString(name), table)
    }

    public fun setFunction(name: String, function: KLuaCoreFunction) {
        table.rawSet(
            LuaString(name),
            LuaNativeFunction { arguments -> callCoreFunction(function, arguments, this) },
        )
    }

    public fun <T : Any> setUserDataMethod(
        type: Class<T>,
        name: String,
        method: KLuaCoreUserDataMethod<T>,
    ) {
        val methods = userDataTypes.getOrPut(type) { linkedMapOf() }
        methods[name] = LuaNativeFunction { arguments ->
            val receiver = arguments.firstOrNull() as? LuaUserData
                ?: throw LuaVmException("userdata method '$name' missing receiver")
            if (!type.isInstance(receiver.value)) {
                throw LuaVmException("userdata method '$name' expected ${type.name}")
            }
            callCoreUserDataMethod(type.cast(receiver.value), method, arguments.drop(1), this)
        }
    }

    public fun <T : Any> setUserDataProperty(
        type: Class<T>,
        name: String,
        getter: KLuaCoreUserDataGetter<T>?,
        setter: KLuaCoreUserDataSetter<T>?,
    ) {
        val properties = userDataProperties.getOrPut(type) { linkedMapOf() }
        properties[name] = LuaUserDataProperty(
            getter = getter?.let { propertyGetter ->
                LuaNativeFunction { arguments ->
                    val receiver = arguments.firstOrNull() as? LuaUserData
                        ?: throw LuaVmException("userdata property '$name' missing receiver")
                    if (!type.isInstance(receiver.value)) {
                        throw LuaVmException("userdata property '$name' expected ${type.name}")
                    }
                    callCoreUserDataGetter(type.cast(receiver.value), propertyGetter, this)
                }
            },
            setter = setter?.let { propertySetter ->
                LuaNativeFunction { arguments ->
                    val receiver = arguments.firstOrNull() as? LuaUserData
                        ?: throw LuaVmException("userdata property '$name' missing receiver")
                    if (!type.isInstance(receiver.value)) {
                        throw LuaVmException("userdata property '$name' expected ${type.name}")
                    }
                    val value = arguments.getOrNull(1)
                        ?: throw LuaVmException("userdata property '$name' missing value")
                    callCoreUserDataSetter(type.cast(receiver.value), propertySetter, value, this)
                }
            },
        )
    }

    internal fun userDataType(value: Any): LuaUserDataType? {
        val methods = linkedMapOf<String, LuaNativeFunction>()
        for ((_, registeredMethods) in applicableUserDataEntries(userDataTypes, value)) {
            methods.putAll(registeredMethods)
        }
        val properties = linkedMapOf<String, LuaUserDataProperty>()
        for ((_, registeredProperties) in applicableUserDataEntries(userDataProperties, value)) {
            for ((name, property) in registeredProperties) {
                properties[name] = properties[name]?.mergeWith(property) ?: property
            }
        }
        if (methods.isEmpty() && properties.isEmpty()) {
            return null
        }
        return LuaUserDataType(methods.toMap(), properties.toMap())
    }

    private fun <T> applicableUserDataEntries(
        registrations: Map<Class<*>, T>,
        value: Any,
    ): List<Map.Entry<Class<*>, T>> {
        return registrations.entries
            .filter { (type, _) -> type.isInstance(value) }
            .sortedWith { left, right -> compareUserDataSpecificity(left.key, right.key) }
    }

    private fun compareUserDataSpecificity(left: Class<*>, right: Class<*>): Int {
        return when {
            left == right -> 0
            left.isAssignableFrom(right) -> -1
            right.isAssignableFrom(left) -> 1
            else -> 0
        }
    }
}

private fun LuaUserDataProperty.mergeWith(moreSpecific: LuaUserDataProperty): LuaUserDataProperty {
    return LuaUserDataProperty(
        getter = moreSpecific.getter ?: getter,
        setter = moreSpecific.setter ?: setter,
    )
}

public fun interface KLuaCoreFunction {
    public fun call(arguments: List<KLuaCoreValue>): KLuaCoreCallResult
}

public fun interface KLuaCoreContextFunction {
    public fun call(context: KLuaCoreCallContext): KLuaCoreCallResult
}

public data class KLuaCoreCallContext(
    public val arguments: List<KLuaCoreValue>,
    public val luaFrames: List<KLuaCoreStackFrame>,
)

public fun interface KLuaCoreUserDataMethod<T : Any> {
    public fun call(receiver: T, arguments: List<KLuaCoreValue>): KLuaCoreCallResult
}

public fun interface KLuaCoreUserDataGetter<T : Any> {
    public fun get(receiver: T): KLuaCoreCallResult
}

public fun interface KLuaCoreUserDataSetter<T : Any> {
    public fun set(receiver: T, value: KLuaCoreValue): KLuaCoreCallResult
}

public sealed interface KLuaCoreCallResult {
    public data class Success(
        public val values: List<KLuaCoreValue>,
    ) : KLuaCoreCallResult

    public data class Yielded(
        public val values: List<KLuaCoreValue>,
        public val continuation: KLuaCoreContinuation? = null,
    ) : KLuaCoreCallResult

    public data class RuntimeError(
        public val message: String,
        public val cause: Throwable? = null,
        public val nativeFrames: List<String> = emptyList(),
    ) : KLuaCoreCallResult
}

public sealed interface KLuaCoreLoad {
    public data class Success(
        public val chunk: KLuaCoreChunk,
    ) : KLuaCoreLoad

    public data class SyntaxError(
        public val message: String,
    ) : KLuaCoreLoad
}

public sealed interface KLuaCoreExecution {
    public data class Success(
        public val values: List<KLuaCoreValue>,
    ) : KLuaCoreExecution

    public data class SyntaxError(
        public val message: String,
    ) : KLuaCoreExecution

    public data class RuntimeError(
        public val message: String,
        public val sourceName: String? = null,
        public val line: Int? = null,
        public val cause: Throwable? = null,
        public val luaFrames: List<KLuaCoreStackFrame> = emptyList(),
    ) : KLuaCoreExecution {
        public val traceback: String = formatCoreTraceback(message, luaFrames)
    }
}

public data class KLuaCoreStackFrame(
    public val sourceName: String,
    public val line: Int,
    public val lineDefined: Int = 0,
    public val lastLineDefined: Int = 0,
    public val locals: List<KLuaCoreLocalVariable> = emptyList(),
)

public data class KLuaCoreLocalVariable(
    public val name: String,
    public val value: KLuaCoreValue,
)

public data class KLuaCoreUpvalue(
    public val name: String,
    public val value: KLuaCoreValue,
)

public fun interface KLuaCoreContinuation {
    public fun resume(arguments: List<KLuaCoreValue>): KLuaCoreCallResult
}

public class KLuaCoreCoroutine internal constructor(
    private val function: LuaValue,
    private val globals: KLuaCoreGlobals,
) {
    private val vm = LuaVm(globals.table)
    private var started = false
    private var dead = false
    private var pendingContinuation: LuaYieldContinuation? = null

    public fun resume(arguments: List<KLuaCoreValue>): KLuaCoreCoroutineExecution {
        if (dead) {
            return KLuaCoreCoroutineExecution.RuntimeError("cannot resume dead coroutine")
        }
        val luaArguments = arguments.map { value ->
            value.toLuaValueOrNull(globals)
                ?: return KLuaCoreCoroutineExecution.RuntimeError("cannot pass ${value.publicTypeName()} as Lua argument")
        }
        return try {
            val continuation = pendingContinuation
            pendingContinuation = null
            val result = when {
                continuation != null -> continuation.resume(luaArguments)
                started -> vm.resumeYieldable(luaArguments)
                else -> vm.callYieldable(function, luaArguments)
            }
            result.toCoroutineExecution(globals).also {
                when (result) {
                    is LuaExecutionResult.Returned -> dead = true
                    is LuaExecutionResult.Yielded -> {
                        pendingContinuation = result.continuation ?: if (vm.currentFrame == null) {
                            LuaYieldContinuation { resumedArguments -> LuaExecutionResult.Returned(resumedArguments) }
                        } else {
                            null
                        }
                    }
                }
            }
        } catch (error: LuaVmException) {
            dead = true
            KLuaCoreCoroutineExecution.RuntimeError(
                error.message ?: "runtime error",
                error.sourceName,
                error.line,
                error.rootCause(),
                error.luaFrames.toCoreStackFrames(),
            )
        } finally {
            started = true
        }
    }
}

private fun LuaExecutionResult.toCoroutineExecution(globals: KLuaCoreGlobals): KLuaCoreCoroutineExecution {
    return when (this) {
        is LuaExecutionResult.Returned -> KLuaCoreCoroutineExecution.Returned(
            values.map { value -> toPublicValue(value, globals) },
        )
        is LuaExecutionResult.Yielded -> KLuaCoreCoroutineExecution.Yielded(
            values.map { value -> toPublicValue(value, globals) },
        )
    }
}

public sealed interface KLuaCoreCoroutineExecution {
    public data class Returned(
        public val values: List<KLuaCoreValue>,
    ) : KLuaCoreCoroutineExecution

    public data class Yielded(
        public val values: List<KLuaCoreValue>,
    ) : KLuaCoreCoroutineExecution

    public data class RuntimeError(
        public val message: String,
        public val sourceName: String? = null,
        public val line: Int? = null,
        public val cause: Throwable? = null,
        public val luaFrames: List<KLuaCoreStackFrame> = emptyList(),
    ) : KLuaCoreCoroutineExecution {
        public val traceback: String = formatCoreTraceback(message, luaFrames)
    }
}

public sealed interface KLuaCoreValue {
    public data object Nil : KLuaCoreValue

    public data class BooleanValue(
        public val value: Boolean,
    ) : KLuaCoreValue

    public data class IntegerValue(
        public val value: Long,
    ) : KLuaCoreValue

    public data class NumberValue(
        public val value: Double,
    ) : KLuaCoreValue

    public data class StringValue(
        public val value: String,
    ) : KLuaCoreValue

    public data class FunctionValue(
        public val function: KLuaCoreFunction,
    ) : KLuaCoreValue {
        internal var sourceFunction: LuaValue? = null
        internal var contextFunction: KLuaCoreContextFunction? = null
        internal var yieldable: Boolean = false
    }

    public data class TableValue(
        public val fields: MutableMap<KLuaCoreValue, KLuaCoreValue>,
    ) : KLuaCoreValue {
        public var metatable: TableValue? = null
        internal var sourceTable: LuaTable? = null
    }

    public data class UserDataValue(
        public val value: Any,
    ) : KLuaCoreValue

    public data class UnsupportedValue(
        public val typeName: String,
    ) : KLuaCoreValue
}

private fun KLuaCoreValue.publicTypeName(): String {
    return when (this) {
        KLuaCoreValue.Nil -> "nil"
        is KLuaCoreValue.BooleanValue -> "boolean"
        is KLuaCoreValue.IntegerValue,
        is KLuaCoreValue.NumberValue,
        -> "number"
        is KLuaCoreValue.StringValue -> "string"
        is KLuaCoreValue.FunctionValue -> "function"
        is KLuaCoreValue.TableValue -> "table"
        is KLuaCoreValue.UserDataValue -> "userdata"
        is KLuaCoreValue.UnsupportedValue -> typeName
    }
}

private fun KLuaCoreValue.toLuaValueOrNull(globals: KLuaCoreGlobals): LuaValue? {
    return toLuaValueOrNull(globals, IdentityHashMap())
}

private fun KLuaCoreValue.toLuaValueOrNull(
    globals: KLuaCoreGlobals,
    tableCache: MutableMap<KLuaCoreValue.TableValue, LuaTable>,
): LuaValue? {
    return when (this) {
        KLuaCoreValue.Nil -> LuaNil
        is KLuaCoreValue.BooleanValue -> LuaBoolean(value)
        is KLuaCoreValue.IntegerValue -> LuaInteger(value)
        is KLuaCoreValue.NumberValue -> LuaFloat(value)
        is KLuaCoreValue.StringValue -> LuaString(value)
        is KLuaCoreValue.FunctionValue -> LuaNativeFunction(
            yieldable = yieldable,
            function = { arguments -> callCoreFunction(function, arguments, globals) },
            contextualFunction = contextFunction?.let { function ->
                { context -> callCoreContextFunction(function, context, globals) }
            },
        )
        is KLuaCoreValue.TableValue -> {
            val cached = tableCache[this]
            if (cached != null) {
                return cached
            }
            val originalTable = this.sourceTable
            if (originalTable != null) {
                syncPublicTableToLua(originalTable, this, globals, tableCache)
                return originalTable
            }
            val table = LuaTable()
            tableCache[this] = table
            for ((fieldKey, fieldValue) in fields) {
                val luaKey = fieldKey.toLuaValueOrNull(globals, tableCache) ?: return null
                val luaValue = fieldValue.toLuaValueOrNull(globals, tableCache) ?: return null
                table.rawSet(luaKey, luaValue)
            }
            table.metatable = metatable?.toLuaValueOrNull(globals, tableCache) as? LuaTable
            table
        }
        is KLuaCoreValue.UserDataValue -> LuaUserData(value) { hostValue -> globals.userDataType(hostValue) }
        is KLuaCoreValue.UnsupportedValue -> null
    }
}

private fun toPublicValue(value: LuaValue, globals: KLuaCoreGlobals): KLuaCoreValue = toPublicValue(value, globals, IdentityHashMap())

private fun toPublicValue(
    value: LuaValue,
    globals: KLuaCoreGlobals,
    tableCache: MutableMap<LuaTable, KLuaCoreValue.TableValue>,
): KLuaCoreValue {
    return when (value) {
        LuaNil -> KLuaCoreValue.Nil
        is LuaBoolean -> KLuaCoreValue.BooleanValue(value.value)
        is LuaInteger -> KLuaCoreValue.IntegerValue(value.value)
        is LuaFloat -> KLuaCoreValue.NumberValue(value.value)
        is LuaString -> KLuaCoreValue.StringValue(value.value)
        is LuaTable -> {
            val cached = tableCache[value]
            if (cached != null) {
                return cached
            }
            val tableValue = KLuaCoreValue.TableValue(mutableMapOf())
            tableCache[value] = tableValue
            tableValue.sourceTable = value
            tableValue.fields.putAll(
                value.rawEntries()
                    .map { (key, fieldValue) -> toPublicValue(key, globals, tableCache) to toPublicValue(fieldValue, globals, tableCache) },
            )
            tableValue.metatable = value.metatable?.let { metatable -> toPublicValue(metatable, globals, tableCache) as KLuaCoreValue.TableValue }
            tableValue
        }
        is LuaUserData -> KLuaCoreValue.UserDataValue(value.value)
        is LuaClosure,
        is LuaNativeFunction,
        -> KLuaCoreValue.FunctionValue { arguments ->
            callPublicLuaFunction(value, arguments, globals)
        }.also { functionValue ->
            functionValue.sourceFunction = value
            functionValue.yieldable = when (value) {
                is LuaClosure -> true
                is LuaNativeFunction -> value.yieldable
            }
        }
    }
}

private fun LuaValue.publicTypeName(): String {
    return when (this) {
        is LuaBoolean -> "boolean"
        is LuaFloat,
        is LuaInteger,
        -> "number"
        LuaNil -> "nil"
        is LuaString -> "string"
        is LuaClosure,
        is LuaNativeFunction -> "function"
        is LuaTable -> "table"
        is LuaUserData -> "userdata"
    }
}

private fun LuaVmException.rootCause(): Throwable? {
    var cause = cause ?: return null
    while (cause is LuaVmException && cause.cause != null) {
        cause = cause.cause!!
    }
    return cause
}

private fun List<LuaVmStackFrame>.toCoreStackFrames(): List<KLuaCoreStackFrame> {
    return map { frame -> KLuaCoreStackFrame(frame.sourceName, frame.line) }
}

private fun List<LuaNativeStackFrame>.toCoreStackFramesFromNative(globals: KLuaCoreGlobals): List<KLuaCoreStackFrame> {
    return map { frame ->
        KLuaCoreStackFrame(
            frame.sourceName,
            frame.line,
            frame.lineDefined,
            frame.lastLineDefined,
            frame.locals.map { local ->
                KLuaCoreLocalVariable(
                    local.name,
                    toPublicValue(local.value, globals),
                )
            },
        )
    }
}

private fun formatCoreTraceback(message: String, frames: List<KLuaCoreStackFrame>): String {
    return buildString {
        append(message)
        append("\nstack traceback:")
        for (frame in frames) {
            append("\n\t")
            append(frame.sourceName)
            if (frame.line > 0) {
                append(':')
                append(frame.line)
            }
        }
    }
}

private fun callCoreFunction(
    function: KLuaCoreFunction,
    arguments: List<LuaValue>,
    globals: KLuaCoreGlobals,
): List<LuaValue> {
    return callCoreFunction(arguments, globals) { publicArguments ->
        function.call(publicArguments)
    }
}

private fun callCoreContextFunction(
    function: KLuaCoreContextFunction,
    context: LuaNativeCallContext,
    globals: KLuaCoreGlobals,
): List<LuaValue> {
    return callCoreFunction(context.arguments, globals) { publicArguments ->
        function.call(KLuaCoreCallContext(publicArguments, context.luaFrames.toCoreStackFramesFromNative(globals)))
    }
}

private fun callCoreFunction(
    arguments: List<LuaValue>,
    globals: KLuaCoreGlobals,
    call: (List<KLuaCoreValue>) -> KLuaCoreCallResult,
): List<LuaValue> {
    val tableCache = IdentityHashMap<LuaTable, KLuaCoreValue.TableValue>()
    val publicArguments = arguments.map { value -> toPublicValue(value, globals, tableCache) }
    return try {
        when (val result = call(publicArguments)) {
            is KLuaCoreCallResult.Success -> {
                syncPublicTablesToLua(arguments, publicArguments, globals)
                result.values.map { value ->
                    value.toLuaReturnValue(arguments, publicArguments, globals)
                }
            }
            is KLuaCoreCallResult.Yielded -> {
                syncPublicTablesToLua(arguments, publicArguments, globals)
                throw result.toLuaYieldSignal(arguments, publicArguments, globals)
            }
            is KLuaCoreCallResult.RuntimeError -> throw LuaVmException(
                result.message,
                luaFrames = result.nativeFrames.toNativeStackFrames(),
                cause = result.cause,
            )
        }
    } catch (error: LuaVmException) {
        throw error
    } catch (yield: LuaYieldSignal) {
        throw yield
    } catch (error: RuntimeException) {
        throw LuaVmException(error.message ?: error::class.java.simpleName, cause = error)
    }
}

private fun KLuaCoreCallResult.Yielded.toLuaYieldSignal(
    luaArguments: List<LuaValue>,
    publicArguments: List<KLuaCoreValue>,
    globals: KLuaCoreGlobals,
): LuaYieldSignal {
    return LuaYieldSignal(
        values.map { value ->
            value.toLuaReturnValue(luaArguments, publicArguments, globals)
        },
        continuation?.let { continuation ->
            LuaYieldSignalContinuation { arguments ->
                continuePublicYield(continuation, arguments, globals)
            }
        },
    )
}

private fun continuePublicYield(
    continuation: KLuaCoreContinuation,
    arguments: List<LuaValue>,
    globals: KLuaCoreGlobals,
): List<LuaValue> {
    val publicArguments = arguments.map { value -> toPublicValue(value, globals) }
    return when (val result = continuation.resume(publicArguments)) {
        is KLuaCoreCallResult.Success -> result.values.map { value ->
            value.toLuaValueOrNull(globals)
                ?: throw LuaVmException("cannot return ${value.publicTypeName()} as Lua value")
        }
        is KLuaCoreCallResult.Yielded -> throw result.toLuaYieldSignal(arguments, publicArguments, globals)
        is KLuaCoreCallResult.RuntimeError -> throw LuaVmException(
            result.message,
            luaFrames = result.nativeFrames.toNativeStackFrames(),
            cause = result.cause,
        )
    }
}

private fun List<String>.toNativeStackFrames(): List<LuaVmStackFrame> {
    return map { name -> LuaVmStackFrame("[Kotlin]: $name") }
}

private fun KLuaCoreValue.toLuaReturnValue(
    luaArguments: List<LuaValue>,
    publicArguments: List<KLuaCoreValue>,
    globals: KLuaCoreGlobals,
): LuaValue {
    val tableCache = seedLuaTableCache(luaArguments, publicArguments)
    return toLuaValueOrNull(globals, tableCache)
        ?: throw LuaVmException("cannot return ${publicTypeName()} as Lua value")
}

private fun syncPublicTablesToLua(
    luaArguments: List<LuaValue>,
    publicArguments: List<KLuaCoreValue>,
    globals: KLuaCoreGlobals,
) {
    val tableCache = seedLuaTableCache(luaArguments, publicArguments)
    for (index in luaArguments.indices) {
        val luaTable = luaArguments[index] as? LuaTable ?: continue
        val publicTable = publicArguments.getOrNull(index) as? KLuaCoreValue.TableValue ?: continue
        syncPublicTableToLua(luaTable, publicTable, globals, tableCache)
    }
}

private fun syncPublicTableToLua(
    luaTable: LuaTable,
    publicTable: KLuaCoreValue.TableValue,
    globals: KLuaCoreGlobals,
    tableCache: MutableMap<KLuaCoreValue.TableValue, LuaTable>,
) {
    tableCache[publicTable] = luaTable
    val entries = publicTable.fields.map { (key, value) ->
        val luaKey = key.toLuaValueOrNull(globals, tableCache)
            ?: throw LuaVmException("cannot use ${key.publicTypeName()} as Lua table key")
        val luaValue = value.toLuaValueOrNull(globals, tableCache)
            ?: throw LuaVmException("cannot use ${value.publicTypeName()} as Lua table value")
        luaKey to luaValue
    }
    luaTable.rawReplace(entries)
    luaTable.metatable = publicTable.metatable?.toLuaValueOrNull(globals, tableCache) as? LuaTable
}

private fun seedLuaTableCache(
    luaValues: List<LuaValue>,
    publicValues: List<KLuaCoreValue>,
): MutableMap<KLuaCoreValue.TableValue, LuaTable> {
    val tableCache = IdentityHashMap<KLuaCoreValue.TableValue, LuaTable>()
    for (index in luaValues.indices) {
        seedLuaTableCache(luaValues[index], publicValues.getOrNull(index), tableCache)
    }
    return tableCache
}

private fun seedLuaTableCache(
    luaValue: LuaValue,
    publicValue: KLuaCoreValue?,
    tableCache: MutableMap<KLuaCoreValue.TableValue, LuaTable>,
) {
    val luaTable = luaValue as? LuaTable ?: return
    val publicTable = publicValue as? KLuaCoreValue.TableValue ?: return
    if (tableCache.put(publicTable, luaTable) != null) {
        return
    }
    val publicMetatable = publicTable.metatable
    val luaMetatable = luaTable.metatable
    if (publicMetatable != null && luaMetatable != null) {
        seedLuaTableCache(luaMetatable, publicMetatable, tableCache)
    }
}

private fun <T : Any> callCoreUserDataMethod(
    receiver: T,
    method: KLuaCoreUserDataMethod<T>,
    arguments: List<LuaValue>,
    globals: KLuaCoreGlobals,
): List<LuaValue> {
    return try {
        when (val result = method.call(receiver, arguments.map { value -> toPublicValue(value, globals) })) {
            is KLuaCoreCallResult.Success -> result.values.map { value ->
                value.toLuaValueOrNull(globals)
                    ?: throw LuaVmException("cannot return ${value.publicTypeName()} as Lua value")
            }
            is KLuaCoreCallResult.Yielded -> throw LuaYieldSignal(
                result.values.map { value ->
                    value.toLuaValueOrNull(globals)
                        ?: throw LuaVmException("cannot yield ${value.publicTypeName()} as Lua value")
                },
                result.continuation?.let { continuation ->
                    LuaYieldSignalContinuation { arguments -> continuePublicYield(continuation, arguments, globals) }
                },
            )
            is KLuaCoreCallResult.RuntimeError -> throw LuaVmException(
                result.message,
                luaFrames = result.nativeFrames.toNativeStackFrames(),
                cause = result.cause,
            )
        }
    } catch (error: LuaVmException) {
        throw error
    } catch (yield: LuaYieldSignal) {
        throw yield
    } catch (error: RuntimeException) {
        throw LuaVmException(error.message ?: error::class.java.simpleName, cause = error)
    }
}

private fun <T : Any> callCoreUserDataGetter(
    receiver: T,
    getter: KLuaCoreUserDataGetter<T>,
    globals: KLuaCoreGlobals,
): List<LuaValue> {
    return try {
        when (val result = getter.get(receiver)) {
            is KLuaCoreCallResult.Success -> result.values.map { value ->
                value.toLuaValueOrNull(globals)
                    ?: throw LuaVmException("cannot return ${value.publicTypeName()} as Lua value")
            }
            is KLuaCoreCallResult.Yielded -> throw LuaYieldSignal(
                result.values.map { value ->
                    value.toLuaValueOrNull(globals)
                        ?: throw LuaVmException("cannot yield ${value.publicTypeName()} as Lua value")
                },
                result.continuation?.let { continuation ->
                    LuaYieldSignalContinuation { arguments -> continuePublicYield(continuation, arguments, globals) }
                },
            )
            is KLuaCoreCallResult.RuntimeError -> throw LuaVmException(
                result.message,
                luaFrames = result.nativeFrames.toNativeStackFrames(),
                cause = result.cause,
            )
        }
    } catch (error: LuaVmException) {
        throw error
    } catch (yield: LuaYieldSignal) {
        throw yield
    } catch (error: RuntimeException) {
        throw LuaVmException(error.message ?: error::class.java.simpleName, cause = error)
    }
}

private fun <T : Any> callCoreUserDataSetter(
    receiver: T,
    setter: KLuaCoreUserDataSetter<T>,
    value: LuaValue,
    globals: KLuaCoreGlobals,
): List<LuaValue> {
    return try {
        when (val result = setter.set(receiver, toPublicValue(value, globals))) {
            is KLuaCoreCallResult.Success -> emptyList()
            is KLuaCoreCallResult.Yielded -> throw LuaYieldSignal(
                result.values.map { yieldedValue ->
                    yieldedValue.toLuaValueOrNull(globals)
                        ?: throw LuaVmException("cannot yield ${yieldedValue.publicTypeName()} as Lua value")
                },
                result.continuation?.let { continuation ->
                    LuaYieldSignalContinuation { arguments -> continuePublicYield(continuation, arguments, globals) }
                },
            )
            is KLuaCoreCallResult.RuntimeError -> throw LuaVmException(
                result.message,
                luaFrames = result.nativeFrames.toNativeStackFrames(),
                cause = result.cause,
            )
        }
    } catch (error: LuaVmException) {
        throw error
    } catch (yield: LuaYieldSignal) {
        throw yield
    } catch (error: RuntimeException) {
        throw LuaVmException(error.message ?: error::class.java.simpleName, cause = error)
    }
}

private fun callPublicLuaFunction(
    function: LuaValue,
    arguments: List<KLuaCoreValue>,
    globals: KLuaCoreGlobals,
): KLuaCoreCallResult {
    val luaArguments = arguments.map { value ->
        value.toLuaValueOrNull(globals)
            ?: return KLuaCoreCallResult.RuntimeError("cannot pass ${value.publicTypeName()} as Lua argument")
    }
    return try {
        val vm = LuaVm(globals.table)
        vm.callYieldable(function, luaArguments).toCoreCallResult(vm, globals)
    } catch (error: LuaVmException) {
        KLuaCoreCallResult.RuntimeError(error.message ?: "runtime error", error.rootCause())
    }
}

private fun LuaExecutionResult.toCoreCallResult(
    vm: LuaVm,
    globals: KLuaCoreGlobals,
): KLuaCoreCallResult {
    return when (this) {
        is LuaExecutionResult.Returned -> KLuaCoreCallResult.Success(values.map { value -> toPublicValue(value, globals) })
        is LuaExecutionResult.Yielded -> KLuaCoreCallResult.Yielded(
            values.map { value -> toPublicValue(value, globals) },
            KLuaCoreContinuation { arguments ->
                val luaArguments = arguments.map { value ->
                    value.toLuaValueOrNull(globals)
                        ?: return@KLuaCoreContinuation KLuaCoreCallResult.RuntimeError("cannot pass ${value.publicTypeName()} as Lua argument")
                }
                try {
                    vm.resumeYieldable(luaArguments).toCoreCallResult(vm, globals)
                } catch (error: LuaVmException) {
                    KLuaCoreCallResult.RuntimeError(error.message ?: "runtime error", error.rootCause())
                }
            },
        )
    }
}
