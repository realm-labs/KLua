package io.github.realmlabs.klua.core.value

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

private const val RAW_BYTE_MARKER_START = 0xDC00
private const val RAW_BYTE_MARKER_END = RAW_BYTE_MARKER_START + 0xFF

internal fun ByteArray.toLuaByteString(): String {
    val builder = StringBuilder()
    var index = 0
    while (index < size) {
        val decoded = decodeUtf8Scalar(index)
        if (decoded != null) {
            builder.appendCodePoint(decoded.codePoint)
            index += decoded.byteCount
        } else {
            builder.append((RAW_BYTE_MARKER_START + (this[index].toInt() and 0xff)).toChar())
            index++
        }
    }
    return builder.toString()
}

internal fun String.luaRawBytes(): ByteArray {
    val output = ByteArrayOutputStream()
    var index = 0
    while (index < length) {
        val char = this[index]
        val code = char.code
        if (code in RAW_BYTE_MARKER_START..RAW_BYTE_MARKER_END) {
            output.write(code - RAW_BYTE_MARKER_START)
            index++
        } else if (char.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) {
            output.write(substring(index, index + 2).toByteArray(StandardCharsets.UTF_8))
            index += 2
        } else {
            output.write(char.toString().toByteArray(StandardCharsets.UTF_8))
            index++
        }
    }
    return output.toByteArray()
}

private fun ByteArray.decodeUtf8Scalar(index: Int): DecodedUtf8Scalar? {
    val first = this[index].toInt() and 0xff
    return when {
        first < 0x80 -> DecodedUtf8Scalar(first, 1)
        first in 0xC2..0xDF -> decodeContinuation(index, 1, minimum = 0x80, maximum = 0x7FF)
        first in 0xE0..0xEF -> decodeContinuation(index, 2, minimum = 0x800, maximum = 0xFFFF)
            ?.takeUnless { it.codePoint in 0xD800..0xDFFF }
        first in 0xF0..0xF4 -> decodeContinuation(index, 3, minimum = 0x10000, maximum = 0x10FFFF)
        else -> null
    }
}

private fun ByteArray.decodeContinuation(
    index: Int,
    continuationCount: Int,
    minimum: Int,
    maximum: Int,
): DecodedUtf8Scalar? {
    if (index + continuationCount >= size) {
        return null
    }
    var codePoint = this[index].toInt() and (0x7F shr continuationCount)
    for (offset in 1..continuationCount) {
        val byte = this[index + offset].toInt() and 0xff
        if (byte !in 0x80..0xBF) {
            return null
        }
        codePoint = (codePoint shl 6) or (byte and 0x3F)
    }
    if (codePoint !in minimum..maximum) {
        return null
    }
    return DecodedUtf8Scalar(codePoint, continuationCount + 1)
}

private data class DecodedUtf8Scalar(
    val codePoint: Int,
    val byteCount: Int,
)
