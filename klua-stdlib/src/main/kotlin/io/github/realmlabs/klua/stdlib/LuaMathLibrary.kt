package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState
import java.util.Random
import kotlin.math.absoluteValue
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

internal object LuaMathLibrary {
    private var random = Random()

    fun open(state: LuaState): LuaState {
        state.newTable()
        setFunctionField(state, "abs", ::mathAbs)
        setFunctionField(state, "ceil", ::mathCeil)
        setFunctionField(state, "cos", ::mathCos)
        setFunctionField(state, "deg", ::mathDeg)
        setFunctionField(state, "exp", ::mathExp)
        setFunctionField(state, "floor", ::mathFloor)
        setFunctionField(state, "log", ::mathLog)
        setFunctionField(state, "max", ::mathMax)
        setFunctionField(state, "min", ::mathMin)
        setFunctionField(state, "rad", ::mathRad)
        setFunctionField(state, "random", ::mathRandom)
        setFunctionField(state, "randomseed", ::mathRandomSeed)
        setFunctionField(state, "sin", ::mathSin)
        setFunctionField(state, "sqrt", ::mathSqrt)
        setFunctionField(state, "tan", ::mathTan)
        setNumberField(state, "huge", Double.POSITIVE_INFINITY)
        setNumberField(state, "pi", Math.PI)
        state.setGlobal("math")
        return state
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

    private fun mathCos(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(cos(requiredNumber(context, 1, "math.cos")))
    }

    private fun mathDeg(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(requiredNumber(context, 1, "math.deg") * 180.0 / Math.PI)
    }

    private fun mathExp(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(exp(requiredNumber(context, 1, "math.exp")))
    }

    private fun mathFloor(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(floor(requiredNumber(context, 1, "math.floor")).toLong())
    }

    private fun mathLog(context: LuaCallContext): LuaReturn {
        val value = ln(requiredNumber(context, 1, "math.log"))
        if (context.argumentCount < 2) {
            return LuaReturn.of(value)
        }
        return LuaReturn.of(value / ln(requiredNumber(context, 2, "math.log")))
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

    private fun mathRad(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(requiredNumber(context, 1, "math.rad") * Math.PI / 180.0)
    }

    private fun mathRandom(context: LuaCallContext): LuaReturn {
        return when (context.argumentCount) {
            0 -> LuaReturn.of(random.nextDouble())
            1 -> {
                val upper = requiredInteger(context, 1, "math.random")
                LuaReturn.of(randomInteger(1L, upper))
            }
            2 -> {
                val lower = requiredInteger(context, 1, "math.random")
                val upper = requiredInteger(context, 2, "math.random")
                LuaReturn.of(randomInteger(lower, upper))
            }
            else -> throw LuaRuntimeException("wrong number of arguments to 'math.random'")
        }
    }

    private fun mathRandomSeed(context: LuaCallContext): LuaReturn {
        val seed = requiredInteger(context, 1, "math.randomseed")
        random = Random(seed)
        return LuaReturn.none()
    }

    private fun mathSin(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(sin(requiredNumber(context, 1, "math.sin")))
    }

    private fun mathSqrt(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(sqrt(requiredNumber(context, 1, "math.sqrt")))
    }

    private fun mathTan(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(tan(requiredNumber(context, 1, "math.tan")))
    }

    private fun requiredNumber(context: LuaCallContext, index: Int, functionName: String): Double {
        return context.toNumber(index)
            ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (number expected)")
    }

    private fun requiredInteger(context: LuaCallContext, index: Int, functionName: String): Long {
        return context.toInteger(index)
            ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (integer expected)")
    }

    private fun requireMathArguments(context: LuaCallContext, functionName: String) {
        if (context.argumentCount == 0) {
            throw LuaRuntimeException("bad argument #1 to '$functionName' (number expected)")
        }
    }

    private fun randomInteger(lower: Long, upper: Long): Long {
        if (lower > upper) {
            throw LuaRuntimeException("bad argument #1 to 'math.random' (interval is empty)")
        }
        val width = upper - lower + 1
        if (width <= 0L || width > Int.MAX_VALUE) {
            throw LuaRuntimeException("bad argument #1 to 'math.random' (interval is too large)")
        }
        return lower + random.nextInt(width.toInt())
    }

    private fun setFunctionField(state: LuaState, name: String, function: (LuaCallContext) -> LuaReturn) {
        state.pushFunction(function)
        state.setField(-2, name)
    }

    private fun setNumberField(state: LuaState, name: String, value: Double) {
        state.pushNumber(value)
        state.setField(-2, name)
    }
}
