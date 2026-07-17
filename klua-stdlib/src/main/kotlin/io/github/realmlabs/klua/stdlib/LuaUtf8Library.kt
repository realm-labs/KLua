package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaFunction
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState
import io.github.realmlabs.klua.core.value.toLuaByteString

internal object LuaUtf8Library {
    private const val MAX_UNICODE_CODE_POINT = 0x10FFFFL
    private const val MAX_UTF_CODE_POINT = 0x7FFFFFFFL
    private const val HIGH_SURROGATE_START = 0xD800L
    private const val LOW_SURROGATE_END = 0xDFFFL
    private const val CHAR_PATTERN = "[\u0000-\u007F\u00C2-\u00FD][\u0080-\u00BF]*"
    private val UTF8_MIN_BY_CONTINUATION_COUNT = longArrayOf(
        Long.MAX_VALUE,
        0x80L,
        0x800L,
        0x10000L,
        0x200000L,
        0x4000000L,
    )

    fun open(state: LuaState): LuaState {
        state.newTable()
        setFunctionField(state, "char", ::utf8Char)
        setFunctionField(state, "codepoint", ::utf8Codepoint)
        setFunctionField(state, "codes", ::utf8Codes)
        setFunctionField(state, "len", ::utf8Len)
        setFunctionField(state, "offset", ::utf8Offset)
        state.pushString(CHAR_PATTERN)
        state.setField(-2, "charpattern")
        state.setGlobal("utf8")
        return state
    }

    private fun utf8Char(context: LuaCallContext): LuaReturn {
        val bytes = mutableListOf<Byte>()
        for (index in 1..context.argumentCount) {
            val codePoint = requiredCodePoint(context, index, "char")
            bytes += encodeUtf8CodePoint(codePoint).toList()
        }
        return LuaReturn.of(bytes.toByteArray().toLuaByteString())
    }

    private fun utf8Codepoint(context: LuaCallContext): LuaReturn {
        val bytes = requiredBytes(context, 1, "codepoint")
        val byteLength = bytes.size.toLong()
        val start = normalizedCodepointStart(context, 2, 1L, byteLength, "codepoint")
        val end = normalizedCodepointEnd(context, 3, start, byteLength, "codepoint")
        val lax = context.toBoolean(4)
        if (start > end) {
            return LuaReturn.none()
        }
        val values = mutableListOf<Long>()
        var byteIndex = (start - 1L).toInt()
        val endExclusive = end.toInt()
        while (byteIndex < endExclusive) {
            val decoded = decodeUtf8(bytes, byteIndex, strict = !lax)
                ?: throw LuaRuntimeException("invalid UTF-8 code")
            values += decoded.codePoint
            byteIndex = decoded.nextIndex
        }
        return LuaReturn.ofValues(values)
    }

    private fun utf8Codes(context: LuaCallContext): LuaReturn {
        val text = requiredString(context, 1, "codes")
        val textBytes = requiredBytes(context, 1, "codes")
        val lax = context.toBoolean(2)
        if (textBytes.firstOrNull()?.let(::isContinuationByte) == true) {
            throw LuaRuntimeException("bad argument #1 to 'codes' (invalid UTF-8 code)")
        }
        val iterator = LuaFunction { iteratorContext ->
            val bytes = requiredBytes(iteratorContext, 1, "codes")
            val byteLength = bytes.size.toLong()
            val previousPosition = iteratorContext.toInteger(2) ?: 0L
            if (previousPosition < 0L || previousPosition >= byteLength) {
                return@LuaFunction LuaReturn.none()
            }

            var byteIndex = previousPosition.toInt()
            while (byteIndex < bytes.size && isContinuationByte(bytes[byteIndex])) {
                byteIndex++
            }
            if (byteIndex >= bytes.size) {
                LuaReturn.none()
            } else {
                val decoded = decodeUtf8(bytes, byteIndex, strict = !lax)
                if (decoded == null || decoded.nextIndex < bytes.size && isContinuationByte(bytes[decoded.nextIndex])) {
                    throw LuaRuntimeException("invalid UTF-8 code")
                }
                LuaReturn.of(byteIndex + 1L, decoded.codePoint)
            }
        }
        return LuaReturn.ofValues(listOf(iterator, text, 0L))
    }

    private fun utf8Len(context: LuaCallContext): LuaReturn {
        val bytes = requiredBytes(context, 1, "len")
        val byteLength = bytes.size.toLong()
        val start = normalizedLenStart(context, 2, 1L, byteLength, "len")
        val end = normalizedLenEnd(context, 3, -1L, byteLength, "len")
        val lax = context.toBoolean(4)
        if (start > end) {
            return LuaReturn.of(0L)
        }
        var count = 0L
        var byteIndex = (start - 1L).toInt()
        val endExclusive = end.toInt()
        while (byteIndex < endExclusive) {
            val decoded = decodeUtf8(bytes, byteIndex, strict = !lax)
                ?: return LuaReturn.of(null, byteIndex + 1L)
            byteIndex = decoded.nextIndex
            count++
        }
        return LuaReturn.of(count)
    }

