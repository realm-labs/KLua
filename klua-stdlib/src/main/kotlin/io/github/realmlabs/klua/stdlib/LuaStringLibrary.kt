package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaFunction
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState
import io.github.realmlabs.klua.core.value.luaRawBytes
import io.github.realmlabs.klua.core.value.toLuaByteString
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale

internal object LuaStringLibrary {
    private const val FORMAT_CONVERSIONS = "diouxXfFeEgGcaApqs"
    private const val FORMAT_FLAGS = "-+ #0"
    private const val FORMAT_SPECIFIER_PREFIX = "$FORMAT_FLAGS.123456789"
    private const val MAX_FORMAT_SPECIFIER_LENGTH = 22
    private const val MAX_PACK_RESULT_SIZE = 2147483647L
    private val GSUB_REPLACEMENT_TYPES = setOf("number", "string", "function", "table")
    private const val GSUB_REPLACEMENT_EXPECTED_TYPE = "string/function/table"
    private val UINT64_MODULUS = BigInteger.ONE.shiftLeft(Long.SIZE_BITS)

    fun open(state: LuaState): LuaState {
        state.newTable()
        setFunctionField(state, "byte", ::stringByte)
        setFunctionField(state, "char", ::stringChar)
        setFunctionField(state, "dump", ::stringDump)
        setFunctionField(state, "find", ::stringFind)
        setFunctionField(state, "format", ::stringFormat)
        setFunctionField(state, "gmatch", ::stringGmatch)
        setFunctionField(state, "gsub", ::stringGsub)
        setFunctionField(state, "len", ::stringLen)
        setFunctionField(state, "lower", ::stringLower)
        setFunctionField(state, "match", ::stringMatch)
        setFunctionField(state, "pack", ::stringPack)
        setFunctionField(state, "packsize", ::stringPackSize)
        setFunctionField(state, "rep", ::stringRep)
        setFunctionField(state, "reverse", ::stringReverse)
        setFunctionField(state, "sub", ::stringSub)
        setFunctionField(state, "unpack", ::stringUnpack)
        setFunctionField(state, "upper", ::stringUpper)
        createStringMetatable(state)
        state.setGlobal("string")
        return state
    }

    private fun createStringMetatable(state: LuaState) {
        state.newTable()
        state.pushValue(-2)
        state.setField(-2, "__index")
        state.pushString("")
        state.pushValue(-2)
        state.setMetatable(-2)
        state.setTop(-3)
    }

    private fun stringByte(context: LuaCallContext): LuaReturn {
        val text = requiredString(context, 1, "byte")
        val bytes = text.luaRawBytes()
        val start = if (context.isNone(2) || context.isNil(2)) {
            1L
        } else {
            requiredInteger(context, 2, "byte")
        }
        val end = if (context.isNone(3) || context.isNil(3)) {
            start
        } else {
            requiredInteger(context, 3, "byte")
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
            val code = requiredInteger(context, index, "char")
            if (code !in 0L..255L) {
                throw LuaRuntimeException("bad argument #$index to 'char' (value out of range)")
            }
            bytes[index - 1] = code.toByte()
        }
        return LuaReturn.of(bytes.toLuaByteString())
    }

    private fun stringDump(context: LuaCallContext): LuaReturn {
        if (context.typeName(1) != "function") {
            throw LuaRuntimeException("bad argument #1 to 'dump' (Lua function expected)")
        }
        val bytes = context.dumpFunctionBytecode(1, strip = context.toBoolean(2))
            ?: throw LuaRuntimeException("bad argument #1 to 'dump' (Lua function expected)")
        return LuaReturn.of(bytes.toLuaByteString())
    }

    private fun stringLen(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(requiredString(context, 1, "len").luaByteLength())
    }

    private fun stringGsub(context: LuaCallContext): LuaReturn {
        val text = requiredString(context, 1, "gsub")
        val pattern = requiredString(context, 2, "gsub")
        val replacementType = context.typeName(3)
        if (replacementType !in GSUB_REPLACEMENT_TYPES) {
            throw LuaRuntimeException("bad argument #3 to 'gsub' ($GSUB_REPLACEMENT_EXPECTED_TYPE expected)")
        }
        val limit = if (context.isNone(4) || context.isNil(4)) {
            Long.MAX_VALUE
        } else {
            requiredInteger(context, 4, "gsub")
        }
        if (limit <= 0L) {
            return LuaReturn.of(text, 0L)
        }

        val subject = text.toLuaPatternSubject()
        val patternSubject = pattern.toLuaPatternSubject()
        val result = StringBuilder()
        var cursor = 0
        var replacements = 0L
        val compiledPattern = LuaStringPattern.compile(patternSubject.text)
        while (replacements < limit) {
            val match = compiledPattern.find(subject.text, cursor)
            if (match == null) {
                break
            }
            result.append(subject.text, cursor, match.startIndex)
            val wholeMatch = subject.substring(match.startIndex, match.endIndex)
            result.append(
                replacementForMatch(
                    context,
                    replacementType,
                    wholeMatch,
                    subject.luaPatternCaptures(match.captures),
                ).toLuaPatternReplacement(),
            )
            cursor = if (match.startIndex == match.endIndex && match.endIndex < subject.text.length) {
                val nextCursor = match.endIndex + 1
                result.append(subject.text, match.endIndex, nextCursor)
                nextCursor
            } else if (match.startIndex == match.endIndex) {
                subject.text.length + 1
            } else {
                match.endIndex
            }
            replacements++
            if (compiledPattern.startAnchored) {
                break
            }
        }
        if (cursor <= subject.text.length) {
            result.append(subject.text, cursor, subject.text.length)
        }
        return LuaReturn.of(result.toString().toLuaByteStringFromOneBytePerChar(), replacements)
    }

