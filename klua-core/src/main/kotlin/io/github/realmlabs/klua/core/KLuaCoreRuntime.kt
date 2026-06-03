package io.github.realmlabs.klua.core

import io.github.realmlabs.klua.core.compiler.Compiler
import io.github.realmlabs.klua.core.compiler.CompilerException
import io.github.realmlabs.klua.core.bytecode.Prototype
import io.github.realmlabs.klua.core.lexer.LexerException
import io.github.realmlabs.klua.core.parser.ParserException
import io.github.realmlabs.klua.core.value.LuaBoolean
import io.github.realmlabs.klua.core.value.LuaFloat
import io.github.realmlabs.klua.core.value.LuaInteger
import io.github.realmlabs.klua.core.value.LuaNil
import io.github.realmlabs.klua.core.value.LuaString
import io.github.realmlabs.klua.core.value.LuaTable
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
            value.toLuaValueOrNull()
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
    public companion object {
        @JvmStatic
        public fun create(): KLuaCoreGlobals = KLuaCoreGlobals()
    }

    public fun get(name: String): KLuaCoreValue = toPublicValue(table.rawGet(LuaString(name)))

    public fun set(name: String, value: KLuaCoreValue): Boolean {
        val luaValue = value.toLuaValueOrNull() ?: return false
        table.rawSet(LuaString(name), luaValue)
        return true
    }
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
        is KLuaCoreValue.UnsupportedValue -> typeName
    }
}

private fun KLuaCoreValue.toLuaValueOrNull(): LuaValue? {
    return when (this) {
        KLuaCoreValue.Nil -> LuaNil
        is KLuaCoreValue.BooleanValue -> LuaBoolean(value)
        is KLuaCoreValue.IntegerValue -> LuaInteger(value)
        is KLuaCoreValue.NumberValue -> LuaFloat(value)
        is KLuaCoreValue.StringValue -> LuaString(value)
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
        else -> KLuaCoreValue.UnsupportedValue(typeName = value::class.simpleName ?: "value")
    }
}
