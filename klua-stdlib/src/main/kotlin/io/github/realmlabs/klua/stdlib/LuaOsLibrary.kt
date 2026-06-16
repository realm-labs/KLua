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
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

internal object LuaOsLibrary {
    private val startupLocale: Locale = Locale.getDefault()

    fun open(state: LuaState): LuaState {
        val startNanos = System.nanoTime()
        state.newTable()
        setFunctionField(state, "clock") { context -> clock(context, startNanos) }
        setFunctionField(state, "date", ::date)
        setFunctionField(state, "difftime", ::difftime)
        setFunctionField(state, "execute", ::execute)
        setFunctionField(state, "getenv", ::getenv)
        setFunctionField(state, "remove", ::remove)
        setFunctionField(state, "rename", ::rename)
        setFunctionField(state, "setlocale", ::setlocale)
        setFunctionField(state, "time", ::time)
        setFunctionField(state, "tmpname", ::tmpname)
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

    private fun execute(context: LuaCallContext): LuaReturn {
        if (context.isNone(1) || context.isNil(1)) {
            return LuaReturn.of(true)
        }
        val command = requiredString(context, 1, "os.execute")
        val exitCode = try {
            ProcessBuilder(shellCommand(command))
                .inheritIO()
                .start()
                .waitFor()
        } catch (_: IOException) {
            return LuaReturn.of(null, "exit", 1L)
        } catch (_: SecurityException) {
            return LuaReturn.of(null, "exit", 1L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw LuaRuntimeException("interrupted while executing command")
        }
        return executeResult(exitCode)
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

    private fun tmpname(context: LuaCallContext): LuaReturn {
        return try {
            val path = Files.createTempFile("klua-", ".tmp")
            Files.deleteIfExists(path)
            LuaReturn.of(path.toString())
        } catch (_: IOException) {
            throw LuaRuntimeException("unable to generate a unique filename")
        } catch (_: SecurityException) {
            throw LuaRuntimeException("unable to generate a unique filename")
        }
    }

    @Synchronized
    private fun setlocale(context: LuaCallContext): LuaReturn {
        val localeName = if (context.isNone(1) || context.isNil(1)) {
            null
        } else {
            requiredString(context, 1, "os.setlocale")
        }
        val category = if (context.isNone(2) || context.isNil(2)) {
            "all"
        } else {
            requiredString(context, 2, "os.setlocale")
        }
        if (category !in LOCALE_CATEGORIES) {
            throw LuaRuntimeException("bad argument #2 to 'os.setlocale' (invalid option '$category')")
        }
        if (localeName == null) {
            return LuaReturn.of(localeName(Locale.getDefault()))
        }

        val locale = when (localeName) {
            "C" -> Locale.ROOT
            "" -> startupLocale
            else -> parseLocale(localeName) ?: return LuaReturn.of(null)
        }
        Locale.setDefault(locale)
        return LuaReturn.of(localeName(locale))
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
        val zone = if (utc) ZoneOffset.UTC else ZoneId.systemDefault()
        val dateTime = try {
            Instant.ofEpochSecond(time).atZone(zone)
        } catch (_: DateTimeException) {
            throw LuaRuntimeException("date result cannot be represented in this installation")
        }
        return if (body == "*t") {
            LuaReturn.of(dateTimeFields(dateTime))
        } else {
            LuaReturn.of(formatDate(body, dateTime))
        }
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

    private fun executeResult(exitCode: Int): LuaReturn {
        return if (exitCode == 0) {
            LuaReturn.of(true, "exit", 0L)
        } else {
            LuaReturn.of(null, "exit", exitCode.toLong())
        }
    }

    private fun shellCommand(command: String): List<String> {
        return if (isWindows()) {
            listOf("cmd", "/c", command)
        } else {
            listOf("sh", "-c", command)
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

    private fun formatDate(format: String, dateTime: ZonedDateTime): String {
        val result = StringBuilder()
        var index = 0
        while (index < format.length) {
            val char = format[index]
            if (char != '%') {
                result.append(char)
                index++
                continue
            }
            if (index + 1 >= format.length) {
                throw invalidDateFormat("")
            }
            index++
            val modifier = if (format[index] == 'E' || format[index] == 'O') {
                val value = format[index]
                index++
                value
            } else {
                null
            }
            if (index >= format.length) {
                throw invalidDateFormat(modifier?.toString() ?: "")
            }
            val conversion = format[index]
            result.append(formatDateConversion(dateTime, modifier, conversion))
            index++
        }
        return result.toString()
    }

    private fun formatDateConversion(dateTime: ZonedDateTime, modifier: Char?, conversion: Char): String {
        if (!isValidDateAlternateConversion(modifier, conversion)) {
            throw invalidDateFormat("$modifier$conversion")
        }
        return when (conversion) {
            'a' -> dateTime.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()))
            'A' -> dateTime.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))
            'b',
            'h',
            -> dateTime.format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault()))
            'B' -> dateTime.format(DateTimeFormatter.ofPattern("MMMM", Locale.getDefault()))
            'c' -> formatDate("%a %b %d %H:%M:%S %Y", dateTime)
            'C' -> twoDigits(java.lang.Math.floorDiv(dateTime.year, 100))
            'd' -> twoDigits(dateTime.dayOfMonth)
            'D' -> formatDate("%m/%d/%y", dateTime)
            'e' -> dateTime.dayOfMonth.toString().padStart(2, ' ')
            'F' -> formatDate("%Y-%m-%d", dateTime)
            'g' -> twoDigits(java.lang.Math.floorMod(isoWeekYear(dateTime), 100))
            'G' -> fourDigits(isoWeekYear(dateTime))
            'H' -> twoDigits(dateTime.hour)
            'I' -> twoDigits(hour12(dateTime.hour))
            'j' -> dateTime.dayOfYear.toString().padStart(3, '0')
            'm' -> twoDigits(dateTime.monthValue)
            'M' -> twoDigits(dateTime.minute)
            'n' -> "\n"
            'p' -> if (dateTime.hour < 12) "AM" else "PM"
            'r' -> formatDate("%I:%M:%S %p", dateTime)
            'R' -> formatDate("%H:%M", dateTime)
            'S' -> twoDigits(dateTime.second)
            't' -> "\t"
            'T' -> formatDate("%H:%M:%S", dateTime)
            'u' -> dateTime.dayOfWeek.value.toString()
            'U' -> twoDigits(weekOfYear(dateTime, firstDay = 7))
            'V' -> twoDigits(dateTime.get(WeekFields.ISO.weekOfWeekBasedYear()))
            'w' -> (dateTime.dayOfWeek.value % 7).toString()
            'W' -> twoDigits(weekOfYear(dateTime, firstDay = 1))
            'x' -> formatDate("%m/%d/%y", dateTime)
            'X' -> formatDate("%H:%M:%S", dateTime)
            'y' -> twoDigits(java.lang.Math.floorMod(dateTime.year, 100))
            'Y' -> fourDigits(dateTime.year)
            'z' -> zoneOffset(dateTime)
            'Z' -> dateTime.zone.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
            '%' -> "%"
            else -> throw invalidDateFormat((modifier?.toString() ?: "") + conversion)
        }
    }