    private fun utf8Offset(context: LuaCallContext): LuaReturn {
        val bytes = requiredBytes(context, 1, "offset")
        val byteLength = bytes.size.toLong()
        val offset = requiredNumberInteger(context, 2, "offset")
        val defaultPosition = if (offset < 0L) {
            byteLength + 1L
        } else {
            1L
        }
        val position = normalizedOffsetPosition(context, 3, defaultPosition, byteLength, "offset")
        var byteIndex = (position - 1L).toInt()
        if (offset == 0L) {
            while (byteIndex > 0 && byteIndex < bytes.size && isContinuationByte(bytes[byteIndex])) {
                byteIndex--
            }
        } else {
            if (byteIndex < bytes.size && isContinuationByte(bytes[byteIndex])) {
                throw LuaRuntimeException("initial position is a continuation byte")
            }
            var remaining = offset
            if (remaining < 0L) {
                while (remaining < 0L && byteIndex > 0) {
                    do {
                        byteIndex--
                    } while (byteIndex > 0 && isContinuationByte(bytes[byteIndex]))
                    remaining++
                }
            } else {
                remaining--
                while (remaining > 0L && byteIndex < bytes.size) {
                    do {
                        byteIndex++
                    } while (byteIndex < bytes.size && isContinuationByte(bytes[byteIndex]))
                    remaining--
                }
            }
            if (remaining != 0L) {
                return LuaReturn.of(null)
            }
        }
        if (byteIndex < 0 || byteIndex > bytes.size) {
            return LuaReturn.of(null)
        }
        return utf8OffsetResult(bytes, byteIndex, byteLength)
    }

    private fun setFunctionField(state: LuaState, name: String, function: (LuaCallContext) -> LuaReturn) {
        state.pushFunction(function)
        state.setField(-2, name)
    }

    private fun requiredString(context: LuaCallContext, index: Int, functionName: String): String {
        return context.toString(index)
            ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (string expected)")
    }