    private fun replacementForMatch(
        context: LuaCallContext,
        replacementType: String,
        wholeMatch: String,
        captures: List<Any?>,
    ): String {
        return when (replacementType) {
            "number" -> expandReplacement(luaNumberToString(context.get(3)), wholeMatch, captures)
            "string" -> expandReplacement(requiredString(context, 3, "gsub"), wholeMatch, captures)
            "function" -> {
                val result = context.call(3, replacementArguments(wholeMatch, captures)).get(1)
                replacementValueToString(context, result, wholeMatch)
            }
            "table" -> {
                val result = gsubTableReplacementValue(
                    context,
                    context.getTable(3),
                    replacementArguments(wholeMatch, captures).first(),
                )
                replacementValueToString(context, result, wholeMatch)
            }
            else -> throw LuaRuntimeException("bad argument #3 to 'gsub' ($GSUB_REPLACEMENT_EXPECTED_TYPE expected)")
        }
    }

    private fun replacementArguments(wholeMatch: String, captures: List<Any?>): List<Any?> {
        return captures.ifEmpty { listOf(wholeMatch) }
    }

    private fun replacementValueToString(context: LuaCallContext, value: Any?, wholeMatch: String): String {
        return when (value) {
            null -> wholeMatch
            false -> wholeMatch
            is Byte,
            is Short,
            is Int,
            is Long,
            is Float,
            is Double,
            -> luaNumberToString(value)
            is CharSequence -> value.toString()
            else -> {
                val typeName = gsubReplacementTypeName(context, value)
                throw LuaRuntimeException("invalid replacement value (a $typeName)")
            }
        }
    }

    private fun gsubReplacementTypeName(context: LuaCallContext, value: Any?): String {
        return context.valueTypeName(value)
    }

    private fun gsubTableReplacementValue(context: LuaCallContext, table: Any?, key: Any?): Any? {
        return gsubTableReplacementValue(context, table, key, identitySet())
    }

    private fun gsubTableReplacementValue(
        context: LuaCallContext,
        table: Any?,
        key: Any?,
        visited: MutableSet<Any>,
    ): Any? {
        if (table != null && !visited.add(table)) {
            throw LuaRuntimeException("'__index' chain too long; possible loop")
        }
        val rawValue = context.getTableField(table, key)
        if (rawValue != null) {
            return rawValue
        }
        val index = context.getTableField(context.getTableMetatable(table), "__index") ?: return null
        return if (context.isFunctionValue(index)) {
            context.call(index, listOf(table, key)).get(1)
        } else if (context.isTableValue(index)) {
            gsubTableReplacementValue(context, index, key, visited)
        } else if (index is CharSequence) {
            null
        } else {
            throw LuaRuntimeException("attempt to index a ${context.valueTypeName(index)} value")
        }
    }

