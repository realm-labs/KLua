package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState
import io.github.realmlabs.klua.api.LuaStatus
import io.github.realmlabs.klua.core.value.luaRawBytes
import io.github.realmlabs.klua.core.value.toLuaByteString
import java.text.DecimalFormatSymbols
import java.util.Locale

internal fun installLuaSource(state: LuaState, source: String, chunkName: String) {
    val loadStatus = state.load(source, chunkName)
    if (loadStatus != LuaStatus.OK) {
        throw LuaRuntimeException(state.toString(-1) ?: "failed to load $chunkName")
    }
    val callStatus = state.pcall(0, 0)
    if (callStatus != LuaStatus.OK) {
        throw LuaRuntimeException(state.toString(-1) ?: "failed to run $chunkName")
    }
}

internal data class LoadFileContent(
    val source: String,
    val bytes: ByteArray,
)

internal fun loadFileContent(bytes: ByteArray): LoadFileContent {
    val start = luaLoadFileTextStart(bytes)
    val contentBytes = bytes.copyOfRange(start.offset, bytes.size)
    val source = contentBytes.toLuaByteString()
    return LoadFileContent(
        source = if (start.skippedShebang) "\n$source" else source,
        bytes = contentBytes,
    )
}

internal fun loadFileContent(source: String): LoadFileContent {
    val bytes = source.luaRawBytes()
    val start = luaLoadFileTextStart(bytes)
    if (start.offset == 0 && !start.skippedShebang) {
        return LoadFileContent(source, bytes)
    }
    val contentBytes = bytes.copyOfRange(start.offset, bytes.size)
    val remaining = contentBytes.toLuaByteString()
    return LoadFileContent(
        source = if (start.skippedShebang) "\n$remaining" else remaining,
        bytes = contentBytes,
    )
}

internal fun luaNumberToString(value: Any?): String {
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

internal fun luaLocaleDecimalPoint(): Char {
    return DecimalFormatSymbols.getInstance(Locale.getDefault()).decimalSeparator
}

private fun luaLoadFileTextStart(bytes: ByteArray): LuaLoadFileTextStart {
    var offset = if (bytes.size >= 3 &&
        (bytes[0].toInt() and 0xff) == 0xEF &&
        (bytes[1].toInt() and 0xff) == 0xBB &&
        (bytes[2].toInt() and 0xff) == 0xBF
    ) {
        3
    } else {
        0
    }
    if (offset < bytes.size && bytes[offset] == '#'.code.toByte()) {
        offset++
        while (offset < bytes.size && bytes[offset] != '\n'.code.toByte()) {
            offset++
        }
        if (offset < bytes.size) {
            offset++
        }
        return LuaLoadFileTextStart(offset, skippedShebang = true)
    }
    return LuaLoadFileTextStart(offset, skippedShebang = false)
}

private data class LuaLoadFileTextStart(
    val offset: Int,
    val skippedShebang: Boolean,
)

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
    val decimalPoint = luaLocaleDecimalPoint()
    val formatted = String.format(Locale.getDefault(), "%.15g", value).lowercase(Locale.ROOT)
    val exponentIndex = formatted.indexOf('e')
    return if (exponentIndex >= 0) {
        val mantissa = formatted.substring(0, exponentIndex).trimLuaFloatTrailingZeros(decimalPoint)
        mantissa + formatted.substring(exponentIndex)
    } else {
        val decimal = formatted.trimLuaFloatTrailingZeros(decimalPoint)
        if (value.isFiniteWholeNumber() && decimalPoint !in decimal) "${decimal}${decimalPoint}0" else decimal
    }
}

private fun String.trimLuaFloatTrailingZeros(decimalPoint: Char): String {
    if (decimalPoint !in this) {
        return this
    }
    return trimEnd('0').trimEnd(decimalPoint)
}

private fun Double.isFiniteWholeNumber(): Boolean {
    return isFinite() && this % 1.0 == 0.0
}
