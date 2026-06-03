package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState
import java.util.Random
import java.util.function.Consumer
import kotlin.math.absoluteValue
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

public object LuaStdlib {
    private var random = Random()

    @JvmStatic
    public fun openLibs(state: LuaState): LuaState {
        return openLibs(state, standardOutput())
    }

    @JvmStatic
    public fun openLibs(state: LuaState, output: Consumer<String>): LuaState {
        openBase(state, output)
        openMath(state)
        openString(state)
        openTable(state)
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
        state.register("rawget", ::rawget)
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
        setFunctionField(state, "cos", ::mathCos)
        setFunctionField(state, "floor", ::mathFloor)
        setFunctionField(state, "max", ::mathMax)
        setFunctionField(state, "min", ::mathMin)
        setFunctionField(state, "random", ::mathRandom)
        setFunctionField(state, "randomseed", ::mathRandomSeed)
        setFunctionField(state, "sin", ::mathSin)
        setFunctionField(state, "tan", ::mathTan)
        setNumberField(state, "huge", Double.POSITIVE_INFINITY)
        setNumberField(state, "pi", Math.PI)
        state.setGlobal("math")
        return state
    }

    @JvmStatic
    public fun openString(state: LuaState): LuaState {
        state.newTable()
        setFunctionField(state, "byte", ::stringByte)
        setFunctionField(state, "char", ::stringChar)
        setFunctionField(state, "find", ::stringFind)
        setFunctionField(state, "gsub", ::stringGsub)
        setFunctionField(state, "len", ::stringLen)
        setFunctionField(state, "lower", ::stringLower)
        setFunctionField(state, "match", ::stringMatch)
        setFunctionField(state, "rep", ::stringRep)
        setFunctionField(state, "reverse", ::stringReverse)
        setFunctionField(state, "sub", ::stringSub)
        setFunctionField(state, "upper", ::stringUpper)
        state.setGlobal("string")
        return state
    }

