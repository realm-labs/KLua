package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaFunction
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState
import java.math.BigInteger
import java.util.Locale

internal object LuaStringLibrary {
    private const val FORMAT_CONVERSIONS = "diouxXfFeEgGcpqs"
    private const val FORMAT_FLAGS = "-+ #0"
    private val GSUB_REPLACEMENT_TYPES = setOf("number", "string", "function", "table")
    private val UINT64_MODULUS = BigInteger.ONE.shiftLeft(Long.SIZE_BITS)

    fun open(state: LuaState): LuaState {
        state.newTable()
        setFunctionField(state, "byte", ::stringByte)
        setFunctionField(state, "char", ::stringChar)
        setFunctionField(state, "find", ::stringFind)
        setFunctionField(state, "format", ::stringFormat)
        setFunctionField(state, "gmatch", ::stringGmatch)
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
        val replacementType = context.typeName(3)
        if (replacementType !in GSUB_REPLACEMENT_TYPES) {
            throw LuaRuntimeException("bad argument #3 to 'string.gsub' (string/number/function/table expected)")
        }
        val limit = if (context.isNone(4) || context.isNil(4)) {
            Long.MAX_VALUE
        } else {
            requiredInteger(context, 4, "string.gsub")
        }
        if (limit <= 0L) {
            return LuaReturn.of(text, 0L)
        }

        val result = StringBuilder()
        var cursor = 0
        var replacements = 0L
        val compiledPattern = LuaStringPattern.compile(pattern)
        while (replacements < limit) {
            val match = compiledPattern.find(text, cursor)
            if (match == null) {
                break
            }
            result.append(text, cursor, match.startIndex)
            val wholeMatch = text.substring(match.startIndex, match.endIndex)
            result.append(
                replacementForMatch(context, replacementType, wholeMatch, match.captures),
            )
            cursor = if (match.startIndex == match.endIndex && match.endIndex < text.length) {
                result.append(text[match.endIndex])
                match.endIndex + 1
            } else if (match.startIndex == match.endIndex) {
                match.endIndex + 1
            } else {
                match.endIndex
            }
            replacements++
        }
        if (cursor <= text.length) {
            result.append(text, cursor, text.length)
        }
        return LuaReturn.of(result.toString(), replacements)
    }

    private fun replacementForMatch(
        context: LuaCallContext,
        replacementType: String,
        wholeMatch: String,
        captures: List<String>,
    ): String {
        return when (replacementType) {
            "number" -> expandReplacement(requiredString(context, 3, "string.gsub"), wholeMatch, captures, "string.gsub")
            "string" -> expandReplacement(requiredString(context, 3, "string.gsub"), wholeMatch, captures, "string.gsub")
            "function" -> {
                val result = context.call(3, replacementArguments(wholeMatch, captures)).get(1)
                replacementValueToString(result, wholeMatch)
            }
            "table" -> {
                val result = context.getTableValue(3, replacementArguments(wholeMatch, captures).first())
                replacementValueToString(result, wholeMatch)
            }
            else -> throw LuaRuntimeException("bad argument #3 to 'string.gsub' (string/number/function/table expected)")
        }
    }

    private fun replacementArguments(wholeMatch: String, captures: List<String>): List<String> {
        return captures.ifEmpty { listOf(wholeMatch) }
    }

    private fun replacementValueToString(value: Any?, wholeMatch: String): String {
        return when (value) {
            null -> wholeMatch
            false -> wholeMatch
            is Byte -> value.toString()
            is Short -> value.toString()
            is Int -> value.toString()
            is Long -> value.toString()
            is Float -> value.toString()
            is Double -> value.toString()
            is CharSequence -> value.toString()
            else -> throw LuaRuntimeException("bad argument #3 to 'string.gsub' (invalid replacement value)")
        }
    }

    private fun expandReplacement(
        replacement: String,
        wholeMatch: String,
        captures: List<String>,
        functionName: String,
    ): String {
        val result = StringBuilder()
        var index = 0
        while (index < replacement.length) {
            val char = replacement[index]
            if (char != '%') {
                result.append(char)
                index++
                continue
            }
            if (index + 1 >= replacement.length) {
                throw LuaRuntimeException("invalid use of '%' in replacement string")
            }
            when (val next = replacement[index + 1]) {
                '%' -> result.append('%')
                '0' -> result.append(wholeMatch)
                in '1'..'9' -> {
                    val captureIndex = next.digitToInt()
                    val capture = captures.getOrNull(captureIndex - 1)
                        ?: throw LuaRuntimeException("bad argument #3 to '$functionName' (invalid capture index %$next)")
                    result.append(capture)
                }
                else -> throw LuaRuntimeException("bad argument #3 to '$functionName' (invalid capture index %$next)")
            }
            index += 2
        }
        return result.toString()
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
        val startIndex = text.normalizeSearchStart(start) - 1
        val match = if (plain) {
            LuaStringPattern.literal(pattern).find(text, startIndex)
        } else {
            LuaStringPattern.compile(pattern).find(text, startIndex)
        }
        if (match == null) {
            return LuaReturn.of(null)
        }
        return LuaReturn.ofValues(listOf(match.startIndex + 1L, match.endIndex.toLong()) + match.captures)
    }

