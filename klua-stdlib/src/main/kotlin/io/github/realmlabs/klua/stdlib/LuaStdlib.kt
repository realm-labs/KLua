package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState

public object LuaStdlib {
    @JvmStatic
    public fun openLibs(state: LuaState): LuaState {
        return openBase(state)
    }

    @JvmStatic
    public fun openBase(state: LuaState): LuaState {
        state.register("assert", ::assert)
        state.register("error", ::error)
        state.register("select", ::select)
        state.register("tonumber", ::tonumber)
        state.register("tostring", ::tostring)
        state.register("type", ::type)
        return state
    }

    private fun assert(context: LuaCallContext): LuaReturn {
        if (!context.toBoolean(1)) {
            throw LuaRuntimeException(context.toString(2) ?: "assertion failed!")
        }
        return LuaReturn.ofValues((1..context.argumentCount).map { index -> context.get(index) })
    }

    private fun error(context: LuaCallContext): LuaReturn {
        throw LuaRuntimeException(context.toString(1) ?: context.typeName(1))
    }

    private fun select(context: LuaCallContext): LuaReturn {
        if (context.toString(1) == "#") {
            return LuaReturn.of((context.argumentCount - 1).toLong())
        }

        val index = context.toInteger(1)
            ?: throw LuaRuntimeException("bad argument #1 to 'select' (number expected)")
        val start = when {
            index > 0L -> index + 1L
            index < 0L -> context.argumentCount + index + 1L
            else -> throw LuaRuntimeException("bad argument #1 to 'select' (index out of range)")
        }
        if (start < 2L || start > context.argumentCount.toLong()) {
            throw LuaRuntimeException("bad argument #1 to 'select' (index out of range)")
        }
        return LuaReturn.ofValues((start.toInt()..context.argumentCount).map { argument -> context.get(argument) })
    }

    private fun tonumber(context: LuaCallContext): LuaReturn {
        val value = context.get(1) ?: return LuaReturn.of(null)
        return when (value) {
            is Byte -> LuaReturn.of(value.toLong())
            is Short -> LuaReturn.of(value.toLong())
            is Int -> LuaReturn.of(value.toLong())
            is Long -> LuaReturn.of(value)
            is Float -> LuaReturn.of(value.toDouble())
            is Double -> LuaReturn.of(value)
            is CharSequence -> LuaReturn.of(parseNumber(value.toString()))
            else -> LuaReturn.of(null)
        }
    }

    private fun tostring(context: LuaCallContext): LuaReturn {
        val text = when (context.typeName(1)) {
            "nil" -> "nil"
            "boolean" -> context.toBoolean(1).toString()
            "number",
            "string",
            -> context.toString(1)
            "userdata" -> context.get(1)?.toString() ?: "userdata"
            else -> context.typeName(1)
        }
        return LuaReturn.of(text)
    }

    private fun type(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(context.typeName(1))
    }

    private fun parseNumber(text: String): Number? {
        val trimmed = text.trim()
        return trimmed.toLongOrNull() ?: trimmed.toDoubleOrNull()
    }
}