    private fun invalidDateFormat(conversion: String): LuaRuntimeException {
        return LuaRuntimeException("bad argument #1 to 'os.date' (invalid conversion specifier '%$conversion')")
    }

    private fun isValidDateAlternateConversion(modifier: Char?, conversion: Char): Boolean {
        return when (modifier) {
            null -> true
            'E' -> conversion in "cCxXyY"
            'O' -> conversion in "deHImMSuUVwWy"
            else -> false
        }
    }

    private fun weekOfYear(dateTime: ZonedDateTime, firstDay: Int): Int {
        val yearDay = dateTime.dayOfYear
        val janFirst = dateTime.withDayOfYear(1)
        val janFirstDay = janFirst.dayOfWeek.value % 7
        val first = firstDay % 7
        val daysBeforeFirstWeek = (first - janFirstDay + 7) % 7
        if (yearDay <= daysBeforeFirstWeek) {
            return 0
        }
        return (yearDay - daysBeforeFirstWeek + 6) / 7
    }

    private fun isoWeekYear(dateTime: ZonedDateTime): Int {
        return dateTime.get(WeekFields.ISO.weekBasedYear())
    }

    private fun hour12(hour: Int): Int {
        val value = hour % 12
        return if (value == 0) 12 else value
    }

    private fun zoneOffset(dateTime: ZonedDateTime): String {
        val offset = dateTime.offset.totalSeconds
        val sign = if (offset < 0) "-" else "+"
        val absolute = kotlin.math.abs(offset)
        val hours = absolute / 3600
        val minutes = (absolute % 3600) / 60
        return "$sign${twoDigits(hours)}${twoDigits(minutes)}"
    }

    private fun twoDigits(value: Int): String {
        return value.toString().padStart(2, '0')
    }

    private fun fourDigits(value: Int): String {
        return value.toString().padStart(4, '0')
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

    private fun parseLocale(value: String): Locale? {
        val normalized = value.substringBefore('.').replace('_', '-')
        if (!LOCALE_TAG_PATTERN.matches(normalized)) {
            return null
        }
        val locale = Locale.forLanguageTag(normalized)
        return locale.takeIf { it.language.isNotEmpty() && it.language != "und" }
    }

    private fun localeName(locale: Locale): String {
        return if (locale == Locale.ROOT) "C" else locale.toString()
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
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
    private val LOCALE_CATEGORIES = setOf("all", "collate", "ctype", "monetary", "numeric", "time")
    private val LOCALE_TAG_PATTERN = Regex("[A-Za-z]{2,3}(-([A-Za-z]{2}|[0-9]{3}))?(-[A-Za-z0-9]+)*")
}