    private fun stringGmatch(context: LuaCallContext): LuaReturn {
        val text = requiredString(context, 1, "string.gmatch")
        val pattern = requiredString(context, 2, "string.gmatch")
        val start = if (context.isNone(3) || context.isNil(3)) {
            1L
        } else {
            requiredInteger(context, 3, "string.gmatch")
        }
        val compiledPattern = LuaStringPattern.compile(pattern)
        var cursor = text.normalizeSearchStart(start) - 1
        val iterator = LuaFunction { _ ->
            if (cursor > text.length) {
                LuaReturn.of(null)
            } else {
                val match = compiledPattern.find(text, cursor)
                if (match == null) {
                    LuaReturn.of(null)
                } else {
                    cursor = if (match.startIndex == match.endIndex) {
                        match.endIndex + 1
                    } else {
                        match.endIndex
                    }
                    if (match.captures.isNotEmpty()) {
                        LuaReturn.ofValues(match.captures)
                    } else {
                        LuaReturn.of(text.substring(match.startIndex, match.endIndex))
                    }
                }
            }
        }
        return LuaReturn.of(iterator)
    }

    private fun stringFormat(context: LuaCallContext): LuaReturn {
        val format = requiredString(context, 1, "string.format")
        val result = StringBuilder()
        var cursor = 0
        var argument = 2
        while (cursor < format.length) {
            val char = format[cursor]
            if (char != '%') {
                result.append(char)
                cursor++
                continue
            }

            if (cursor + 1 >= format.length) {
                throw LuaRuntimeException("invalid option '%' to 'string.format'")
            }
            if (format[cursor + 1] == '%') {
                result.append('%')
                cursor += 2
                continue
            }

            val specStart = cursor
            cursor++
            while (cursor < format.length && format[cursor] !in FORMAT_CONVERSIONS) {
                cursor++
            }
            if (cursor >= format.length) {
                throw LuaRuntimeException("invalid option '%' to 'string.format'")
            }
            val conversion = format[cursor]
            val specifier = format.substring(specStart, cursor + 1)
            if (argument > context.argumentCount) {
                throw LuaRuntimeException("bad argument #$argument to 'string.format' (no value)")
            }
            result.append(formatValue(context, argument, specifier, conversion))
            argument++
            cursor++
        }
        return LuaReturn.of(result.toString())
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
        val startIndex = text.normalizeSearchStart(start) - 1
        val match = LuaStringPattern.compile(pattern).find(text, startIndex)
        if (match == null) {
            return LuaReturn.of(null)
        }
        if (match.captures.isNotEmpty()) {
            return LuaReturn.ofValues(match.captures)
        }
        return LuaReturn.of(text.substring(match.startIndex, match.endIndex))
    }

