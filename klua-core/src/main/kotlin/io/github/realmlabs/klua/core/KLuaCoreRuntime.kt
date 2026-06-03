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
import io.github.realmlabs.klua.core.value.LuaNativeFunction
import io.github.realmlabs.klua.core.value.LuaString
import io.github.realmlabs.klua.core.value.LuaTable
import io.github.realmlabs.klua.core.value.LuaUserData
import io.github.realmlabs.klua.core.value.LuaUserDataProperty
import io.github.realmlabs.klua.core.value.LuaUserDataType
import io.github.realmlabs.klua.core.value.LuaValue
import io.github.realmlabs.klua.core.vm.LuaVm
import io.github.realmlabs.klua.core.vm.LuaVmException

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
            KLuaCoreExecution.Success(LuaVm(globals.table).execute(chunk.prototype, vmArguments).map(::toPublicValue))
        } catch (error: LuaVmException) {
            KLuaCoreExecution.RuntimeError(error.message ?: "runtime error")
        }
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

    public fun get(name: String): KLuaCoreValue = toPublicValue(table.rawGet(LuaString(name)))

    public fun set(name: String, value: KLuaCoreValue): Boolean {
        val luaValue = value.toLuaValueOrNull(this) ?: return false
        table.rawSet(LuaString(name), luaValue)
        return true
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
                    callCoreUserDataSetter(type.cast(receiver.value), propertySetter, value)
                }
            },
        )
    }

    internal fun userDataType(value: Any): LuaUserDataType? {
        val methods = userDataTypes.entries.firstOrNull { (type, _) -> type.isInstance(value) }?.value.orEmpty()
        val properties = userDataProperties.entries.firstOrNull { (type, _) -> type.isInstance(value) }?.value.orEmpty()
        if (methods.isEmpty() && properties.isEmpty()) {
            return null
        }
        return LuaUserDataType(methods.toMap(), properties.toMap())
    }
}

public fun interface KLuaCoreFunction {
    public fun call(arguments: List<KLuaCoreValue>): KLuaCoreCallResult
}

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

    public data class RuntimeError(
        public val message: String,
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
    ) : KLuaCoreExecution
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
        is KLuaCoreValue.UserDataValue -> "userdata"
        is KLuaCoreValue.UnsupportedValue -> typeName
    }
}

private fun KLuaCoreValue.toLuaValueOrNull(globals: KLuaCoreGlobals): LuaValue? {
    return when (this) {
        KLuaCoreValue.Nil -> LuaNil
        is KLuaCoreValue.BooleanValue -> LuaBoolean(value)
        is KLuaCoreValue.IntegerValue -> LuaInteger(value)
        is KLuaCoreValue.NumberValue -> LuaFloat(value)
        is KLuaCoreValue.StringValue -> LuaString(value)
        is KLuaCoreValue.UserDataValue -> LuaUserData(value) { hostValue -> globals.userDataType(hostValue) }
        is KLuaCoreValue.UnsupportedValue -> null
    }
}

private fun toPublicValue(value: LuaValue): KLuaCoreValue {
    return when (value) {
        LuaNil -> KLuaCoreValue.Nil
        is LuaBoolean -> KLuaCoreValue.BooleanValue(value.value)
        is LuaInteger -> KLuaCoreValue.IntegerValue(value.value)
        is LuaFloat -> KLuaCoreValue.NumberValue(value.value)
        is LuaString -> KLuaCoreValue.StringValue(value.value)
        is LuaUserData -> KLuaCoreValue.UserDataValue(value.value)
        is LuaNativeFunction -> KLuaCoreValue.UnsupportedValue("function")
        else -> KLuaCoreValue.UnsupportedValue(typeName = value.publicTypeName())
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

private fun callCoreFunction(
    function: KLuaCoreFunction,
    arguments: List<LuaValue>,
    globals: KLuaCoreGlobals,
): List<LuaValue> {
    return try {
        when (val result = function.call(arguments.map(::toPublicValue))) {
            is KLuaCoreCallResult.Success -> result.values.map { value ->
                value.toLuaValueOrNull(globals)
                    ?: throw LuaVmException("cannot return ${value.publicTypeName()} as Lua value")
            }
            is KLuaCoreCallResult.RuntimeError -> throw LuaVmException(result.message)
        }
    } catch (error: LuaVmException) {
        throw error
    } catch (error: RuntimeException) {
        throw LuaVmException(error.message ?: error::class.java.simpleName)
    }
}

private fun <T : Any> callCoreUserDataMethod(
    receiver: T,
    method: KLuaCoreUserDataMethod<T>,
    arguments: List<LuaValue>,
    globals: KLuaCoreGlobals,
): List<LuaValue> {
    return try {
        when (val result = method.call(receiver, arguments.map(::toPublicValue))) {
            is KLuaCoreCallResult.Success -> result.values.map { value ->
                value.toLuaValueOrNull(globals)
                    ?: throw LuaVmException("cannot return ${value.publicTypeName()} as Lua value")
            }
            is KLuaCoreCallResult.RuntimeError -> throw LuaVmException(result.message)
        }
    } catch (error: LuaVmException) {
        throw error
    } catch (error: RuntimeException) {
        throw LuaVmException(error.message ?: error::class.java.simpleName)
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
            is KLuaCoreCallResult.RuntimeError -> throw LuaVmException(result.message)
        }
    } catch (error: LuaVmException) {
        throw error
    } catch (error: RuntimeException) {
        throw LuaVmException(error.message ?: error::class.java.simpleName)
    }
}

private fun <T : Any> callCoreUserDataSetter(
    receiver: T,
    setter: KLuaCoreUserDataSetter<T>,
    value: LuaValue,
): List<LuaValue> {
    return try {
        when (val result = setter.set(receiver, toPublicValue(value))) {
            is KLuaCoreCallResult.Success -> emptyList()
            is KLuaCoreCallResult.RuntimeError -> throw LuaVmException(result.message)
        }
    } catch (error: LuaVmException) {
        throw error
    } catch (error: RuntimeException) {
        throw LuaVmException(error.message ?: error::class.java.simpleName)
    }
}