    private fun identitySet(): MutableSet<Any> {
        return java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
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
                    result.append(replacementCapture(captures, captureIndex))
                }
                else -> throw LuaRuntimeException("invalid use of '%' in replacement string")
            }
            index += 2
        }
        return result.toString()
    }

    private fun replacementCapture(captures: List<Any?>, captureIndex: Int): Any? {
        return captures.getOrNull(captureIndex - 1)
            ?: throw LuaRuntimeException("invalid capture index %$captureIndex")
    }

    private fun stringFind(context: LuaCallContext): LuaReturn {
        val text = requiredString(context, 1, "find")
        val pattern = requiredString(context, 2, "find")
        val start = if (context.isNone(3) || context.isNil(3)) {
            1L
        } else {
            requiredInteger(context, 3, "find")
        }
        val plain = context.toBoolean(4)
        val searchStart = text.luaByteSearchStart(start)
        val subject = text.toLuaPatternSubject()
        val patternSubject = pattern.toLuaPatternSubject()
        val startIndex = (searchStart.bytePosition - 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (startIndex > subject.text.length) {
            return LuaReturn.of(null)
        }
        val match = if (plain) {
            LuaStringPattern.literal(patternSubject.text).find(subject.text, startIndex)
        } else {
            LuaStringPattern.compile(patternSubject.text).find(subject.text, startIndex)
        }
        if (match == null) {
            return LuaReturn.of(null)
        }
        return LuaReturn.ofValues(
            listOf(
                match.startIndex + 1L,
                match.endIndex.toLong(),
            ) + subject.luaPatternCaptures(match.captures),
        )
    }

    private fun stringGmatch(context: LuaCallContext): LuaReturn {
        val text = requiredString(context, 1, "gmatch")
        val pattern = requiredString(context, 2, "gmatch")
        val start = if (context.isNone(3) || context.isNil(3)) {
            1L
        } else {
            requiredInteger(context, 3, "gmatch")
        }
        val searchStart = text.luaByteSearchStart(start)
        val subject = text.toLuaPatternSubject()
        val patternSubject = pattern.toLuaPatternSubject()
        if (searchStart.bytePosition - 1L > subject.text.length) {
            val emptyIterator = LuaFunction { LuaReturn.of(null) }
            return LuaReturn.of(emptyIterator)
        }
        val compiledPattern = LuaStringPattern.compileGmatch(patternSubject.text)
        var cursor = (searchStart.bytePosition - 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val iterator = LuaFunction { _ ->
            if (cursor > subject.text.length) {
                LuaReturn.of(null)
            } else {
                val match = compiledPattern.find(subject.text, cursor)
                if (match == null) {
                    LuaReturn.of(null)
                } else {
                    cursor = if (match.startIndex == match.endIndex && match.endIndex < subject.text.length) {
                        match.endIndex + 1
                    } else if (match.startIndex == match.endIndex) {
                        subject.text.length + 1
                    } else {
                        match.endIndex
                    }
                    if (match.captures.isNotEmpty()) {
                        LuaReturn.ofValues(subject.luaPatternCaptures(match.captures))
                    } else {
                        LuaReturn.of(subject.substring(match.startIndex, match.endIndex))
                    }
                }
            }
        }
        return LuaReturn.of(iterator)
    }

    private fun stringFormat(context: LuaCallContext): LuaReturn {
        val format = requiredString(context, 1, "format")
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

            if (cursor + 1 < format.length && format[cursor + 1] == '%') {
                result.append('%')
                cursor += 2
                continue
            }

            val specStart = cursor
            cursor++
            while (cursor < format.length && format[cursor] in FORMAT_SPECIFIER_PREFIX) {
                cursor++
            }
            val specifier = if (cursor < format.length) {
                format.substring(specStart, cursor + 1)
            } else {
                format.substring(specStart)
            }
            if (argument > context.argumentCount) {
                throw LuaRuntimeException("bad argument #$argument to 'format' (no value)")
            }
            if (specifier.length > MAX_FORMAT_SPECIFIER_LENGTH) {
                throw LuaRuntimeException("invalid format (too long)")
            }
            if (cursor >= format.length) {
                throw invalidFormatConversion(specifier)
            }
            val conversion = format[cursor]
            result.append(formatValue(context, argument, specifier, conversion))
            argument++
            cursor++
        }
        return LuaReturn.of(result.toString())
    }

    private fun stringLower(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(requiredString(context, 1, "lower").mapAsciiCase(::lowerAscii))
    }

    private fun stringMatch(context: LuaCallContext): LuaReturn {
        val text = requiredString(context, 1, "match")
        val pattern = requiredString(context, 2, "match")
        val start = if (context.isNone(3) || context.isNil(3)) {
            1L
        } else {
            requiredInteger(context, 3, "match")
        }
        val searchStart = text.luaByteSearchStart(start)
        val subject = text.toLuaPatternSubject()
        val patternSubject = pattern.toLuaPatternSubject()
        val startIndex = (searchStart.bytePosition - 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (startIndex > subject.text.length) {
            return LuaReturn.of(null)
        }
        val match = LuaStringPattern.compile(patternSubject.text).find(subject.text, startIndex)
        if (match == null) {
            return LuaReturn.of(null)
        }
        if (match.captures.isNotEmpty()) {
            return LuaReturn.ofValues(subject.luaPatternCaptures(match.captures))
        }
        return LuaReturn.of(subject.substring(match.startIndex, match.endIndex))
    }

    private fun stringPackSize(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(LuaStringPackFormat.packSize(requiredString(context, 1, "packsize")))
    }

    private fun stringPack(context: LuaCallContext): LuaReturn {
        val scanner = LuaStringPackFormat.PackFormatScanner(requiredString(context, 1, "pack"), "pack")
        val output = ByteArrayOutputStream()
        var totalSize = 0L
        var argumentIndex = 2
        while (!scanner.isDone()) {
            val details = scanner.nextDetails(totalSize)
            val resultLengthArgumentIndex = argumentIndex - 1
            val fixedSize = checkedPackResultSize(
                details.padding,
                details.size,
                resultLengthArgumentIndex,
            )
            totalSize = checkedPackResultSize(totalSize, fixedSize, resultLengthArgumentIndex)
            repeatByte(output, 0, details.padding)
            when (details.option) {
                LuaStringPackFormat.PackOption.Int -> {
                    val value = requiredInteger(context, argumentIndex, "pack")
                    requireSignedPackRange(value, details.size, argumentIndex)
                    writePackedInteger(output, value, details.size, details.littleEndian, signed = true)
                    argumentIndex++
                }
                LuaStringPackFormat.PackOption.UInt -> {
                    val value = requiredInteger(context, argumentIndex, "pack")
                    requireUnsignedPackRange(value, details.size, argumentIndex)
                    writePackedInteger(output, value, details.size, details.littleEndian, signed = false)
                    argumentIndex++
                }
                LuaStringPackFormat.PackOption.Char -> {
                    val value = requiredString(context, argumentIndex, "pack")
                    val bytes = value.luaRawBytes()
                    if (bytes.size.toLong() > details.size) {
                        throw LuaRuntimeException("bad argument #$argumentIndex to 'pack' (string longer than given size)")
                    }
                    output.write(bytes)
                    repeatByte(output, 0, details.size - bytes.size.toLong())
                    argumentIndex++
                }
                LuaStringPackFormat.PackOption.Padding,
                LuaStringPackFormat.PackOption.AlignPadding,
                LuaStringPackFormat.PackOption.NoOp,
                -> {
                    if (details.option == LuaStringPackFormat.PackOption.Padding) {
                        output.write(0)
                    }
                }
                LuaStringPackFormat.PackOption.Float -> {
                    val value = requiredNumber(context, argumentIndex, "pack")
                    writePackedFloat(output, value, details.littleEndian)
                    argumentIndex++
                }
                LuaStringPackFormat.PackOption.Number,
                LuaStringPackFormat.PackOption.Double,
                -> {
                    val value = requiredNumber(context, argumentIndex, "pack")
                    writePackedDouble(output, value, details.littleEndian)
                    argumentIndex++
                }
                LuaStringPackFormat.PackOption.String -> {
                    val value = requiredString(context, argumentIndex, "pack")
                    val bytes = value.luaRawBytes()
                    requireStringLengthPackRange(bytes.size, details.size, argumentIndex)
                    writePackedInteger(output, bytes.size.toLong(), details.size, details.littleEndian, signed = false)
                    totalSize = checkedPackResultSize(totalSize, bytes.size.toLong(), argumentIndex)
                    output.write(bytes)
                    argumentIndex++
                }
                LuaStringPackFormat.PackOption.ZeroTerminatedString -> {
                    val value = requiredString(context, argumentIndex, "pack")
                    val bytes = value.luaRawBytes()
                    if (bytes.any { it.toInt() == 0 }) {
                        throw LuaRuntimeException("bad argument #$argumentIndex to 'pack' (string contains zeros)")
                    }
                    totalSize = checkedPackResultSize(totalSize, bytes.size.toLong() + 1L, argumentIndex)
                    output.write(bytes)
                    output.write(0)
                    argumentIndex++
                }
            }
        }
        return LuaReturn.of(output.toByteArray().toLuaByteString())
    }

    private fun stringUnpack(context: LuaCallContext): LuaReturn {
        val scanner = LuaStringPackFormat.PackFormatScanner(requiredString(context, 1, "unpack"), "unpack")
        val data = requiredString(context, 2, "unpack").luaRawBytes()
        val initialPosition = if (context.isNone(3) || context.isNil(3)) {
            1L
        } else {
            requiredInteger(context, 3, "unpack")
        }
        var position = normalizeUnpackPosition(initialPosition, data.size)
        val results = mutableListOf<Any?>()
        while (!scanner.isDone()) {
            val details = scanner.nextDetails(position)
            val needed = checkedPackSize(details.padding, details.size)
            if (needed > data.size.toLong() - position) {
                throw LuaRuntimeException("bad argument #2 to 'unpack' (data string too short)")
            }
            position += details.padding
            when (details.option) {
                LuaStringPackFormat.PackOption.Int,
                LuaStringPackFormat.PackOption.UInt,
                -> results += unpackInteger(
                    data,
                    position.toInt(),
                    details.size,
                    details.littleEndian,
                    signed = details.option == LuaStringPackFormat.PackOption.Int,
                )
                LuaStringPackFormat.PackOption.Char -> {
                    val start = position.toInt()
                    val end = start + details.size.toInt()
                    results += data.copyOfRange(start, end).toLuaByteString()
                }
                LuaStringPackFormat.PackOption.Padding,
                LuaStringPackFormat.PackOption.AlignPadding,
                LuaStringPackFormat.PackOption.NoOp,
                -> Unit
                LuaStringPackFormat.PackOption.Float -> {
                    results += unpackFloat(data, position.toInt(), details.littleEndian)
                }
                LuaStringPackFormat.PackOption.Number,
                LuaStringPackFormat.PackOption.Double,
                -> {
                    results += unpackDouble(data, position.toInt(), details.littleEndian)
                }
                LuaStringPackFormat.PackOption.String -> {
                    val length = unpackInteger(
                        data,
                        position.toInt(),
                        details.size,
                        details.littleEndian,
                        signed = false,
                    )
                    val remaining = data.size.toLong() - position - details.size
                    if (length < 0L || length > remaining) {
                        throw LuaRuntimeException("bad argument #2 to 'unpack' (data string too short)")
                    }
                    val start = (position + details.size).toInt()
                    val end = start + length.toInt()
                    results += data.copyOfRange(start, end).toLuaByteString()
                    position += length
                }
                LuaStringPackFormat.PackOption.ZeroTerminatedString -> {
                    val zeroIndex = data.indexOfZero(position.toInt())
                    if (zeroIndex < 0) {
                        throw LuaRuntimeException("bad argument #2 to 'unpack' (unfinished string for format 'z')")
                    }
                    results += data.copyOfRange(position.toInt(), zeroIndex).toLuaByteString()
                    position += zeroIndex - position.toInt() + 1L
                }
            }
            position += details.size
        }
        results += position + 1L
        return LuaReturn.ofValues(results)
    }

    private fun checkedPackSize(left: Long, right: Long): Long {
        return try {
            java.lang.Math.addExact(left, right)
        } catch (_: ArithmeticException) {
            throw LuaRuntimeException("resulting string too large")
        }
    }

    private fun checkedPackResultSize(left: Long, right: Long, argumentIndex: Int): Long {
        val size = try {
            java.lang.Math.addExact(left, right)
        } catch (_: ArithmeticException) {
            throw LuaRuntimeException("bad argument #$argumentIndex to 'pack' (result too long)")
        }
        if (size > MAX_PACK_RESULT_SIZE) {
            throw LuaRuntimeException("bad argument #$argumentIndex to 'pack' (result too long)")
        }
        return size
    }

    private fun repeatByte(output: ByteArrayOutputStream, value: Int, count: Long) {
        repeat(count.toInt()) {
            output.write(value)
        }
    }

    private fun requireSignedPackRange(value: Long, size: Long, argumentIndex: Int) {
        if (size >= Long.SIZE_BYTES) {
            return
        }
        val bits = (size * Byte.SIZE_BITS).toInt()
        val limit = 1L shl (bits - 1)
        if (value !in -limit until limit) {
            throw LuaRuntimeException("bad argument #$argumentIndex to 'pack' (integer overflow)")
        }
    }

    private fun requireUnsignedPackRange(value: Long, size: Long, argumentIndex: Int) {
        if (size >= Long.SIZE_BYTES) {
            return
        }
        val bits = (size * Byte.SIZE_BITS).toInt()
        val limit = 1L shl bits
        if (value < 0L || value >= limit) {
            throw LuaRuntimeException("bad argument #$argumentIndex to 'pack' (unsigned overflow)")
        }
    }

    private fun requireStringLengthPackRange(length: Int, size: Long, argumentIndex: Int) {
        if (size >= Long.SIZE_BYTES) {
            return
        }
        val bits = (size * Byte.SIZE_BITS).toInt()
        val limit = 1L shl bits
        if (length.toLong() >= limit) {
            throw LuaRuntimeException(
                "bad argument #$argumentIndex to 'pack' (string length does not fit in given size)",
            )
        }
    }

    private fun writePackedInteger(
        output: ByteArrayOutputStream,
        value: Long,
        size: Long,
        littleEndian: Boolean,
        signed: Boolean,
    ) {
        val bytes = ByteArray(size.toInt())
        for (offset in bytes.indices) {
            val packed = if (offset < Long.SIZE_BYTES) {
                (value ushr (offset * Byte.SIZE_BITS)).toInt() and 0xff
            } else if (signed && value < 0L) {
                0xff
            } else {
                0
            }
            bytes[if (littleEndian) offset else bytes.lastIndex - offset] = packed.toByte()
        }
        output.write(bytes)
    }

    private fun writePackedFloat(output: ByteArrayOutputStream, value: Double, littleEndian: Boolean) {
        val bytes = ByteBuffer.allocate(Float.SIZE_BYTES)
            .order(byteOrder(littleEndian))
            .putFloat(value.toFloat())
            .array()
        output.write(bytes)
    }

    private fun writePackedDouble(output: ByteArrayOutputStream, value: Double, littleEndian: Boolean) {
        val bytes = ByteBuffer.allocate(Double.SIZE_BYTES)
            .order(byteOrder(littleEndian))
            .putDouble(value)
            .array()
        output.write(bytes)
    }

    private fun normalizeUnpackPosition(position: Long, length: Int): Long {
        val normalized = if (position >= 0L) {
            position
        } else {
            length.toLong() + position + 1L
        }
        if (normalized < 1L || normalized > length.toLong() + 1L) {
            throw LuaRuntimeException("bad argument #3 to 'unpack' (initial position out of string)")
        }
        return normalized - 1L
    }

    private fun unpackInteger(
        data: ByteArray,
        position: Int,
        size: Long,
        littleEndian: Boolean,
        signed: Boolean,
    ): Long {
        val sizeInt = size.toInt()
        val limit = minOf(sizeInt, Long.SIZE_BYTES)
        var result = 0L
        for (offset in limit - 1 downTo 0) {
            val sourceIndex = if (littleEndian) {
                position + offset
            } else {
                position + sizeInt - 1 - offset
            }
            result = (result shl Byte.SIZE_BITS) or (data[sourceIndex].toLong() and 0xffL)
        }
        if (sizeInt < Long.SIZE_BYTES && signed) {
            val bits = sizeInt * Byte.SIZE_BITS
            val mask = 1L shl (bits - 1)
            result = (result xor mask) - mask
        } else if (sizeInt > Long.SIZE_BYTES) {
            val expected = if (!signed || result >= 0L) 0 else 0xff
            for (offset in limit until sizeInt) {
                val sourceIndex = if (littleEndian) {
                    position + offset
                } else {
                    position + sizeInt - 1 - offset
                }
                if ((data[sourceIndex].toInt() and 0xff) != expected) {
                    throw LuaRuntimeException("$size-byte integer does not fit into Lua Integer")
                }
            }
        }
        return result
    }

    private fun ByteArray.indexOfZero(start: Int): Int {
        for (index in start until size) {
            if (this[index].toInt() == 0) {
                return index
            }
        }
        return -1
    }

    private fun unpackFloat(data: ByteArray, position: Int, littleEndian: Boolean): Double {
        return ByteBuffer.wrap(data, position, Float.SIZE_BYTES)
            .order(byteOrder(littleEndian))
            .float
            .toDouble()
    }

    private fun unpackDouble(data: ByteArray, position: Int, littleEndian: Boolean): Double {
        return ByteBuffer.wrap(data, position, Double.SIZE_BYTES)
            .order(byteOrder(littleEndian))
            .double
    }

    private fun byteOrder(littleEndian: Boolean): ByteOrder {
        return if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
    }

    private fun stringRep(context: LuaCallContext): LuaReturn {
        val text = requiredString(context, 1, "rep")
        val count = requiredInteger(context, 2, "rep")
        val separator = if (context.isNone(3) || context.isNil(3)) {
            ""
        } else {
            requiredString(context, 3, "rep")
        }
        if (count <= 0L) {
            return LuaReturn.of("")
        }
        val textBytes = text.luaRawBytes()
        val separatorBytes = separator.luaRawBytes()
        val resultLength = stringRepResultLength(textBytes.size.toLong(), separatorBytes.size.toLong(), count)
        if (resultLength == 0) {
            return LuaReturn.of("")
        }
        val output = ByteArrayOutputStream(resultLength)
        var index = 0L
        while (index < count) {
            if (index > 0L) {
                output.write(separatorBytes)
            }
            output.write(textBytes)
            index++
        }
        return LuaReturn.of(output.toByteArray().toLuaByteString())
    }

    private fun stringRepResultLength(textLength: Long, separatorLength: Long, count: Long): Int {
        val copyLength = textLength + separatorLength
        val repeatedLength = try {
            java.lang.Math.multiplyExact(copyLength, count)
        } catch (_: ArithmeticException) {
            throw LuaRuntimeException("resulting string too large")
        }
        val resultLength = repeatedLength - separatorLength
        if (resultLength > Int.MAX_VALUE) {
            throw LuaRuntimeException("resulting string too large")
        }
        return resultLength.toInt()
    }

    private fun stringReverse(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(requiredString(context, 1, "reverse").luaRawBytes().reversedArray().toLuaByteString())
    }

    private fun stringSub(context: LuaCallContext): LuaReturn {
        val text = requiredString(context, 1, "sub")
        val start = requiredInteger(context, 2, "sub")
        val end = if (context.isNone(3) || context.isNil(3)) {
            -1L
        } else {
            requiredInteger(context, 3, "sub")
        }
        return LuaReturn.of(text.substringByLuaByteRange(start, end))
    }

    private fun stringUpper(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(requiredString(context, 1, "upper").mapAsciiCase(::upperAscii))
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
            ?: if (context.toNumber(index) != null || context.typeName(index) == "number") {
                throw LuaRuntimeException("bad argument #$index to '$functionName' (number has no integer representation)")
            } else {
                throw LuaRuntimeException("bad argument #$index to '$functionName' (number expected)")
            }
    }

    private fun requiredFormatInteger(context: LuaCallContext, index: Int): Long {
        return context.toInteger(index)
            ?: if (context.toNumber(index) != null || context.typeName(index) == "number") {
                throw LuaRuntimeException("bad argument #$index to 'format' (number has no integer representation)")
            } else {
                throw LuaRuntimeException("bad argument #$index to 'format' (number expected)")
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

    private fun formatValue(
        context: LuaCallContext,
        index: Int,
        specifier: String,
        conversion: Char,
    ): String {
        return when (conversion) {
            's' -> {
                formatStringValue(context, index, specifier)
            }
            'd',
            'i',
            'o',
            'x',
            'X',
            -> formatIntegerValue(context, index, specifier, conversion)
            'u' -> formatIntegerValue(context, index, specifier, conversion)
            'a',
            'A',
            -> formatHexFloatValue(context, index, specifier, conversion)
            'f',
            'e',
            'E',
            'g',
            'G',
            -> formatDecimalFloatValue(context, index, specifier, conversion)
            'c' -> {
                val parsed = validateCharacterFormatSpecifier(specifier)
                val code = requiredFormatInteger(context, index)
                formatCharacterByte(code, parsed)
            }
            'p' -> formatPointerValue(context, index, specifier)
            'q' -> {
                if (specifier != "%q") {
                    throw LuaRuntimeException("specifier '%q' cannot have modifiers")
                }
                quoteValue(context, index)
            }
            else -> throw invalidFormatConversion(specifier)
        }
    }

    private fun invalidFormatConversion(specifier: String): LuaRuntimeException {
        return LuaRuntimeException("invalid conversion '$specifier' to 'format'")
    }

    private fun String.formatWith(value: Any): String {
        return java.lang.String.format(Locale.ROOT, this, value)
    }

    private fun validateFormatSize(specifier: String): FormatSpecifier {
        return parseFormatSpecifier(specifier)
    }

    private fun formatPointerValue(
        context: LuaCallContext,
        index: Int,
        specifier: String,
    ): String {
        val parsed = parseFormatSpecifier(specifier)
        if (parsed.flags.any { flag -> flag != '-' } || parsed.precision != null) {
            throw invalidConversionSpecification(specifier)
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

    private fun validateCharacterFormatSpecifier(specifier: String): FormatSpecifier {
        val parsed = parseFormatSpecifier(specifier)
        if (parsed.flags.any { flag -> flag != '-' } || parsed.precision != null) {
            throw invalidConversionSpecification(specifier)
        }
        return parsed
    }

    private fun formatCharacterByte(code: Long, parsed: FormatSpecifier): String {
        val value = byteArrayOf(code.toByte()).toLuaByteString()
        val width = parsed.width ?: return value
        if (width <= 1) {
            return value
        }
        val padding = " ".repeat(width - 1)
        return if ('-' in parsed.flags) value + padding else padding + value
    }

    private fun validateStringFormatSpecifier(specifier: String): FormatSpecifier {
        val parsed = parseFormatSpecifier(specifier)
        if (parsed.flags.any { flag -> flag != '-' }) {
            throw invalidConversionSpecification(specifier)
        }
        return parsed
    }

    private fun formatStringValue(
        context: LuaCallContext,
        index: Int,
        specifier: String,
    ): String {
        val parsed = validateStringFormatSpecifier(specifier)
        val value = toLuaString(context, index)
        if (specifier == "%s") {
            return value
        }
        val bytes = value.luaRawBytes()
        if (bytes.any { byte -> byte == 0.toByte() }) {
            throw LuaRuntimeException("bad argument #$index to 'format' (string contains zeros)")
        }
        return formatStringBytes(bytes, parsed)
    }

    private fun formatStringBytes(bytes: ByteArray, parsed: FormatSpecifier): String {
        val selected = parsed.precision
            ?.let { precision -> bytes.copyOfRange(0, precision.coerceAtMost(bytes.size)) }
            ?: bytes
        val value = selected.toLuaByteString()
        val width = parsed.width ?: return value
        val paddingSize = width - selected.size
        if (paddingSize <= 0) {
            return value
        }
        val padding = " ".repeat(paddingSize)
        return if ('-' in parsed.flags) value + padding else padding + value
    }

    private fun formatDecimalFloatValue(
        context: LuaCallContext,
        index: Int,
        specifier: String,
        conversion: Char,
    ): String {
        val parsed = validateFormatSize(specifier)
        val value = requiredNumber(context, index, "string.format")
        if (!value.isFinite()) {
            return formatNonFiniteDecimalFloat(value, parsed, conversion)
        }
        return parsed.toJavaSpecifier(conversion).formatWith(value)
    }

    private fun formatIntegerValue(
        context: LuaCallContext,
        index: Int,
        specifier: String,
        conversion: Char,
    ): String {
        val value = requiredFormatInteger(context, index)
        val parsed = parseFormatSpecifier(specifier)
        validateIntegerFormatFlags(specifier, conversion, parsed)
        if (parsed.precision == null) {
            val formatValue = if (conversion == 'u') unsignedIntegerValue(value) else value
            val javaConversion = if (conversion == 'i' || conversion == 'u') 'd' else conversion
            val javaFlags = if (value == 0L && '#' in parsed.flags && conversion in "oxX") {
                parsed.flags.replace("#", "")
            } else {
                parsed.flags
            }
            return parsed.copy(flags = javaFlags).toJavaSpecifier(javaConversion).formatWith(formatValue)
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

    private fun formatHexFloatValue(
        context: LuaCallContext,
        index: Int,
        specifier: String,
        conversion: Char,
    ): String {
        val parsed = validateFormatSize(specifier)
        val value = requiredNumber(context, index, "string.format")
        if (!value.isFinite()) {
            return formatNonFiniteHexFloat(value, parsed, conversion)
        }
        val formatted = parsed.copy(width = null).toJavaSpecifier(conversion).formatWith(value)
        return applyFloatWidth(canonicalizeHexFloat(formatted, parsed), parsed)
    }

    private fun canonicalizeHexFloat(value: String, parsed: FormatSpecifier): String {
        val exponent = value.indexOfLast { char -> char == 'p' || char == 'P' }
        if (exponent < 0) {
            return value
        }
        val rawMantissa = value.substring(0, exponent)
        val dot = rawMantissa.indexOf('.')
        val mantissa = when {
            parsed.precision == 0 && dot >= 0 && '#' in parsed.flags -> rawMantissa.substring(0, dot + 1)
            parsed.precision == 0 && dot >= 0 -> rawMantissa.substring(0, dot)
            parsed.precision == null && '#' !in parsed.flags -> rawMantissa.removeSuffix(".0")
            else -> rawMantissa
        }
        return signedHexFloatExponent(mantissa + value.substring(exponent))
    }

    private fun applyFloatWidth(value: String, parsed: FormatSpecifier): String {
        val width = parsed.width ?: return value
        if (value.length >= width) {
            return value
        }
        if ('-' in parsed.flags) {
            return value.padEnd(width, ' ')
        }
        if ('0' !in parsed.flags) {
            return value.padStart(width, ' ')
        }
        val first = value.firstOrNull()
        val signLength = if (first == '-' || first == '+' || first == ' ') 1 else 0
        val prefixLength = if (value.regionMatches(signLength, "0x", 0, 2, ignoreCase = true)) 2 else 0
        val insertion = signLength + prefixLength
        val padding = "0".repeat(width - value.length)
        return value.substring(0, insertion) + padding + value.substring(insertion)
    }

    private fun signedHexFloatExponent(value: String): String {
        val exponent = value.indexOfLast { char -> char == 'p' || char == 'P' }
        if (exponent < 0) {
            return value
        }
        val sign = value.getOrNull(exponent + 1)
        if (sign == '+' || sign == '-') {
            return value
        }
        return value.substring(0, exponent + 1) + "+" + value.substring(exponent + 1)
    }

    private fun formatNonFiniteDecimalFloat(
        value: Double,
        parsed: FormatSpecifier,
        conversion: Char,
    ): String {
        val text = when {
            value.isNaN() -> "nan"
            value < 0.0 -> "-inf"
            '+' in parsed.flags -> "+inf"
            ' ' in parsed.flags -> " inf"
            else -> "inf"
        }.let { formatted ->
            if (conversion == 'E' || conversion == 'G') formatted.uppercase(Locale.ROOT) else formatted
        }
        return applyFloatWidth(text, parsed)
    }

    private fun formatNonFiniteHexFloat(
        value: Double,
        parsed: FormatSpecifier,
        conversion: Char,
    ): String {
        val text = when {
            value.isNaN() -> "nan"
            value < 0.0 -> "-inf"
            '+' in parsed.flags -> "+inf"
            ' ' in parsed.flags -> " inf"
            else -> "inf"
        }.let { formatted ->
            if (conversion == 'A') formatted.uppercase(Locale.ROOT) else formatted
        }
        return applyFloatWidth(text, parsed)
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
            throw invalidConversionSpecification(specifier)
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
            throw invalidConversionSpecification(specifier)
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
                throw invalidConversionSpecification(specifier)
            }
            precisionDigits.takeIf { it.isNotEmpty() }?.toInt() ?: 0
        } else {
            null
        }
        if (cursor != specifier.lastIndex) {
            throw invalidConversionSpecification(specifier)
        }
        return FormatSpecifier(flags, width, precision)
    }

    private fun invalidConversionSpecification(specifier: String): LuaRuntimeException {
        return LuaRuntimeException("invalid conversion specification: '$specifier'")
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

    private fun FormatSpecifier.toJavaSpecifier(conversion: Char): String {
        val normalizedFlags = FORMAT_FLAGS.filter { flag ->
            flag in flags && (width != null || flag != '-' && flag != '0')
        }.filterNot { flag ->
            flag == '0' && '-' in flags || flag == ' ' && '+' in flags
        }
        val widthText = width?.toString() ?: ""
        val precisionText = precision?.let { precision -> ".$precision" } ?: ""
        return "%$normalizedFlags$widthText$precisionText$conversion"
    }

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
                    val nextIsDigit = value.getOrNull(index + 1) in '0'..'9'
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
            else -> throw LuaRuntimeException("bad argument #$index to 'format' (value has no literal form)")
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
        tostringMetamethod(context, index)?.let { return it }
        return when (context.typeName(index)) {
            "nil" -> "nil"
            "boolean" -> context.toBoolean(index).toString()
            "number" -> luaNumberToString(context.get(index))
            "string" -> context.toString(index) ?: context.typeName(index)
            "userdata" -> context.get(index)?.typedPointerString(tostringPointerName(context, index, "userdata")) ?: "userdata"
            "function" -> context.get(index)?.typedPointerString(tostringPointerName(context, index, "function")) ?: "function"
            "table" -> context.getTable(index)?.typedPointerString(tostringPointerName(context, index, "table")) ?: context.typeName(index)
            else -> context.typeName(index)
        }
    }

    private fun tostringMetamethod(context: LuaCallContext, index: Int): String? {
        val metamethod = context.getTableField(context.getMetatable(index), "__tostring")
            ?: return null
        return when (val result = context.call(metamethod, listOf(formatArgumentValue(context, index))).get(1)) {
            is Byte -> result.toLong().toString()
            is Short -> result.toLong().toString()
            is Int -> result.toLong().toString()
            is Long -> result.toString()
            is Float -> luaFloatToString(result.toDouble())
            is Double -> luaFloatToString(result)
            is CharSequence -> result.toString()
            else -> throw LuaRuntimeException("'__tostring' must return a string")
        }
    }

    private fun luaNumberToString(value: Any?): String {
        return when (value) {
            is Byte -> value.toLong().toString()
            is Short -> value.toLong().toString()
            is Int -> value.toLong().toString()
            is Long -> value.toString()
            is Float -> luaFloatToString(value.toDouble())
            is Double -> luaFloatToString(value)
            else -> value?.toString() ?: "number"
        }
    }

    private fun luaFloatToString(value: Double): String {
        if (value.isNaN()) {
            return "nan"
        }
        if (value == Double.POSITIVE_INFINITY) {
            return "inf"
        }
        if (value == Double.NEGATIVE_INFINITY) {
            return "-inf"
        }
        val formatted = String.format(Locale.ROOT, "%.15g", value).lowercase(Locale.ROOT)
        val exponentIndex = formatted.indexOf('e')
        return if (exponentIndex >= 0) {
            val mantissa = formatted.substring(0, exponentIndex).trimLuaFloatTrailingZeros()
            mantissa + formatted.substring(exponentIndex)
        } else {
            val decimal = formatted.trimLuaFloatTrailingZeros()
            if (value.isFiniteWholeNumber() && '.' !in decimal) "$decimal.0" else decimal
        }
    }

    private fun String.trimLuaFloatTrailingZeros(): String {
        if ('.' !in this) {
            return this
        }
        return trimEnd('0').trimEnd('.')
    }

    private fun Double.isFiniteWholeNumber(): Boolean {
        return isFinite() && this % 1.0 == 0.0
    }

    private fun tostringPointerName(context: LuaCallContext, index: Int, defaultName: String): String {
        return (context.getTableField(context.getMetatable(index), "__name") as? CharSequence)?.toString() ?: defaultName
    }

    private fun Any.typedPointerString(typeName: String): String {
        return "$typeName: ${System.identityHashCode(this).toString(16)}"
    }

    private fun formatArgumentValue(context: LuaCallContext, index: Int): Any? {
        return when (context.typeName(index)) {
            "table" -> context.getTable(index)
            else -> context.get(index)
        }
    }

    private fun String.luaByteLength(): Long {
        return luaRawBytes().size.toLong()
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
        val bytes = luaRawBytes()
        val range = bytes.luaIndexRange(start, end)
        if (range.isEmpty()) {
            return ""
        }
        return bytes.copyOfRange(range.first - 1, range.last).toLuaByteString()
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
        return luaByteSearchStart(index).charIndex
    }

    private fun String.luaByteSearchStart(index: Long): LuaByteSearchStart {
        val byteLength = luaByteLength()
        val normalized = when {
            index > 0L -> index
            index == 0L -> 1L
            index < -byteLength -> 1L
            else -> byteLength + index + 1L
        }
        if (normalized > byteLength + 1L) {
            return LuaByteSearchStart(length + 1, normalized)
        }
        if (normalized == byteLength + 1L) {
            return LuaByteSearchStart(length, normalized)
        }

        var bytePosition = 1L
        var charIndex = 0
        while (charIndex < length) {
            val nextCharIndex = nextLuaByteSegmentEnd(charIndex)
            val charBytes = substring(charIndex, nextCharIndex).luaRawBytes().size.toLong()
            if (bytePosition >= normalized) {
                return LuaByteSearchStart(charIndex, normalized)
            }
            bytePosition += charBytes
            charIndex = nextCharIndex
        }
        return LuaByteSearchStart(length, normalized)
    }

    private fun String.nextLuaByteSegmentEnd(charIndex: Int): Int {
        return if (
            this[charIndex].isHighSurrogate() &&
            charIndex + 1 < length &&
            this[charIndex + 1].isLowSurrogate()
        ) {
            charIndex + 2
        } else {
            charIndex + 1
        }
    }

    private fun String.luaBytePosition(charIndex: Int): Long {
        return substring(0, charIndex).luaByteLength() + 1L
    }

    private fun String.luaByteCaptures(
        captures: List<Any?>,
        searchStart: LuaByteSearchStart? = null,
        initialEmptyMatch: Boolean = false,
    ): List<Any?> {
        return captures.map { capture ->
            if (capture is Long) {
                if (initialEmptyMatch && searchStart != null && capture == searchStart.charIndex + 1L) {
                    searchStart.bytePosition
                } else {
                    luaBytePosition((capture - 1L).toInt())
                }
            } else {
                capture
            }
        }
    }

    private fun String.toLuaPatternSubject(): LuaPatternSubject {
        return LuaPatternSubject(luaRawBytes().toOneBytePerCharString())
    }

}

private data class LuaPatternSubject(
    val text: String,
) {
    fun substring(startIndex: Int, endIndex: Int): String {
        return text.substring(startIndex, endIndex).toLuaByteStringFromOneBytePerChar()
    }

    fun luaPatternCaptures(captures: List<Any?>): List<Any?> {
        return captures.map { capture ->
            if (capture is String) {
                capture.toLuaByteStringFromOneBytePerChar()
            } else {
                capture
            }
        }
    }
}

private data class LuaByteSearchStart(
    val charIndex: Int,
    val bytePosition: Long,
)

private fun String.toLuaByteStringFromOneBytePerChar(): String {
    return ByteArray(length) { index -> this[index].code.toByte() }.toLuaByteString()
}

private fun String.toLuaPatternReplacement(): String {
    return luaRawBytes().toOneBytePerCharString()
}

private fun ByteArray.toOneBytePerCharString(): String {
    return buildString(size) {
        for (byte in this@toOneBytePerCharString) {
            append((byte.toInt() and 0xff).toChar())
        }
    }
}