    @JvmStatic
    public fun openTable(state: LuaState): LuaState {
        state.newTable()
        setFunctionField(state, "concat", ::tableConcat)
        setFunctionField(state, "unpack", ::tableUnpack)
        state.setGlobal("table")
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

    private fun rawget(context: LuaCallContext): LuaReturn {
        if (!context.isTable(1)) {
            throw LuaRuntimeException("bad argument #1 to 'rawget' (table expected)")
        }
        if (context.isNone(2) || context.isNil(2)) {
            throw LuaRuntimeException("table index is nil")
        }
        return LuaReturn.of(context.getTableValue(1, context.get(2)))
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

    private fun mathCos(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(cos(requiredNumber(context, 1, "math.cos")))
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

    private fun mathTan(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(tan(requiredNumber(context, 1, "math.tan")))
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

    private fun stringByte(context: LuaCallContext): LuaReturn {
        val text = requiredString(context, 1, "string.byte")
        val start = if (context.isNone(2) || context.isNil(2)) {
            1L
        } else {
            requiredInteger(context, 2, "string.byte")
        }
        val end = if (context.isNone(3) || context.isNil(3)) {
            start
        } else {
            requiredInteger(context, 3, "string.byte")
        }
        val range = text.luaIndexRange(start, end)
        if (range.isEmpty()) {
            return LuaReturn.none()
        }
        return LuaReturn.ofValues(range.map { index -> text[index - 1].code.toLong() })
    }

    private fun stringChar(context: LuaCallContext): LuaReturn {
        val chars = StringBuilder()
        for (index in 1..context.argumentCount) {
            val code = requiredInteger(context, index, "string.char")
            if (code !in 0L..255L) {
                throw LuaRuntimeException("bad argument #$index to 'string.char' (value out of range)")
            }
            chars.append(code.toInt().toChar())
        }
        return LuaReturn.of(chars.toString())
    }

    private fun stringLen(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(requiredString(context, 1, "string.len").length.toLong())
    }

    private fun stringGsub(context: LuaCallContext): LuaReturn {
        val text = requiredString(context, 1, "string.gsub")
        val pattern = requiredString(context, 2, "string.gsub")
        val replacement = requiredString(context, 3, "string.gsub")
        val limit = if (context.isNone(4) || context.isNil(4)) {
            Long.MAX_VALUE
        } else {
            requiredInteger(context, 4, "string.gsub")
        }
        if (pattern.hasLuaPatternMagic()) {
            throw LuaRuntimeException("string patterns are not supported")
        }
        if (pattern.isEmpty()) {
            throw LuaRuntimeException("empty patterns are not supported")
        }
        if (limit <= 0L) {
            return LuaReturn.of(text, 0L)
        }

        val result = StringBuilder()
        var cursor = 0
        var replacements = 0L
        while (replacements < limit) {
            val foundIndex = text.indexOf(pattern, cursor)
            if (foundIndex < 0) {
                break
            }
            result.append(text, cursor, foundIndex)
            result.append(replacement)
            cursor = foundIndex + pattern.length
            replacements++
        }
        result.append(text, cursor, text.length)
        return LuaReturn.of(result.toString(), replacements)
    }

    private fun stringFind(context: LuaCallContext): LuaReturn {
        val text = requiredString(context, 1, "string.find")
        val pattern = requiredString(context, 2, "string.find")
        val start = if (context.isNone(3) || context.isNil(3)) {
            1L
        } else {
            requiredInteger(context, 3, "string.find")
        }
        val plain = context.toBoolean(4)
        if (!plain && pattern.hasLuaPatternMagic()) {
            throw LuaRuntimeException("string patterns are not supported")
        }

        val startIndex = text.normalizeSearchStart(start) - 1
        val foundIndex = text.indexOf(pattern, startIndex)
        if (foundIndex < 0) {
            return LuaReturn.of(null)
        }
        return LuaReturn.of(foundIndex + 1L, foundIndex + pattern.length.toLong())
    }

    private fun stringLower(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(requiredString(context, 1, "string.lower").lowercase())
    }

    private fun stringMatch(context: LuaCallContext): LuaReturn {
        val text = requiredString(context, 1, "string.match")
        val pattern = requiredString(context, 2, "string.match")
        val start = if (context.isNone(3) || context.isNil(3)) {
            1L
        } else {
            requiredInteger(context, 3, "string.match")
        }
        if (pattern.hasLuaPatternMagic()) {
            throw LuaRuntimeException("string patterns are not supported")
        }

        val startIndex = text.normalizeSearchStart(start) - 1
        val foundIndex = text.indexOf(pattern, startIndex)
        if (foundIndex < 0) {
            return LuaReturn.of(null)
        }
        return LuaReturn.of(text.substring(foundIndex, foundIndex + pattern.length))
    }

    private fun stringRep(context: LuaCallContext): LuaReturn {
        val text = requiredString(context, 1, "string.rep")
        val count = requiredInteger(context, 2, "string.rep")
        if (count <= 0L) {
            return LuaReturn.of("")
        }
        if (count > Int.MAX_VALUE) {
            throw LuaRuntimeException("bad argument #2 to 'string.rep' (repeat count too large)")
        }
        return LuaReturn.of(text.repeat(count.toInt()))
    }

    private fun stringReverse(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(requiredString(context, 1, "string.reverse").reversed())
    }

    private fun stringSub(context: LuaCallContext): LuaReturn {
        val text = requiredString(context, 1, "string.sub")
        val start = requiredInteger(context, 2, "string.sub")
        val end = if (context.isNone(3) || context.isNil(3)) {
            -1L
        } else {
            requiredInteger(context, 3, "string.sub")
        }
        return LuaReturn.of(text.substringByLuaRange(start, end))
    }

    private fun stringUpper(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(requiredString(context, 1, "string.upper").uppercase())
    }

    private fun tableConcat(context: LuaCallContext): LuaReturn {
        if (!context.isTable(1)) {
            throw LuaRuntimeException("bad argument #1 to 'table.concat' (table expected)")
        }

        val separator = if (context.isNone(2) || context.isNil(2)) {
            ""
        } else {
            requiredString(context, 2, "table.concat")
        }
        val start = if (context.isNone(3) || context.isNil(3)) {
            1L
        } else {
            requiredInteger(context, 3, "table.concat")
        }
        val end = if (context.isNone(4) || context.isNil(4)) {
            context.tableLength(1) ?: 0L
        } else {
            requiredInteger(context, 4, "table.concat")
        }

        if (start > end) {
            return LuaReturn.of("")
        }

        val builder = StringBuilder()
        var index = start
        while (index <= end) {
            if (index > start) {
                builder.append(separator)
            }
            builder.append(tableConcatValue(context, index))
            index++
        }
        return LuaReturn.of(builder.toString())
    }

    private fun tableConcatValue(context: LuaCallContext, index: Long): String {
        val value = try {
            context.getTableValue(1, index)
        } catch (_: IllegalArgumentException) {
            null
        }
        return when (value) {
            is Byte -> value.toLong().toString()
            is Short -> value.toLong().toString()
            is Int -> value.toLong().toString()
            is Long -> value.toString()
            is Float -> value.toDouble().toString()
            is Double -> value.toString()
            is CharSequence -> value.toString()
            else -> throw LuaRuntimeException("invalid value at index $index in table for 'concat'")
        }
    }

    private fun tableUnpack(context: LuaCallContext): LuaReturn {
        if (!context.isTable(1)) {
            throw LuaRuntimeException("bad argument #1 to 'table.unpack' (table expected)")
        }

        val start = if (context.isNone(2) || context.isNil(2)) {
            1L
        } else {
            requiredInteger(context, 2, "table.unpack")
        }
        val end = if (context.isNone(3) || context.isNil(3)) {
            context.tableLength(1) ?: 0L
        } else {
            requiredInteger(context, 3, "table.unpack")
        }

        if (start > end) {
            return LuaReturn.none()
        }

        val values = mutableListOf<Any?>()
        var index = start
        while (index <= end) {
            values += try {
                context.getTableValue(1, index)
            } catch (_: IllegalArgumentException) {
                throw LuaRuntimeException("invalid value at index $index in table for 'unpack'")
            }
            index++
        }
        return LuaReturn.ofValues(values)
    }

    private fun requiredString(context: LuaCallContext, index: Int, functionName: String): String {
        return context.toString(index)
            ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (string expected)")
    }

    private fun requiredInteger(context: LuaCallContext, index: Int, functionName: String): Long {
        return context.toInteger(index)
            ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (integer expected)")
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

    private fun setNumberField(state: LuaState, name: String, value: Double) {
        state.pushNumber(value)
        state.setField(-2, name)
    }

    private fun String.substringByLuaRange(start: Long, end: Long): String {
        val range = luaIndexRange(start, end)
        if (range.isEmpty()) {
            return ""
        }
        return substring(range.first - 1, range.last)
    }

    private fun String.luaIndexRange(start: Long, end: Long): IntRange {
        val normalizedStart = normalizeStringIndex(start)
        val normalizedEnd = normalizeStringIndex(end)
        if (normalizedStart > normalizedEnd) {
            return IntRange.EMPTY
        }
        val first = normalizedStart.coerceIn(1, length + 1)
        val last = normalizedEnd.coerceIn(0, length)
        if (first > last) {
            return IntRange.EMPTY
        }
        return first..last
    }

    private fun String.normalizeStringIndex(index: Long): Int {
        return when {
            index > Int.MAX_VALUE -> Int.MAX_VALUE
            index < Int.MIN_VALUE -> Int.MIN_VALUE
            index >= 0L -> index.toInt()
            else -> (length + index + 1L).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        }
    }

    private fun String.normalizeSearchStart(index: Long): Int {
        val normalized = when {
            index >= 0L -> index
            else -> length + index + 1L
        }
        return normalized.coerceIn(1L, length + 1L).toInt()
    }

    private fun String.hasLuaPatternMagic(): Boolean {
        return any { char -> char in "^$()%.[]*+-?" }
    }
}
