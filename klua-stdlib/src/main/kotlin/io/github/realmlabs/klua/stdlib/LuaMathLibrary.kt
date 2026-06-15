package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState
import kotlin.math.absoluteValue
import kotlin.math.acos
import kotlin.math.asin
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
        setFunctionField(state, "frexp", ::mathFrexp)
        setFunctionField(state, "ldexp", ::mathLdexp)
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
        val integer = integerSubtype(context, 1)
        if (integer != null) {
            return LuaReturn.of(integer.absoluteValue)
        }
        return LuaReturn.of(requiredNumber(context, 1, "abs").absoluteValue)
    }

    private fun mathAcos(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(acos(requiredNumber(context, 1, "acos")))
    }

    private fun mathAsin(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(asin(requiredNumber(context, 1, "asin")))
    }

    private fun mathAtan(context: LuaCallContext): LuaReturn {
        val y = requiredNumber(context, 1, "atan")
        val x = if (context.argumentCount < 2 || context.isNil(2)) {
            1.0
        } else {
            requiredNumber(context, 2, "atan")
        }
        return LuaReturn.of(atan2(y, x))
    }

    private fun mathCeil(context: LuaCallContext): LuaReturn {
        val integer = integerSubtype(context, 1)
        if (integer != null) {
            return LuaReturn.of(integer)
        }
        val rounded = ceil(requiredNumber(context, 1, "ceil"))
        return LuaReturn.of(numberToIntegerSubtype(rounded) ?: rounded)
    }

    private fun mathCos(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(cos(requiredNumber(context, 1, "cos")))
    }

    private fun mathDeg(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(requiredNumber(context, 1, "deg") * 180.0 / Math.PI)
    }

    private fun mathExp(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(exp(requiredNumber(context, 1, "exp")))
    }

    private fun mathFloor(context: LuaCallContext): LuaReturn {
        val integer = integerSubtype(context, 1)
        if (integer != null) {
            return LuaReturn.of(integer)
        }
        val rounded = floor(requiredNumber(context, 1, "floor"))
        return LuaReturn.of(numberToIntegerSubtype(rounded) ?: rounded)
    }

    private fun mathFmod(context: LuaCallContext): LuaReturn {
        val integerDividend = integerSubtype(context, 1)
        val integerDivisor = integerSubtype(context, 2)
        if (integerDividend != null && integerDivisor != null) {
            if (integerDivisor == 0L) {
                throw LuaRuntimeException("bad argument #2 to 'fmod' (zero)")
            }
            if (integerDivisor == -1L) {
                return LuaReturn.of(0L)
            }
            return LuaReturn.of(integerDividend % integerDivisor)
        }
        val dividend = requiredNumber(context, 1, "fmod")
        val divisor = requiredNumber(context, 2, "fmod")
        return LuaReturn.of(dividend % divisor)
    }

    private fun mathFrexp(context: LuaCallContext): LuaReturn {
        val value = requiredNumber(context, 1, "frexp")
        if (value == 0.0 || !value.isFinite()) {
            return LuaReturn.of(value, 0L)
        }

        var normalized = value
        var exponentOffset = 0
        if (value.absoluteValue < java.lang.Double.MIN_NORMAL) {
            normalized = java.lang.Math.scalb(value, SUBNORMAL_SCALE_BITS)
            exponentOffset = -SUBNORMAL_SCALE_BITS
        }

        val exponent = java.lang.Math.getExponent(normalized) + 1
        val mantissa = java.lang.Math.scalb(normalized, -exponent)
        return LuaReturn.of(mantissa, (exponent + exponentOffset).toLong())
    }

    private fun mathLdexp(context: LuaCallContext): LuaReturn {
        val mantissa = requiredNumber(context, 1, "ldexp")
        val exponent = requiredInteger(context, 2, "ldexp").toInt()
        return LuaReturn.of(java.lang.Math.scalb(mantissa, exponent))
    }

    private fun mathLog(context: LuaCallContext): LuaReturn {
        val number = requiredNumber(context, 1, "log")
        if (context.argumentCount < 2 || context.isNil(2)) {
            return LuaReturn.of(ln(number))
        }
        val base = requiredNumber(context, 2, "log")
        if (base == 10.0) {
            return LuaReturn.of(java.lang.Math.log10(number))
        }
        return LuaReturn.of(ln(number) / ln(base))
    }

    private fun mathMax(context: LuaCallContext): LuaReturn {
        requireMathArguments(context, "max")
        var maxIndex = 1
        for (index in 2..context.argumentCount) {
            if (mathLessThan(context, maxIndex, index)) {
                maxIndex = index
            }
        }
        return LuaReturn.of(mathMinMaxValue(context, maxIndex))
    }

    private fun mathMin(context: LuaCallContext): LuaReturn {
        requireMathArguments(context, "min")
        var minIndex = 1
        for (index in 2..context.argumentCount) {
            if (mathLessThan(context, index, minIndex)) {
                minIndex = index
            }
        }
        return LuaReturn.of(mathMinMaxValue(context, minIndex))
    }

    private fun mathLessThan(context: LuaCallContext, leftIndex: Int, rightIndex: Int): Boolean {
        context.lessThan(leftIndex, rightIndex)?.let { result -> return result }
        val leftType = context.typeName(leftIndex)
        val rightType = context.typeName(rightIndex)
        if (leftType == "number" && rightType == "number") {
            return requiredNumber(context, leftIndex, "min") < requiredNumber(context, rightIndex, "min")
        }
        if (leftType == "string" && rightType == "string") {
            return requiredString(context, leftIndex, "min") < requiredString(context, rightIndex, "min")
        }
        if (leftType == rightType) {
            throw LuaRuntimeException("attempt to compare two $leftType values")
        }
        throw LuaRuntimeException("attempt to compare $leftType with $rightType")
    }

    private fun mathMinMaxValue(context: LuaCallContext, index: Int): Any? {
        return when (context.typeName(index)) {
            "number" -> integerSubtype(context, index) ?: requiredNumber(context, index, "min")
            "string" -> requiredString(context, index, "min")
            else -> context.getLuaValue(index)
        }
    }

    private fun mathModf(context: LuaCallContext): LuaReturn {
        val integer = integerSubtype(context, 1)
        if (integer != null) {
            return LuaReturn.of(integer, 0.0)
        }
        val value = requiredNumber(context, 1, "modf")
        val integerPart = if (value < 0) ceil(value) else floor(value)
        val fraction = if (value == integerPart) 0.0 else value - integerPart
        return LuaReturn.of(numberToIntegerSubtype(integerPart) ?: integerPart, fraction)
    }

    private fun mathRad(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(requiredNumber(context, 1, "rad") * Math.PI / 180.0)
    }

    private fun mathRandom(context: LuaCallContext, randomState: MathRandomState): LuaReturn {
        val randomValue = randomState.next()
        return when (context.argumentCount) {
            0 -> LuaReturn.of(MathRandomState.toUnitDouble(randomValue))
            1 -> {
                val upper = requiredInteger(context, 1, "random")
                if (upper == 0L) {
                    return LuaReturn.of(randomValue)
                }
                LuaReturn.of(randomInteger(randomState, randomValue, 1L, upper))
            }
            2 -> {
                val lower = requiredInteger(context, 1, "random")
                val upper = requiredInteger(context, 2, "random")
                LuaReturn.of(randomInteger(randomState, randomValue, lower, upper))
            }
            else -> throw LuaRuntimeException("wrong number of arguments")
        }
    }

    private fun mathRandomSeed(context: LuaCallContext, randomState: MathRandomState): LuaReturn {
        val (firstSeed, secondSeed) = if (context.argumentCount == 0) {
            MathRandomState.makeSeed() to randomState.next()
        } else {
            val first = requiredInteger(context, 1, "randomseed")
            val second = if (context.argumentCount < 2) {
                0L
            } else {
                requiredInteger(context, 2, "randomseed")
            }
            first to second
        }
        randomState.setSeed(firstSeed, secondSeed)
        return LuaReturn.of(firstSeed, secondSeed)
    }

    private fun mathSin(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(sin(requiredNumber(context, 1, "sin")))
    }

    private fun mathSqrt(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(sqrt(requiredNumber(context, 1, "sqrt")))
    }

    private fun mathTan(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(tan(requiredNumber(context, 1, "tan")))
    }

    private fun mathType(context: LuaCallContext): LuaReturn {
        requireAnyArgument(context, "type")
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
        requireAnyArgument(context, "tointeger")
        return LuaReturn.of(context.toInteger(1))
    }

    private fun mathUnsignedLessThan(context: LuaCallContext): LuaReturn {
        val left = requiredInteger(context, 1, "ult")
        val right = requiredInteger(context, 2, "ult")
        return LuaReturn.of(java.lang.Long.compareUnsigned(left, right) < 0)
    }

    private fun requiredNumber(context: LuaCallContext, index: Int, functionName: String): Double {
        return context.toNumber(index)
            ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (number expected)")
    }

    private fun requiredString(context: LuaCallContext, index: Int, functionName: String): String {
        return context.toString(index)
            ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (string expected)")
    }

    private fun requiredInteger(context: LuaCallContext, index: Int, functionName: String): Long {
        return context.toInteger(index)
            ?: if (context.toNumber(index) != null || context.typeName(index) == "number") {
                throw LuaRuntimeException("bad argument #$index to '$functionName' (number has no integer representation)")
            } else {
                throw LuaRuntimeException("bad argument #$index to '$functionName' (number expected)")
            }
    }

    private fun requiredNumberInteger(context: LuaCallContext, index: Int, functionName: String): Long {
        return context.toInteger(index) ?: throw LuaRuntimeException(
            if (context.toNumber(index) != null) {
                "bad argument #$index to '$functionName' (number has no integer representation)"
            } else {
                "bad argument #$index to '$functionName' (number expected)"
            },
        )
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

    private fun randomInteger(randomState: MathRandomState, randomValue: Long, lower: Long, upper: Long): Long {
        if (lower > upper) {
            throw LuaRuntimeException("bad argument #1 to 'random' (interval is empty)")
        }
        val width = upper - lower
        return lower + randomState.project(randomValue, width)
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

    private class MathRandomState {
        private val state = LongArray(RANDOM_STATE_SIZE)

        init {
            setSeed(makeSeed(), 0L)
        }

        fun setSeed(firstSeed: Long, secondSeed: Long) {
            state[0] = firstSeed
            state[1] = 0xffL
            state[2] = secondSeed
            state[3] = 0L
            repeat(RANDOM_SEED_SPREAD_ROUNDS) {
                next()
            }
        }

        fun next(): Long {
            val state0 = state[0]
            val state1 = state[1]
            val state2 = state[2] xor state0
            val state3 = state[3] xor state1
            val result = java.lang.Long.rotateLeft(state1 * 5L, 7) * 9L
            state[0] = state0 xor state3
            state[1] = state1 xor state2
            state[2] = state2 xor (state1 shl 17)
            state[3] = java.lang.Long.rotateLeft(state3, 45)
            return result
        }

        fun project(randomValue: Long, upperOffset: Long): Long {
            var limit = upperOffset
            var shift = 1
            while ((limit and (limit + 1L)) != 0L) {
                limit = limit or (limit ushr shift)
                shift *= 2
            }
            var projected = randomValue and limit
            while (java.lang.Long.compareUnsigned(projected, upperOffset) > 0) {
                projected = next() and limit
            }
            return projected
        }

        companion object {
            fun toUnitDouble(value: Long): Double {
                return (value ushr RANDOM_DOUBLE_DISCARDED_BITS).toDouble() * RANDOM_DOUBLE_SCALE
            }

            fun makeSeed(): Long {
                return System.nanoTime() xor java.lang.Long.rotateLeft(System.currentTimeMillis(), 32)
            }
        }
    }

    private const val SUBNORMAL_SCALE_BITS = 54
    private const val RANDOM_STATE_SIZE = 4
    private const val RANDOM_SEED_SPREAD_ROUNDS = 16
    private const val RANDOM_DOUBLE_DISCARDED_BITS = 11
    private const val RANDOM_DOUBLE_SCALE = 1.0 / 9007199254740992.0
}
