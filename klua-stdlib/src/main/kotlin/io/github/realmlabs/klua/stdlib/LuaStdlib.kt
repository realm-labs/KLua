package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState
import java.util.function.Consumer
import kotlin.math.absoluteValue
import kotlin.math.ceil
import kotlin.math.floor

public object LuaStdlib {
    @JvmStatic
    public fun openLibs(state: LuaState): LuaState {
        return openLibs(state, standardOutput())
    }

    @JvmStatic
    public fun openLibs(state: LuaState, output: Consumer<String>): LuaState {
        openBase(state, output)
        openMath(state)
        return state
    }

    @JvmStatic
    public fun openBase(state: LuaState): LuaState {
        return openBase(state, standardOutput())
    }

    @JvmStatic
    public fun openBase(state: LuaState, output: Consumer<String>): LuaState {
        state.register("assert", ::assert)
        state.register("error", ::error)
        state.register("print") { context -> print(context, output) }
        state.register("select", ::select)
        state.register("tonumber", ::tonumber)
        state.register("tostring", ::tostring)
        state.register("type", ::type)
        return state
    }

    @JvmStatic
    public fun openMath(state: LuaState): LuaState {
        state.newTable()
        setFunctionField(state, "abs", ::mathAbs)
        setFunctionField(state, "ceil", ::mathCeil)
        setFunctionField(state, "floor", ::mathFloor)
        setFunctionField(state, "max", ::mathMax)
        setFunctionField(state, "min", ::mathMin)
        state.setGlobal("math")
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

    private fun print(context: LuaCallContext, output: Consumer<String>): LuaReturn {
        output.accept((1..context.argumentCount).joinToString("\t") { index -> toLuaString(context, index) })
        return LuaReturn.none()
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
        if (!context.isNone(2) && !context.isNil(2)) {
            val base = context.toInteger(2)
                ?: throw LuaRuntimeException("bad argument #2 to 'tonumber' (number expected)")
            if (base !in 2L..36L) {
                throw LuaRuntimeException("bad argument #2 to 'tonumber' (base out of range)")
            }
            return when (value) {
                is CharSequence -> LuaReturn.of(value.toString().trim().toLongOrNull(base.toInt()))
                else -> LuaReturn.of(null)
            }
        }
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
        return LuaReturn.of(toLuaString(context, 1))
    }

    private fun type(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(context.typeName(1))
    }

    private fun mathAbs(context: LuaCallContext): LuaReturn {
        val integer = context.toInteger(1)
        if (integer != null) {
            return LuaReturn.of(integer.absoluteValue)
        }
        return LuaReturn.of(requiredNumber(context, 1, "math.abs").absoluteValue)
    }

    private fun mathCeil(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(ceil(requiredNumber(context, 1, "math.ceil")).toLong())
    }

    private fun mathFloor(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(floor(requiredNumber(context, 1, "math.floor")).toLong())
    }

    private fun mathMax(context: LuaCallContext): LuaReturn {
        requireMathArguments(context, "math.max")
        var max = requiredNumber(context, 1, "math.max")
        for (index in 2..context.argumentCount) {
            val value = requiredNumber(context, index, "math.max")
            if (value > max) {
                max = value
            }
        }
        return LuaReturn.of(max)
    }

    private fun mathMin(context: LuaCallContext): LuaReturn {
        requireMathArguments(context, "math.min")
        var min = requiredNumber(context, 1, "math.min")
        for (index in 2..context.argumentCount) {
            val value = requiredNumber(context, index, "math.min")
            if (value < min) {
                min = value
            }
        }
        return LuaReturn.of(min)
    }

    private fun requiredNumber(context: LuaCallContext, index: Int, functionName: String): Double {
        return context.toNumber(index)
            ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (number expected)")
    }

    private fun requireMathArguments(context: LuaCallContext, functionName: String) {
        if (context.argumentCount == 0) {
            throw LuaRuntimeException("bad argument #1 to '$functionName' (number expected)")
        }
    }

    private fun toLuaString(context: LuaCallContext, index: Int): String {
        return when (context.typeName(index)) {
            "nil" -> "nil"
            "boolean" -> context.toBoolean(index).toString()
            "number",
            "string",
            -> context.toString(index) ?: context.typeName(index)
            "userdata" -> context.get(index)?.toString() ?: "userdata"
            else -> context.typeName(index)
        }
    }

    private fun parseNumber(text: String): Number? {
        val trimmed = text.trim()
        return trimmed.toLongOrNull() ?: trimmed.toDoubleOrNull()
    }

    private fun standardOutput(): Consumer<String> {
        return Consumer { line -> println(line) }
    }

    private fun setFunctionField(state: LuaState, name: String, function: (LuaCallContext) -> LuaReturn) {
        state.pushFunction(function::invoke)
        state.setField(-2, name)
    }
}
