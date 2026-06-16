package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

internal object LuaOsLibrary {
    fun open(state: LuaState): LuaState {
        val startNanos = System.nanoTime()
        state.newTable()
        setFunctionField(state, "clock") { context -> clock(context, startNanos) }
        setFunctionField(state, "difftime", ::difftime)
        setFunctionField(state, "time", ::time)
        state.setGlobal("os")
        return state
    }

    private fun clock(context: LuaCallContext, startNanos: Long): LuaReturn {
        return LuaReturn.of((System.nanoTime() - startNanos).toDouble() / NANOS_PER_SECOND)
    }

    private fun difftime(context: LuaCallContext): LuaReturn {
        val first = requiredTime(context, 1, "os.difftime")
        val second = requiredTime(context, 2, "os.difftime")
        return LuaReturn.of((first - second).toDouble())
    }

    private fun time(context: LuaCallContext): LuaReturn {
        if (context.isNone(1) || context.isNil(1)) {
            return LuaReturn.of(Instant.now().epochSecond)
        }
        if (!context.isTable(1)) {
            throw LuaRuntimeException("bad argument #1 to 'os.time' (table expected)")
        }

        val year = requiredDateField(context, "year").toInt()
        val month = requiredDateField(context, "month")
        val day = requiredDateField(context, "day")
        val hour = optionalDateField(context, "hour", 12L)
        val minute = optionalDateField(context, "min", 0L)
        val second = optionalDateField(context, "sec", 0L)

        val normalized = try {
            LocalDateTime.of(year, 1, 1, 0, 0, 0)
                .plusMonths(month - 1L)
                .plusDays(day - 1L)
                .plusHours(hour)
                .plusMinutes(minute)
                .plusSeconds(second)
                .atZone(ZoneId.systemDefault())
        } catch (_: DateTimeException) {
            throw LuaRuntimeException("time result cannot be represented in this installation")
        } catch (_: ArithmeticException) {
            throw LuaRuntimeException("time result cannot be represented in this installation")
        }

        context.setTableValue(1, "year", normalized.year.toLong())
        context.setTableValue(1, "month", normalized.monthValue.toLong())
        context.setTableValue(1, "day", normalized.dayOfMonth.toLong())
        context.setTableValue(1, "hour", normalized.hour.toLong())
        context.setTableValue(1, "min", normalized.minute.toLong())
        context.setTableValue(1, "sec", normalized.second.toLong())
        context.setTableValue(1, "wday", normalized.dayOfWeek.value % 7L + 1L)
        context.setTableValue(1, "yday", normalized.dayOfYear.toLong())
        context.setTableValue(1, "isdst", normalized.zone.rules.isDaylightSavings(normalized.toInstant()))

        return LuaReturn.of(normalized.toEpochSecond())
    }

    private fun requiredTime(context: LuaCallContext, index: Int, functionName: String): Long {
        return context.toInteger(index) ?: throw LuaRuntimeException(
            if (context.toNumber(index) != null) {
                "bad argument #$index to '$functionName' (number has no integer representation)"
            } else {
                "bad argument #$index to '$functionName' (number expected)"
            },
        )
    }

    private fun requiredDateField(context: LuaCallContext, key: String): Long {
        val value = context.getTableValue(1, key)
            ?: throw LuaRuntimeException("field '$key' missing in date table")
        val integer = luaInteger(value)
            ?: throw LuaRuntimeException("field '$key' is not an integer")
        return checkedDateField(key, integer)
    }

    private fun optionalDateField(context: LuaCallContext, key: String, defaultValue: Long): Long {
        val value = context.getTableValue(1, key) ?: return defaultValue
        val integer = luaInteger(value)
            ?: throw LuaRuntimeException("field '$key' is not an integer")
        return checkedDateField(key, integer)
    }

    private fun checkedDateField(key: String, value: Long): Long {
        try {
            java.lang.Math.toIntExact(value)
        } catch (_: ArithmeticException) {
            throw LuaRuntimeException("field '$key' is out-of-bound")
        }
        return value
    }

    private fun luaInteger(value: Any?): Long? {
        return when (value) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            is Float -> value.toDouble().luaInteger()
            is Double -> value.luaInteger()
            is CharSequence -> value.toString().trim().toLongOrNull()
                ?: value.toString().trim().toDoubleOrNull()?.luaInteger()
            else -> null
        }
    }

    private fun Double.luaInteger(): Long? {
        if (!isFinite()) {
            return null
        }
        val integer = toLong()
        return if (integer.toDouble() == this) integer else null
    }

    private fun setFunctionField(state: LuaState, name: String, function: (LuaCallContext) -> LuaReturn) {
        state.pushFunction(function::invoke)
        state.setField(-2, name)
    }

    private const val NANOS_PER_SECOND = 1_000_000_000.0
}
