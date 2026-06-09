package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaFunction
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.util.Locale

internal object LuaStringLibrary {
    private const val FORMAT_CONVERSIONS = "diouxXfFeEgGcaApqs"
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
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
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
        val range = bytes.luaIndexRange(start, end)
        if (range.isEmpty()) {
            return LuaReturn.none()
        }
        return LuaReturn.ofValues(range.map { index -> (bytes[index - 1].toInt() and 0xff).toLong() })
    }

    private fun stringChar(context: LuaCallContext): LuaReturn {
        val bytes = ByteArray(context.argumentCount)
        for (index in 1..context.argumentCount) {
            val code = requiredInteger(context, index, "string.char")
            if (code !in 0L..255L) {
                throw LuaRuntimeException("bad argument #$index to 'string.char' (value out of range)")
            }
            bytes[index - 1] = code.toByte()
        }
        return LuaReturn.of(String(bytes, StandardCharsets.UTF_8))
    }

    private fun stringLen(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(requiredString(context, 1, "string.len").luaByteLength())
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
                replacementForMatch(context, replacementType, wholeMatch, text.luaByteCaptures(match.captures)),
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
            if (compiledPattern.startAnchored) {
                break
            }
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
        captures: List<Any?>,
    ): String {
        return when (replacementType) {
            "number" -> expandReplacement(requiredString(context, 3, "string.gsub"), wholeMatch, captures)
            "string" -> expandReplacement(requiredString(context, 3, "string.gsub"), wholeMatch, captures)
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

    private fun replacementArguments(wholeMatch: String, captures: List<Any?>): List<Any?> {
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
        captures: List<Any?>,
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
                    result.append(replacementCapture(captures, captureIndex, wholeMatch))
                }
                else -> throw LuaRuntimeException("invalid use of '%' in replacement string")
            }
            index += 2
        }
        return result.toString()
    }

    private fun replacementCapture(captures: List<Any?>, captureIndex: Int, wholeMatch: String): Any? {
        if (captures.isEmpty() && captureIndex == 1) {
            return wholeMatch
        }
        return captures.getOrNull(captureIndex - 1)
            ?: throw LuaRuntimeException("invalid capture index %$captureIndex")
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
        val startIndex = text.luaByteSearchStartToCharIndex(start)
        val match = if (plain) {
            LuaStringPattern.literal(pattern).find(text, startIndex)
        } else {
            LuaStringPattern.compile(pattern).find(text, startIndex)
        }
        if (match == null) {
            return LuaReturn.of(null)
        }
        return LuaReturn.ofValues(
            listOf(text.luaBytePosition(match.startIndex), text.luaByteEndPosition(match.endIndex)) +
                text.luaByteCaptures(match.captures),
        )
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
        var cursor = text.luaByteSearchStartToCharIndex(start)
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
                        LuaReturn.ofValues(text.luaByteCaptures(match.captures))
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
        return LuaReturn.of(requiredString(context, 1, "string.lower").mapAsciiCase(::lowerAscii))
    }

    private fun stringMatch(context: LuaCallContext): LuaReturn {
        val text = requiredString(context, 1, "string.match")
        val pattern = requiredString(context, 2, "string.match")
        val start = if (context.isNone(3) || context.isNil(3)) {
            1L
        } else {
            requiredInteger(context, 3, "string.match")
        }
        val startIndex = text.luaByteSearchStartToCharIndex(start)
        val match = LuaStringPattern.compile(pattern).find(text, startIndex)
        if (match == null) {
            return LuaReturn.of(null)
        }
        if (match.captures.isNotEmpty()) {
            return LuaReturn.ofValues(text.luaByteCaptures(match.captures))
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
        val resultLength = stringRepResultLength(text, separator, count)
        if (resultLength == 0) {
            return LuaReturn.of("")
        }
        return LuaReturn.of(
            buildString {
                ensureCapacity(resultLength)
                repeat(count.toInt()) { index ->
                    if (index > 0) {
                        append(separator)
                    }
                    append(text)
                }
            },
        )
    }

    private fun stringRepResultLength(text: String, separator: String, count: Long): Int {
        val copyLength = text.length.toLong() + separator.length.toLong()
        val repeatedLength = try {
            java.lang.Math.multiplyExact(copyLength, count)
        } catch (_: ArithmeticException) {
            throw LuaRuntimeException("resulting string too large")
        }
        val resultLength = repeatedLength - separator.length.toLong()
        if (resultLength > Int.MAX_VALUE) {
            throw LuaRuntimeException("resulting string too large")
        }
        return resultLength.toInt()
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
        return LuaReturn.of(text.substringByLuaByteRange(start, end))
    }

    private fun stringUpper(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(requiredString(context, 1, "string.upper").mapAsciiCase(::upperAscii))
    }

    private fun String.mapAsciiCase(convert: (Char) -> Char): String {
        return buildString(length) {
            for (char in this@mapAsciiCase) {
                append(convert(char))
            }
        }
    }

    private fun lowerAscii(char: Char): Char {
        return if (char in 'A'..'Z') char + ('a' - 'A') else char
    }

    private fun upperAscii(char: Char): Char {
        return if (char in 'a'..'z') char - ('a' - 'A') else char
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
            'a',
            'A',
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
            val javaSpecifier = if (value == 0L && '#' in parsed.flags && conversion in "oxX") {
                specifier.withoutAlternateFormatFlag()
            } else {
                specifier
            }
            return javaSpecifier.javaIntegerSpecifier(conversion).formatWith(formatValue)
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

    private fun String.withoutAlternateFormatFlag(): String {
        val hashIndex = indexOf('#')
        return if (hashIndex >= 0) removeRange(hashIndex, hashIndex + 1) else this
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
        if (cursor != specifier.lastIndex) {
            throw LuaRuntimeException("invalid option '$specifier' to 'string.format'")
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
        for ((index, char) in value.withIndex()) {
            when (char) {
                '\\' -> result.append("\\\\")
                '"' -> result.append("\\\"")
                '\n' -> result.append("\\\n")
                in '\u0000'..'\u001F',
                '\u007F',
                -> {
                    val nextIsDigit = value.getOrNull(index + 1)?.isDigit() == true
                    val escaped = char.code.toString().let { digits ->
                        if (nextIsDigit) digits.padStart(3, '0') else digits
                    }
                    result.append("\\").append(escaped)
                }
                else -> result.append(char)
            }
        }
        return result.append('"').toString()
    }

    private fun quoteValue(context: LuaCallContext, index: Int): String {
        return when (context.typeName(index)) {
            "nil" -> "nil"
            "boolean" -> context.toBoolean(index).toString()
            "number" -> quoteNumber(context, index)
            "string" -> quoteString(context.toString(index) ?: "")
            else -> throw LuaRuntimeException("bad argument #$index to 'string.format' (value has no literal form)")
        }
    }

    private fun quoteNumber(context: LuaCallContext, index: Int): String {
        return when (val value = context.get(index)) {
            is Byte -> value.toLong().toString()
            is Short -> value.toLong().toString()
            is Int -> value.toLong().toString()
            is Long -> if (value == Long.MIN_VALUE) "0x8000000000000000" else value.toString()
            is Float -> quoteFloat(value.toDouble())
            is Double -> quoteFloat(value)
            else -> context.toString(index) ?: context.typeName(index)
        }
    }

    private fun quoteFloat(value: Double): String {
        if (value == Double.POSITIVE_INFINITY) {
            return "1e9999"
        }
        if (value == Double.NEGATIVE_INFINITY) {
            return "-1e9999"
        }
        if (value.isNaN()) {
            return "(0/0)"
        }
        val hex = java.lang.Double.toHexString(value).replace(".0p", "p")
        val exponent = hex.lastIndexOf('p')
        val sign = hex.getOrNull(exponent + 1)
        return if (exponent >= 0 && sign != '+' && sign != '-') {
            hex.substring(0, exponent + 1) + "+" + hex.substring(exponent + 1)
        } else {
            hex
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
            "function" -> context.get(index)?.typedPointerString("function") ?: "function"
            "table" -> tableToLuaString(context, index)
            else -> context.typeName(index)
        }
    }

    private fun tableToLuaString(context: LuaCallContext, index: Int): String {
        val metamethod = context.getTableField(context.getMetatable(index), "__tostring")
            ?: return context.getTable(index)?.typedPointerString("table") ?: context.typeName(index)
        return when (val result = context.call(metamethod, listOf(context.getTable(index))).get(1)) {
            is Byte -> result.toLong().toString()
            is Short -> result.toLong().toString()
            is Int -> result.toLong().toString()
            is Long -> result.toString()
            is Float -> result.toDouble().toString()
            is Double -> result.toString()
            is CharSequence -> result.toString()
            else -> throw LuaRuntimeException("'__tostring' must return a string")
        }
    }

    private fun Any.typedPointerString(typeName: String): String {
        return "$typeName: ${System.identityHashCode(this).toString(16)}"
    }

    private fun String.luaByteLength(): Long {
        return toByteArray(StandardCharsets.UTF_8).size.toLong()
    }

    private fun ByteArray.luaIndexRange(start: Long, end: Long): IntRange {
        val normalizedStart = normalizeByteIndex(start)
        val normalizedEnd = normalizeByteIndex(end)
        if (normalizedStart > normalizedEnd) {
            return IntRange.EMPTY
        }
        val first = normalizedStart.coerceIn(1, size + 1)
        val last = normalizedEnd.coerceIn(0, size)
        if (first > last) {
            return IntRange.EMPTY
        }
        return first..last
    }

    private fun ByteArray.normalizeByteIndex(index: Long): Int {
        return when {
            index > Int.MAX_VALUE -> Int.MAX_VALUE
            index < Int.MIN_VALUE -> Int.MIN_VALUE
            index >= 0L -> index.toInt()
            else -> (size + index + 1L).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        }
    }

    private fun setFunctionField(state: LuaState, name: String, function: (LuaCallContext) -> LuaReturn) {
        state.pushFunction(function::invoke)
        state.setField(-2, name)
    }

    private fun String.substringByLuaByteRange(start: Long, end: Long): String {
        val bytes = toByteArray(StandardCharsets.UTF_8)
        val range = bytes.luaIndexRange(start, end)
        if (range.isEmpty()) {
            return ""
        }
        return String(bytes.copyOfRange(range.first - 1, range.last), StandardCharsets.UTF_8)
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

    private fun String.luaByteSearchStartToCharIndex(index: Long): Int {
        val byteLength = luaByteLength()
        val normalized = when {
            index > 0L -> index
            index == 0L -> 1L
            index < -byteLength -> 1L
            else -> byteLength + index + 1L
        }
        if (normalized > byteLength + 1L) {
            return length + 1
        }
        if (normalized == byteLength + 1L) {
            return length
        }

        var bytePosition = 1L
        for (charIndex in indices) {
            val charBytes = this[charIndex].toString().toByteArray(StandardCharsets.UTF_8).size.toLong()
            if (bytePosition >= normalized) {
                return charIndex
            }
            bytePosition += charBytes
        }
        return length
    }

    private fun String.luaBytePosition(charIndex: Int): Long {
        return substring(0, charIndex).luaByteLength() + 1L
    }

    private fun String.luaByteEndPosition(charIndex: Int): Long {
        return substring(0, charIndex).luaByteLength()
    }

    private fun String.luaByteCaptures(captures: List<Any?>): List<Any?> {
        return captures.map { capture ->
            if (capture is Long) {
                luaBytePosition((capture - 1L).toInt())
            } else {
                capture
            }
        }
    }
}
