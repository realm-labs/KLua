package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState
import io.github.realmlabs.klua.api.LuaStatus
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