    private fun requiredBytes(context: LuaCallContext, index: Int, functionName: String): ByteArray {
        return context.toBytes(index)
            ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (string expected)")
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

    private fun requiredCodePoint(context: LuaCallContext, index: Int, functionName: String): Long {
        val codePoint = requiredNumberInteger(context, index, functionName)
        if (codePoint !in 0L..MAX_UTF_CODE_POINT) {
            throw LuaRuntimeException("bad argument #$index to '$functionName' (value out of range)")
        }
        return codePoint
    }

    private fun normalizedCodepointStart(
        context: LuaCallContext,
        index: Int,
        defaultValue: Long,
        length: Long,
        functionName: String,
    ): Long {
        val position = if (context.isNone(index) || context.isNil(index)) {
            defaultValue
        } else {
            requiredNumberInteger(context, index, functionName)
        }
        val normalized = if (position < 0L) length + position + 1L else position
        if (normalized < 1L) {
            throw LuaRuntimeException("bad argument #$index to '$functionName' (out of bounds)")
        }
        return normalized
    }

    private fun normalizedCodepointEnd(
        context: LuaCallContext,
        index: Int,
        defaultValue: Long,
        length: Long,
        functionName: String,
    ): Long {
        val position = if (context.isNone(index) || context.isNil(index)) {
            defaultValue
        } else {
            requiredNumberInteger(context, index, functionName)
        }
        val normalized = if (position < 0L) (length + position + 1L).coerceAtLeast(0L) else position
        if (normalized < 0L || normalized > length) {
            throw LuaRuntimeException("bad argument #$index to '$functionName' (out of bounds)")
        }
        return normalized
    }

    private fun normalizedLenStart(
        context: LuaCallContext,
        index: Int,
        defaultValue: Long,
        length: Long,
        functionName: String,
    ): Long {
        val position = if (context.isNone(index) || context.isNil(index)) {
            defaultValue
        } else {
            requiredNumberInteger(context, index, functionName)
        }
        val normalized = if (position < 0L) length + position + 1L else position
        if (normalized < 1L || normalized > length + 1L) {
            throw LuaRuntimeException("bad argument #$index to '$functionName' (initial position out of bounds)")
        }
        return normalized
    }

    private fun normalizedLenEnd(
        context: LuaCallContext,
        index: Int,
        defaultValue: Long,
        length: Long,
        functionName: String,
    ): Long {
        val position = if (context.isNone(index) || context.isNil(index)) {
            defaultValue
        } else {
            requiredNumberInteger(context, index, functionName)
        }
        val normalized = if (position < 0L) (length + position + 1L).coerceAtLeast(0L) else position
        if (normalized < 0L || normalized > length) {
            throw LuaRuntimeException("bad argument #$index to '$functionName' (final position out of bounds)")
        }
        return normalized
    }

    private fun decodeUtf8(bytes: ByteArray, start: Int, strict: Boolean): DecodedUtf8? {
        if (start !in bytes.indices) {
            return null
        }
        val first = bytes[start].toInt() and 0xff
        if (first < 0x80) {
            return DecodedUtf8(first.toLong(), start + 1)
        }
        val continuationCount = when (first) {
            in 0xC0..0xDF -> 1
            in 0xE0..0xEF -> 2
            in 0xF0..0xF7 -> 3
            in 0xF8..0xFB -> 4
            in 0xFC..0xFD -> 5
            else -> return null
        }
        if (start + continuationCount >= bytes.size) {
            return null
        }
        var codePoint = (first and (0x7F shr continuationCount)).toLong()
        for (offset in 1..continuationCount) {
            val byte = bytes[start + offset].toInt() and 0xff
            if (!isContinuationByte(byte)) {
                return null
            }
            codePoint = (codePoint shl 6) or (byte and 0x3F).toLong()
        }
        if (codePoint > MAX_UTF_CODE_POINT || codePoint < UTF8_MIN_BY_CONTINUATION_COUNT[continuationCount]) {
            return null
        }
        if (strict && isInvalidStrictCodePoint(codePoint)) {
            return null
        }
        return DecodedUtf8(codePoint, start + continuationCount + 1)
    }

    private fun encodeUtf8CodePoint(codePoint: Long): ByteArray {
        return when {
            codePoint < 0x80L -> byteArrayOf(codePoint.toByte())
            codePoint <= 0x7FFL -> byteArrayOf(
                (0xC0 or (codePoint shr 6).toInt()).toByte(),
                continuationByte(codePoint, 0),
            )
            codePoint <= 0xFFFFL -> byteArrayOf(
                (0xE0 or (codePoint shr 12).toInt()).toByte(),
                continuationByte(codePoint, 6),
                continuationByte(codePoint, 0),
            )
            codePoint <= 0x1FFFFFL -> byteArrayOf(
                (0xF0 or (codePoint shr 18).toInt()).toByte(),
                continuationByte(codePoint, 12),
                continuationByte(codePoint, 6),
                continuationByte(codePoint, 0),
            )
            codePoint <= 0x3FFFFFFL -> byteArrayOf(
                (0xF8 or (codePoint shr 24).toInt()).toByte(),
                continuationByte(codePoint, 18),
                continuationByte(codePoint, 12),
                continuationByte(codePoint, 6),
                continuationByte(codePoint, 0),
            )
            else -> byteArrayOf(
                (0xFC or (codePoint shr 30).toInt()).toByte(),
                continuationByte(codePoint, 24),
                continuationByte(codePoint, 18),
                continuationByte(codePoint, 12),
                continuationByte(codePoint, 6),
                continuationByte(codePoint, 0),
            )
        }
    }

    private fun continuationByte(codePoint: Long, shift: Int): Byte {
        return (0x80 or ((codePoint shr shift).toInt() and 0x3F)).toByte()
    }

    private fun isInvalidStrictCodePoint(codePoint: Long): Boolean {
        return codePoint > MAX_UNICODE_CODE_POINT || codePoint in HIGH_SURROGATE_START..LOW_SURROGATE_END
    }

    private fun utf8OffsetResult(
        bytes: ByteArray,
        startIndex: Int,
        byteLength: Long,
    ): LuaReturn {
        if (startIndex == bytes.size) {
            return LuaReturn.of(byteLength + 1L, byteLength + 1L)
        }
        if (isContinuationByte(bytes[startIndex])) {
            throw LuaRuntimeException("initial position is a continuation byte")
        }
        var endIndex = startIndex
        if ((bytes[startIndex].toInt() and 0x80) != 0) {
            while (endIndex + 1 < bytes.size && isContinuationByte(bytes[endIndex + 1])) {
                endIndex++
            }
        }
        return LuaReturn.of(startIndex + 1L, endIndex + 1L)
    }

    private fun normalizedOffsetPosition(
        context: LuaCallContext,
        index: Int,
        defaultValue: Long,
        length: Long,
        functionName: String,
    ): Long {
        val position = if (context.isNone(index) || context.isNil(index)) {
            defaultValue
        } else {
            requiredNumberInteger(context, index, functionName)
        }
        val normalized = if (position < 0L) length + position + 1L else position
        if (normalized < 1L || normalized > length + 1L) {
            throw LuaRuntimeException("bad argument #$index to '$functionName' (position out of bounds)")
        }
        return normalized
    }

    private fun isContinuationByte(byte: Byte): Boolean {
        return isContinuationByte(byte.toInt() and 0xff)
    }

    private fun isContinuationByte(byte: Int): Boolean {
        return byte and 0xC0 == 0x80
    }

    private data class DecodedUtf8(
        val codePoint: Long,
        val nextIndex: Int,
    )
}
