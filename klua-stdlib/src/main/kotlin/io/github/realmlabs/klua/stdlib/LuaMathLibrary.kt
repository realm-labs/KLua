package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState
import java.util.Random
import kotlin.math.absoluteValue
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

internal object LuaMathLibrary {
    fun open(state: LuaState): LuaState {
        val randomState = MathRandomState()
        state.newTable()
        setFunctionField(state, "abs", ::mathAbs)
        setFunctionField(state, "acos", ::mathAcos)
        setFunctionField(state, "asin", ::mathAsin)
        setFunctionField(state, "atan", ::mathAtan)
        setFunctionField(state, "ceil", ::mathCeil)
        setFunctionField(state, "cos", ::mathCos)
        setFunctionField(state, "deg", ::mathDeg)
        setFunctionField(state, "exp", ::mathExp)
        setFunctionField(state, "floor", ::mathFloor)
        setFunctionField(state, "fmod", ::mathFmod)
        setFunctionField(state, "log", ::mathLog)
        setFunctionField(state, "max", ::mathMax)
        setFunctionField(state, "min", ::mathMin)
        setFunctionField(state, "modf", ::mathModf)
        setFunctionField(state, "rad", ::mathRad)
        setFunctionField(state, "random") { context -> mathRandom(context, randomState) }
        setFunctionField(state, "randomseed") { context -> mathRandomSeed(context, randomState) }
        setFunctionField(state, "sin", ::mathSin)
        setFunctionField(state, "sqrt", ::mathSqrt)
        setFunctionField(state, "tan", ::mathTan)
        setFunctionField(state, "type", ::mathType)
        setFunctionField(state, "tointeger", ::mathToInteger)
        setFunctionField(state, "ult", ::mathUnsignedLessThan)
        setNumberField(state, "huge", Double.POSITIVE_INFINITY)
        setIntegerField(state, "maxinteger", Long.MAX_VALUE)
        setIntegerField(state, "mininteger", Long.MIN_VALUE)
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

    private fun mathAcos(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(acos(requiredNumber(context, 1, "math.acos")))
    }

    private fun mathAsin(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(asin(requiredNumber(context, 1, "math.asin")))
    }

    private fun mathAtan(context: LuaCallContext): LuaReturn {
        val y = requiredNumber(context, 1, "math.atan")
        if (context.argumentCount < 2) {
            return LuaReturn.of(atan(y))
        }
        return LuaReturn.of(atan2(y, requiredNumber(context, 2, "math.atan")))
    }

    private fun mathCeil(context: LuaCallContext): LuaReturn {
        val integer = integerSubtype(context, 1)
        if (integer != null) {
            return LuaReturn.of(integer)
        }
        val rounded = ceil(requiredNumber(context, 1, "math.ceil"))
        return LuaReturn.of(numberToIntegerSubtype(rounded) ?: rounded)
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
        val integer = integerSubtype(context, 1)
        if (integer != null) {
            return LuaReturn.of(integer)
        }
        val rounded = floor(requiredNumber(context, 1, "math.floor"))
        return LuaReturn.of(numberToIntegerSubtype(rounded) ?: rounded)
    }

    private fun mathFmod(context: LuaCallContext): LuaReturn {
        val integerDividend = integerSubtype(context, 1)
        val integerDivisor = integerSubtype(context, 2)
        if (integerDividend != null && integerDivisor != null) {
            if (integerDivisor == 0L) {
                throw LuaRuntimeException("bad argument #2 to 'math.fmod' (zero)")
            }
            if (integerDivisor == -1L) {
                return LuaReturn.of(0L)
            }
            return LuaReturn.of(integerDividend % integerDivisor)
        }
        val dividend = requiredNumber(context, 1, "math.fmod")
        val divisor = requiredNumber(context, 2, "math.fmod")
        return LuaReturn.of(dividend % divisor)
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
        var maxInteger = integerSubtype(context, 1)
        var max = requiredNumber(context, 1, "math.max")
        for (index in 2..context.argumentCount) {
            val value = requiredNumber(context, index, "math.max")
            if (value > max) {
                max = value
                maxInteger = integerSubtype(context, index)
            }
        }
        return LuaReturn.of(maxInteger ?: max)
    }

    private fun mathMin(context: LuaCallContext): LuaReturn {
        requireMathArguments(context, "math.min")
        var minInteger = integerSubtype(context, 1)
        var min = requiredNumber(context, 1, "math.min")
        for (index in 2..context.argumentCount) {
            val value = requiredNumber(context, index, "math.min")
            if (value < min) {
                min = value
                minInteger = integerSubtype(context, index)
            }
        }
        return LuaReturn.of(minInteger ?: min)
    }

    private fun mathModf(context: LuaCallContext): LuaReturn {
        val integer = integerSubtype(context, 1)
        if (integer != null) {
            return LuaReturn.of(integer, 0.0)
        }
        val value = requiredNumber(context, 1, "math.modf")
        val integerPart = if (value < 0) ceil(value) else floor(value)
        val fraction = if (value == integerPart) 0.0 else value - integerPart
        return LuaReturn.of(numberToIntegerSubtype(integerPart) ?: integerPart, fraction)
    }

    private fun mathRad(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(requiredNumber(context, 1, "math.rad") * Math.PI / 180.0)
    }

    private fun mathRandom(context: LuaCallContext, randomState: MathRandomState): LuaReturn {
        return when (context.argumentCount) {
            0 -> LuaReturn.of(randomState.random.nextDouble())
            1 -> {
                val upper = requiredInteger(context, 1, "math.random")
                if (upper == 0L) {
                    return LuaReturn.of(randomState.random.nextLong())
                }
                LuaReturn.of(randomInteger(randomState, 1L, upper))
            }
            2 -> {
                val lower = requiredInteger(context, 1, "math.random")
                val upper = requiredInteger(context, 2, "math.random")
                LuaReturn.of(randomInteger(randomState, lower, upper))
            }
            else -> throw LuaRuntimeException("wrong number of arguments to 'math.random'")
        }
    }

    private fun mathRandomSeed(context: LuaCallContext, randomState: MathRandomState): LuaReturn {
        if (context.argumentCount > 2) {
            throw LuaRuntimeException("wrong number of arguments to 'math.randomseed'")
        }
        val firstSeed = if (context.argumentCount == 0) {
            System.nanoTime() xor Random().nextLong()
        } else {
            requiredInteger(context, 1, "math.randomseed")
        }
        val secondSeed = if (context.argumentCount < 2) {
            0L
        } else {
            requiredInteger(context, 2, "math.randomseed")
        }
        randomState.random = Random(combineSeeds(firstSeed, secondSeed))
        return LuaReturn.of(firstSeed, secondSeed)
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

    private fun mathType(context: LuaCallContext): LuaReturn {
        requireAnyArgument(context, "math.type")
        if (context.typeName(1) != "number") {
            return LuaReturn.of(null)
        }
        return when (context.get(1)) {
            is Byte,
            is Short,
            is Int,
            is Long,
            -> LuaReturn.of("integer")
            is Float,
            is Double,
            -> LuaReturn.of("float")
            else -> LuaReturn.of(null)
        }
    }

    private fun mathToInteger(context: LuaCallContext): LuaReturn {
        requireAnyArgument(context, "math.tointeger")
        return LuaReturn.of(context.toInteger(1))
    }

    private fun mathUnsignedLessThan(context: LuaCallContext): LuaReturn {
        val left = requiredInteger(context, 1, "math.ult")
        val right = requiredInteger(context, 2, "math.ult")
        return LuaReturn.of(java.lang.Long.compareUnsigned(left, right) < 0)
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
            throw LuaRuntimeException("bad argument #1 to '$functionName' (value expected)")
        }
    }

    private fun requireAnyArgument(context: LuaCallContext, functionName: String) {
        if (context.isNone(1)) {
            throw LuaRuntimeException("bad argument #1 to '$functionName' (value expected)")
        }
    }

    private fun integerSubtype(context: LuaCallContext, index: Int): Long? {
        if (context.typeName(index) != "number") {
            return null
        }
        return when (val value = context.get(index)) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            else -> null
        }
    }

