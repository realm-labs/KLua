package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

internal object LuaOsLibrary {
    fun open(state: LuaState): LuaState {
        val startNanos = System.nanoTime()
        state.newTable()
        setFunctionField(state, "clock") { context -> clock(context, startNanos) }
        setFunctionField(state, "date", ::date)
        setFunctionField(state, "difftime", ::difftime)
        setFunctionField(state, "getenv", ::getenv)
        setFunctionField(state, "remove", ::remove)
        setFunctionField(state, "rename", ::rename)
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

    private fun getenv(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(System.getenv(requiredString(context, 1, "os.getenv")))
    }

    private fun remove(context: LuaCallContext): LuaReturn {
        val filename = requiredString(context, 1, "os.remove")
        return fileResult(filename) {
            Files.delete(Path.of(filename))
        }
    }

    private fun rename(context: LuaCallContext): LuaReturn {
        val source = requiredString(context, 1, "os.rename")
        val target = requiredString(context, 2, "os.rename")
        return fileResult(source) {
            Files.move(Path.of(source), Path.of(target), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun date(context: LuaCallContext): LuaReturn {
        val format = if (context.isNone(1) || context.isNil(1)) {
            "%c"
        } else {
            requiredString(context, 1, "os.date")
        }
        val time = if (context.isNone(2) || context.isNil(2)) {
            Instant.now().epochSecond
        } else {
            requiredTime(context, 2, "os.date")
        }

        val utc = format.startsWith('!')
        val body = if (utc) format.substring(1) else format
        if (body != "*t") {
            throw LuaRuntimeException("bad argument #1 to 'os.date' (unsupported date format)")
        }

        val zone = if (utc) ZoneOffset.UTC else ZoneId.systemDefault()
        val dateTime = try {
            Instant.ofEpochSecond(time).atZone(zone)
        } catch (_: DateTimeException) {
            throw LuaRuntimeException("date result cannot be represented in this installation")
        }
        return LuaReturn.of(dateTimeFields(dateTime))
    }

    private fun fileResult(filename: String, action: () -> Unit): LuaReturn {
        return try {
            action()
            LuaReturn.of(true)
        } catch (error: IOException) {
            LuaReturn.of(null, "$filename: ${error.message ?: error::class.java.simpleName}")
        } catch (error: SecurityException) {
            LuaReturn.of(null, "$filename: ${error.message ?: error::class.java.simpleName}")
        }
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

    private fun dateTimeFields(dateTime: ZonedDateTime): LinkedHashMap<String, Any?> {
        return linkedMapOf(
            "year" to dateTime.year.toLong(),
            "month" to dateTime.monthValue.toLong(),
            "day" to dateTime.dayOfMonth.toLong(),
            "hour" to dateTime.hour.toLong(),
            "min" to dateTime.minute.toLong(),
            "sec" to dateTime.second.toLong(),
            "wday" to (dateTime.dayOfWeek.value % 7L + 1L),
            "yday" to dateTime.dayOfYear.toLong(),
            "isdst" to dateTime.zone.rules.isDaylightSavings(dateTime.toInstant()),
        )
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

    private fun requiredString(context: LuaCallContext, index: Int, functionName: String): String {
        return context.toString(index)
            ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (string expected)")
    }

    private fun requiredDateField(context: LuaCallContext, key: String): Long {
        val value = dateTableField(context, key)
            ?: throw LuaRuntimeException("field '$key' missing in date table")
        val integer = luaInteger(value)
            ?: throw LuaRuntimeException("field '$key' is not an integer")
        return checkedDateField(key, integer)
    }

    private fun optionalDateField(context: LuaCallContext, key: String, defaultValue: Long): Long {
        val value = dateTableField(context, key) ?: return defaultValue
        val integer = luaInteger(value)
            ?: throw LuaRuntimeException("field '$key' is not an integer")
        return checkedDateField(key, integer)
    }

    private fun dateTableField(context: LuaCallContext, key: String): Any? {
        val rawValue = context.getTableValue(1, key)
        if (rawValue != null) {
            return rawValue
        }
        val index = context.getTableField(context.getMetatable(1), "__index") ?: return null
        return try {
            context.call(index, listOf(context.getLuaValue(1), key)).get(1)
        } catch (_: IllegalArgumentException) {
            context.getTableField(index, key)
        }
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