    private fun stringRep(context: LuaCallContext): LuaReturn {
        val text = requiredString(context, 1, "string.rep")
        val count = requiredInteger(context, 2, "string.rep")
        val separator = if (context.isNone(3) || context.isNil(3)) {
            ""
        } else {
            requiredString(context, 3, "string.rep")
        }
        if (count <= 0L) {
            return LuaReturn.of("")
        }
        if (count > Int.MAX_VALUE) {
            throw LuaRuntimeException("bad argument #2 to 'string.rep' (repeat count too large)")
        }
        return LuaReturn.of(
            buildString {
                repeat(count.toInt()) { index ->
                    if (index > 0) {
                        append(separator)
                    }
                    append(text)
                }
            },
        )
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

    private fun requiredString(context: LuaCallContext, index: Int, functionName: String): String {
        return context.toString(index)
            ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (string expected)")
    }

    private fun requiredNumber(context: LuaCallContext, index: Int, functionName: String): Double {
        return context.toNumber(index)
            ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (number expected)")
    }

    private fun requiredInteger(context: LuaCallContext, index: Int, functionName: String): Long {
        return context.toInteger(index)
            ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (integer expected)")
    }

    private fun formatValue(
        context: LuaCallContext,
        index: Int,
        specifier: String,
        conversion: Char,
    ): String {
        return when (conversion) {
            's' -> {
                validateStringFormatSpecifier(specifier)
                specifier.formatWith(toLuaString(context, index))
            }
            'd',
            'i',
            'o',
            'x',
            'X',
            -> formatIntegerValue(context, index, specifier, conversion)
            'u' -> formatIntegerValue(context, index, specifier, conversion)
            'f',
            'F',
            'e',
            'E',
            'g',
            'G',
            -> {
                validateFormatSize(specifier)
                specifier.formatWith(requiredNumber(context, index, "string.format"))
            }
            'c' -> {
                validateCharacterFormatSpecifier(specifier)
                val code = requiredInteger(context, index, "string.format")
                if (code !in 0L..255L) {
                    throw LuaRuntimeException("bad argument #$index to 'string.format' (value out of range)")
                }
                specifier.formatWith(code.toInt())
            }
            'p' -> formatPointerValue(context, index, specifier)
            'q' -> {
                if (specifier != "%q") {
                    throw LuaRuntimeException("invalid option '$specifier' to 'string.format'")
                }
                quoteValue(context, index)
            }
            else -> throw LuaRuntimeException("invalid option '%$conversion' to 'string.format'")
        }
    }

    private fun String.formatWith(value: Any): String {
        return java.lang.String.format(Locale.ROOT, this, value)
    }

    private fun String.javaIntegerSpecifier(conversion: Char): String {
        return if (conversion == 'i' || conversion == 'u') {
            dropLast(1) + 'd'
        } else {
            this
        }
    }

    private fun validateFormatSize(specifier: String) {
        parseFormatSpecifier(specifier)
    }

    private fun formatPointerValue(
        context: LuaCallContext,
        index: Int,
        specifier: String,
    ): String {
        val parsed = parseFormatSpecifier(specifier)
        if (parsed.flags.any { flag -> flag != '-' } || parsed.precision != null) {
            throw LuaRuntimeException("invalid option '$specifier' to 'string.format'")
        }
        val pointer = when (context.typeName(index)) {
            "nil",
            "boolean",
            "number",
            -> "(null)"
            "table" -> context.getTable(index)?.pointerString() ?: "(null)"
            "function",
            "string",
            "userdata",
            -> context.get(index)?.pointerString() ?: "(null)"
            else -> context.get(index)?.pointerString() ?: "(null)"
        }
        return applyFormatWidth(pointer, parsed)
    }

    private fun Any.pointerString(): String {
        return "0x" + Integer.toUnsignedString(System.identityHashCode(this), 16)
    }

    private fun applyFormatWidth(
        value: String,
        parsed: FormatSpecifier,
    ): String {
        val width = parsed.width ?: return value
        if (value.length >= width) {
            return value
        }
        return if ('-' in parsed.flags) {
            value.padEnd(width, ' ')
        } else {
            value.padStart(width, ' ')
        }
    }

    private fun validateCharacterFormatSpecifier(specifier: String) {
        val parsed = parseFormatSpecifier(specifier)
        if (parsed.flags.any { flag -> flag != '-' } || parsed.precision != null) {
            throw LuaRuntimeException("invalid option '$specifier' to 'string.format'")
        }
    }

    private fun validateStringFormatSpecifier(specifier: String) {
        val parsed = parseFormatSpecifier(specifier)
        if (parsed.flags.any { flag -> flag != '-' }) {
            throw LuaRuntimeException("invalid option '$specifier' to 'string.format'")
        }
    }

    private fun formatIntegerValue(
        context: LuaCallContext,
        index: Int,
        specifier: String,
        conversion: Char,
    ): String {
        val value = requiredInteger(context, index, "string.format")
        val parsed = parseFormatSpecifier(specifier)
        validateIntegerFormatFlags(specifier, conversion, parsed)
        if (parsed.precision == null) {
            val formatValue = if (conversion == 'u') unsignedIntegerValue(value) else value
            return specifier.javaIntegerSpecifier(conversion).formatWith(formatValue)
        }

        val unsigned = conversion == 'o' || conversion == 'u' || conversion == 'x' || conversion == 'X'
        val radix = when (conversion) {
            'o' -> 8
            'x',
            'X',
            -> 16
            else -> 10
        }
        val absolute = if (unsigned) {
            unsignedIntegerValue(value)
        } else {
            BigInteger.valueOf(value).abs()
        }
        var digits = if (parsed.precision == 0 && absolute == BigInteger.ZERO) {
            ""
        } else {
            absolute.toString(radix)
        }
        if (conversion == 'X') {
            digits = digits.uppercase(Locale.ROOT)
        }
        if (digits.length < parsed.precision) {
            digits = digits.padStart(parsed.precision, '0')
        }

        val sign = when {
            unsigned -> ""
            value < 0L -> "-"
            '+' in parsed.flags -> "+"
            ' ' in parsed.flags -> " "
            else -> ""
        }
        val prefix = when {
            '#' !in parsed.flags -> ""
            conversion == 'o' && !digits.startsWith("0") -> "0"
            conversion == 'x' && absolute != BigInteger.ZERO -> "0x"
            conversion == 'X' && absolute != BigInteger.ZERO -> "0X"
            else -> ""
        }
        val formatted = sign + prefix + digits
        val width = parsed.width ?: return formatted
        if (formatted.length >= width) {
            return formatted
        }
        return if ('-' in parsed.flags) {
            formatted.padEnd(width, ' ')
        } else {
            formatted.padStart(width, ' ')
        }
    }

    private fun validateIntegerFormatFlags(
        specifier: String,
        conversion: Char,
        parsed: FormatSpecifier,
    ) {
        val allowedFlags = when (conversion) {
            'd',
            'i',
            -> "-+0 "
            'u' -> "-0"
            'o',
            'x',
            'X',
            -> "-#0"
            else -> ""
        }
        if (parsed.flags.any { flag -> flag !in allowedFlags }) {
            throw LuaRuntimeException("invalid option '$specifier' to 'string.format'")
        }
    }

    private fun parseFormatSpecifier(specifier: String): FormatSpecifier {
        var cursor = 1
        val flagsStart = cursor
        while (cursor < specifier.lastIndex && specifier[cursor] in FORMAT_FLAGS) {
            cursor++
        }
        val flags = specifier.substring(flagsStart, cursor)
        val widthStart = cursor
        while (cursor < specifier.lastIndex && specifier[cursor].isDigit()) {
            cursor++
        }
        val widthDigits = specifier.substring(widthStart, cursor)
        if (widthDigits.length > 2) {
            throw LuaRuntimeException("invalid option '$specifier' to 'string.format'")
        }
        val width = widthDigits.takeIf { it.isNotEmpty() }?.toInt()
        val precision = if (cursor < specifier.lastIndex && specifier[cursor] == '.') {
            cursor++
            val precisionStart = cursor
            while (cursor < specifier.lastIndex && specifier[cursor].isDigit()) {
                cursor++
            }
            val precisionDigits = specifier.substring(precisionStart, cursor)
            if (precisionDigits.length > 2) {
                throw LuaRuntimeException("invalid option '$specifier' to 'string.format'")
            }
            precisionDigits.takeIf { it.isNotEmpty() }?.toInt() ?: 0
        } else {
            null
        }
        return FormatSpecifier(flags, width, precision)
    }

    private fun unsignedIntegerValue(value: Long): BigInteger {
        val integer = BigInteger.valueOf(value)
        return if (value < 0L) integer + UINT64_MODULUS else integer
    }

    private data class FormatSpecifier(
        val flags: String,
        val width: Int?,
        val precision: Int?,
    )

    private fun quoteString(value: String): String {
        val result = StringBuilder("\"")
        for (char in value) {
            when (char) {
                '\\' -> result.append("\\\\")
                '"' -> result.append("\\\"")
                '\u0007' -> result.append("\\a")
                '\b' -> result.append("\\b")
                '\u000C' -> result.append("\\f")
                '\n' -> result.append("\\n")
                '\r' -> result.append("\\r")
                '\t' -> result.append("\\t")
                '\u000B' -> result.append("\\v")
                in '\u0000'..'\u001F',
                '\u007F',
                -> result.append("\\").append(char.code.toString().padStart(3, '0'))
                else -> result.append(char)
            }
        }
        return result.append('"').toString()
    }

    private fun quoteValue(context: LuaCallContext, index: Int): String {
        return when (context.typeName(index)) {
            "nil" -> "nil"
            "boolean" -> context.toBoolean(index).toString()
            "number" -> context.toString(index) ?: context.typeName(index)
            "string" -> quoteString(context.toString(index) ?: "")
            else -> throw LuaRuntimeException("bad argument #$index to 'string.format' (value has no literal form)")
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

    private fun setFunctionField(state: LuaState, name: String, function: (LuaCallContext) -> LuaReturn) {
        state.pushFunction(function::invoke)
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
            index > 0L -> index
            index == 0L -> 1L
            index < -length.toLong() -> 1L
            else -> length + index + 1L
        }
        return normalized.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }
}