    private fun numberToIntegerSubtype(value: Double): Long? {
        if (!value.isFinite()) {
            return null
        }
        val integer = value.toLong()
        return if (integer.toDouble() == value) integer else null
    }

    private fun randomInteger(randomState: MathRandomState, lower: Long, upper: Long): Long {
        if (lower > upper) {
            throw LuaRuntimeException("bad argument #1 to 'math.random' (interval is empty)")
        }
        val width = upper - lower + 1
        if (width == 0L) {
            return randomState.random.nextLong()
        }
        if (width > 0L) {
            return lower + randomLongBelow(randomState, width)
        }

        var candidate: Long
        do {
            candidate = randomState.random.nextLong()
        } while (java.lang.Long.compareUnsigned(candidate, width) >= 0)
        return lower + candidate
    }

    private fun randomLongBelow(randomState: MathRandomState, bound: Long): Long {
        val mask = bound - 1
        if (bound and mask == 0L) {
            return randomState.random.nextLong() and mask
        }

        var candidate: Long
        var value: Long
        do {
            candidate = randomState.random.nextLong().ushr(1)
            value = candidate % bound
        } while (candidate + mask - value < 0L)
        return value
    }

    private fun combineSeeds(firstSeed: Long, secondSeed: Long): Long {
        return firstSeed xor java.lang.Long.rotateLeft(secondSeed, 32)
    }

    private fun setFunctionField(state: LuaState, name: String, function: (LuaCallContext) -> LuaReturn) {
        state.pushFunction(function)
        state.setField(-2, name)
    }

    private fun setIntegerField(state: LuaState, name: String, value: Long) {
        state.pushInteger(value)
        state.setField(-2, name)
    }

    private fun setNumberField(state: LuaState, name: String, value: Double) {
        state.pushNumber(value)
        state.setField(-2, name)
    }

    private data class MathRandomState(
        var random: Random = Random(),
    )
}
