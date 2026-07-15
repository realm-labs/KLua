package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaExitException
import io.github.realmlabs.klua.api.LuaExitHandler
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState
import java.io.IOException
import java.math.BigInteger
import java.nio.file.FileSystemException
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
        setFunctionField(state, "exit") { context -> exit(context, state.config.exitHandler) }
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
        return LuaReturn.of(first.toDouble() - second.toDouble())
    }

    private fun execute(context: LuaCallContext): LuaReturn {
        if (context.isNone(1) || context.isNil(1)) {
            return LuaReturn.of(shellAvailable())
        }
        val command = requiredString(context, 1, "os.execute")
        val exitCode = try {
            ProcessBuilder(shellCommand(command))
                .inheritIO()
                .start()
                .waitFor()
        } catch (error: IOException) {
            return LuaReturn.of(null, error.message ?: error::class.java.simpleName, 1L)
        } catch (error: SecurityException) {
            return LuaReturn.of(null, error.message ?: error::class.java.simpleName, 1L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw LuaRuntimeException("interrupted while executing command")
        }
        return executeResult(exitCode)
    }

    private fun shellAvailable(): Boolean {
        return try {
            val process = ProcessBuilder(shellCommand("exit 0"))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            process.outputStream.close()
            process.waitFor() == 0
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw LuaRuntimeException("interrupted while probing command shell")
        }
    }

    private fun exit(context: LuaCallContext, exitHandler: LuaExitHandler): LuaReturn {
        val status = exitStatus(context)
        val closeState = !context.isNone(2) && context.toBoolean(2)
        exitHandler.exit(status, closeState)
        throw LuaExitException(status, closeState)
    }

    private fun exitStatus(context: LuaCallContext): Int {
        if (context.typeName(1) == "boolean") {
            return if (context.toBoolean(1)) EXIT_SUCCESS else EXIT_FAILURE
        }
        if (context.isNone(1) || context.isNil(1)) {
            return EXIT_SUCCESS
        }
        return requiredTime(context, 1, "os.exit").toInt()
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
        return fileResult(null) {
            if (isWindows()) {
                Files.move(Path.of(source), Path.of(target))
            } else {
                Files.move(Path.of(source), Path.of(target), StandardCopyOption.REPLACE_EXISTING)
            }
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
        return if (isDateTableFormat(body)) {
            LuaReturn.of(dateTimeFields(dateTime))
        } else {
            LuaReturn.of(formatDate(body, dateTime))
        }
    }

    private fun isDateTableFormat(format: String): Boolean {
        return format.length >= 2 &&
            format[0] == '*' &&
            format[1] == 't' &&
            (format.length == 2 || format[2] == '\u0000')
    }

    private fun fileResult(filename: String?, action: () -> Unit): LuaReturn {
        return try {
            action()
            LuaReturn.of(true)
        } catch (error: IOException) {
            LuaReturn.of(null, fileErrorMessage(filename, error), 1L)
        } catch (error: SecurityException) {
            LuaReturn.of(null, fileErrorMessage(filename, error), 1L)
        }
    }

    private fun fileErrorMessage(filename: String?, error: IOException): String {
        val reason = if (filename == null) {
            (error as? FileSystemException)?.reason ?: error::class.java.simpleName
        } else {
            (error as? FileSystemException)?.reason ?: error.message ?: error::class.java.simpleName
        }
        return if (filename == null) reason else "$filename: $reason"
    }

    private fun fileErrorMessage(filename: String?, error: SecurityException): String {
        val reason = error.message ?: error::class.java.simpleName
        return if (filename == null) reason else "$filename: $reason"
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

        val year = requiredDateField(context, "year", 1900)
        val month = requiredDateField(context, "month", 1)
        val day = requiredDateField(context, "day", 0)
        val hour = optionalDateField(context, "hour", 12L, 0)
        val minute = optionalDateField(context, "min", 0L, 0)
        val second = optionalDateField(context, "sec", 0L, 0)
        val requestedIsDst = optionalDateBooleanField(context, "isdst")

        val normalized = try {
            val localDateTime = LocalDateTime.of(javaYear(year), 1, 1, 0, 0, 0)
                .plusMonths(month - 1L)
                .plusDays(day - 1L)
                .plusHours(hour)
                .plusMinutes(minute)
                .plusSeconds(second)
            localDateTime.atSystemZone(requestedIsDst)
        } catch (_: DateTimeException) {
            throw LuaRuntimeException("time result cannot be represented in this installation")
        } catch (_: ArithmeticException) {
            throw LuaRuntimeException("time result cannot be represented in this installation")
        }

        val epochSecond = normalized.toEpochSecond()
        setDateField(context, "year", normalized.year.toLong())
        setDateField(context, "month", normalized.monthValue.toLong())
        setDateField(context, "day", normalized.dayOfMonth.toLong())
        setDateField(context, "hour", normalized.hour.toLong())
        setDateField(context, "min", normalized.minute.toLong())
        setDateField(context, "sec", normalized.second.toLong())
        setDateField(context, "wday", normalized.dayOfWeek.value % 7L + 1L)
        setDateField(context, "yday", normalized.dayOfYear.toLong())
        setDateField(context, "isdst", normalized.zone.rules.isDaylightSavings(normalized.toInstant()))
        if (epochSecond == -1L) {
            throw LuaRuntimeException("time result cannot be represented in this installation")
        }

        return LuaReturn.of(epochSecond)
    }

    private fun LocalDateTime.atSystemZone(requestedIsDst: Boolean?): ZonedDateTime {
        val zone = ZoneId.systemDefault()
        if (requestedIsDst == null) {
            return atZone(zone)
        }
        val matchingOffset = zone.rules.getValidOffsets(this).firstOrNull { offset ->
            zone.rules.isDaylightSavings(atOffset(offset).toInstant()) == requestedIsDst
        }
        return if (matchingOffset == null) {
            atZone(zone)
        } else {
            ZonedDateTime.ofLocal(this, zone, matchingOffset)
        }
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
            val specifierStart = index
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
            result.append(
                formatDateConversion(
                    dateTime,
                    modifier,
                    conversion,
                    invalidConversion = invalidDateConversionSuffix(format, specifierStart),
                ),
            )
            index++
        }
        return result.toString()
    }

    private fun formatDateConversion(
        dateTime: ZonedDateTime,
        modifier: Char?,
        conversion: Char,
        invalidConversion: String,
    ): String {
        if (!isValidDateAlternateConversion(modifier, conversion)) {
            throw invalidDateFormat(invalidConversion)
        }
        return when (conversion) {
            'a' -> dateTime.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()))
            'A' -> dateTime.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))
            'b' -> dateTime.format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault()))
            'B' -> dateTime.format(DateTimeFormatter.ofPattern("MMMM", Locale.getDefault()))
            'c' -> formatDate("%a %b %d %H:%M:%S %Y", dateTime)
            'C' -> twoDigits(java.lang.Math.floorDiv(dateTime.year, 100))
            'd' -> twoDigits(dateTime.dayOfMonth)
            'D' -> formatDate("%m/%d/%y", dateTime)
            'e' -> dateTime.dayOfMonth.toString().padStart(2, ' ')
            'F' -> formatDate("%Y-%m-%d", dateTime)
            'g' -> twoDigits(java.lang.Math.floorMod(isoWeekYear(dateTime), 100))
            'G' -> fourDigits(isoWeekYear(dateTime))
            'h' -> dateTime.format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault()))
            'H' -> twoDigits(dateTime.hour)
            'I' -> twoDigits(hour12(dateTime.hour))
            'j' -> dateTime.dayOfYear.toString().padStart(3, '0')
            'm' -> twoDigits(dateTime.monthValue)
            'M' -> twoDigits(dateTime.minute)
            'n' -> "\n"
            'p' -> dateTime.format(DateTimeFormatter.ofPattern("a", Locale.getDefault()))
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
            'Z' -> dateTime.format(DateTimeFormatter.ofPattern("z", Locale.getDefault()))
            '%' -> "%"
            else -> throw invalidDateFormat(invalidConversion)
        }
    }

    private fun invalidDateConversionSuffix(format: String, startIndex: Int): String {
        val endIndex = format.indexOf('\u0000', startIndex).takeIf { it >= 0 } ?: format.length
        return format.substring(startIndex, endIndex)
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
        return if (context.typeName(index) == "number") {
            luaNumberToString(context.get(index))
        } else {
            context.toString(index)
                ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (string expected)")
        }
    }

    private fun requiredDateField(context: LuaCallContext, key: String, delta: Int): Long {
        val value = dateTableField(context, key)
            ?: throw LuaRuntimeException("field '$key' missing in date table")
        val integer = luaInteger(value)
            ?: throw LuaRuntimeException("field '$key' is not an integer")
        return checkedDateField(key, integer, delta)
    }

    private fun optionalDateField(context: LuaCallContext, key: String, defaultValue: Long, delta: Int): Long {
        val value = dateTableField(context, key) ?: return defaultValue
        val integer = luaInteger(value)
            ?: throw LuaRuntimeException("field '$key' is not an integer")
        return checkedDateField(key, integer, delta)
    }

    private fun optionalDateBooleanField(context: LuaCallContext, key: String): Boolean? {
        val value = dateTableField(context, key) ?: return null
        return value != false
    }

    private fun dateTableField(context: LuaCallContext, key: String): Any? {
        return dateTableField(
            context,
            context.getLuaValue(1),
            context.getMetatable(1),
            key,
            java.util.Collections.newSetFromMap(java.util.IdentityHashMap()),
        )
    }

    private fun dateTableField(
        context: LuaCallContext,
        table: Any?,
        metatable: Any?,
        key: String,
        visited: MutableSet<Any>,
    ): Any? {
        if (table != null && !visited.add(table)) {
            throw LuaRuntimeException("'__index' chain too long; possible loop")
        }
        val rawValue = context.getTableField(table, key)
        if (rawValue != null) {
            return rawValue
        }
        val index = context.getTableField(metatable, "__index") ?: return null
        if (context.isFunctionValue(index)) {
            return context.call(index, listOf(table, key)).get(1)
        }
        if (context.isTableValue(index)) {
            return dateTableField(context, index, context.getTableMetatable(index), key, visited)
        }
        return try {
            context.getValueField(index, key)
        } catch (error: IllegalArgumentException) {
            throw LuaRuntimeException(error.message ?: "attempt to index a ${context.valueTypeName(index)} value")
        }
    }

    private fun setDateField(context: LuaCallContext, key: String, value: Any?) {
        setDateField(
            context,
            context.getLuaValue(1),
            context.getMetatable(1),
            key,
            value,
            java.util.Collections.newSetFromMap(java.util.IdentityHashMap()),
        )
    }

    private fun setDateField(
        context: LuaCallContext,
        table: Any?,
        metatable: Any?,
        key: String,
        value: Any?,
        visited: MutableSet<Any>,
    ) {
        if (table != null && !visited.add(table)) {
            throw LuaRuntimeException("'__newindex' chain too long; possible loop")
        }
        if (context.getTableField(table, key) != null) {
            context.setTableField(table, key, value)
            return
        }
        val newIndex = context.getTableField(metatable, "__newindex")
        if (newIndex == null) {
            context.setTableField(table, key, value)
            return
        }
        if (context.isTableValue(newIndex)) {
            setDateField(context, newIndex, context.getTableMetatable(newIndex), key, value, visited)
            return
        }
        if (context.isFunctionValue(newIndex)) {
            context.call(newIndex, listOf(table, key, value))
            return
        }
        try {
            context.setValueField(newIndex, key, value)
        } catch (error: IllegalArgumentException) {
            throw LuaRuntimeException(error.message ?: "attempt to index a ${context.valueTypeName(newIndex)} value")
        }
    }

    private fun checkedDateField(key: String, value: Long, delta: Int): Long {
        val fitsAdjustedInt = if (value >= 0) {
            value - delta <= Int.MAX_VALUE
        } else {
            Int.MIN_VALUE.toLong() + delta <= value
        }
        if (!fitsAdjustedInt) {
            throw LuaRuntimeException("field '$key' is out-of-bound")
        }
        return value
    }

    private fun javaYear(year: Long): Int {
        return try {
            java.lang.Math.toIntExact(year)
        } catch (_: ArithmeticException) {
            throw LuaRuntimeException("time result cannot be represented in this installation")
        }
    }

    private fun luaInteger(value: Any?): Long? {
        return when (value) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            is Float -> value.toDouble().luaInteger()
            is Double -> value.luaInteger()
            is CharSequence -> value.toString().trimLuaAsciiWhitespace().let { text ->
                val normalized = text.normalizeLuaNumberDecimalPoint()
                parseHexInteger(text) ?: normalized.toLongOrNull() ?: normalized.luaFloatFromString()?.luaInteger()
            }
            else -> null
        }
    }

    private fun String.luaFloatFromString(): Double? {
        val parseable = if (isHexNumeral() && indexOf('p', ignoreCase = true) < 0) "${this}p0" else this
        return parseable.toDoubleOrNull()
    }

    private fun String.normalizeLuaNumberDecimalPoint(): String {
        val decimalPoint = luaLocaleDecimalPoint()
        if (decimalPoint == '.' || decimalPoint !in this || '.' in this) {
            return this
        }
        return replace(decimalPoint, '.')
    }

    private fun String.trimLuaAsciiWhitespace(): String {
        return trim { char -> char == ' ' || char == '\u000C' || char == '\n' || char == '\r' || char == '\t' || char == '\u000B' }
    }

    private fun String.isHexNumeral(): Boolean {
        val digitsStart = if (startsWith("-") || startsWith("+")) 1 else 0
        return regionMatches(digitsStart, "0x", 0, 2, ignoreCase = true)
    }

    private fun parseHexInteger(text: String): Long? {
        val sign = when {
            text.startsWith("-") -> -1
            text.startsWith("+") -> 1
            else -> 1
        }
        val digitsStart = if (text.startsWith("-") || text.startsWith("+")) 1 else 0
        if (!text.regionMatches(digitsStart, "0x", 0, 2, ignoreCase = true)) {
            return null
        }
        val digits = text.substring(digitsStart + 2)
        if (digits.isEmpty() || digits.any { digit -> digit.asciiDigitToIntOrNull(16) == null }) {
            return null
        }
        var parsed = BigInteger.ZERO
        val radix = BigInteger.valueOf(16L)
        for (digit in digits) {
            val hexDigit = digit.asciiDigitToIntOrNull(16) ?: return null
            parsed = parsed.multiply(radix).add(BigInteger.valueOf(hexDigit.toLong()))
        }
        if (sign < 0) {
            parsed = parsed.negate()
        }
        return parsed.mod(UINT64_MODULUS).toLong()
    }

    private fun Char.asciiDigitToIntOrNull(base: Int): Int? {
        val value = when (this) {
            in '0'..'9' -> code - '0'.code
            in 'a'..'z' -> code - 'a'.code + 10
            in 'A'..'Z' -> code - 'A'.code + 10
            else -> return null
        }
        return value.takeIf { it < base }
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
        if (!isFinite() || this < Long.MIN_VALUE.toDouble() || this >= LUA_INTEGER_EXCLUSIVE_UPPER_BOUND) {
            return null
        }
        val integer = toLong()
        return if (integer.toDouble() == this) integer else null
    }

    private fun setFunctionField(state: LuaState, name: String, function: (LuaCallContext) -> LuaReturn) {
        state.pushFunction(function::invoke)
        state.setField(-2, name)
    }

    private const val EXIT_SUCCESS = 0
    private const val EXIT_FAILURE = 1
    private const val NANOS_PER_SECOND = 1_000_000_000.0
    private val LUA_INTEGER_EXCLUSIVE_UPPER_BOUND = -Long.MIN_VALUE.toDouble()
    private val UINT64_MODULUS: BigInteger = BigInteger.ONE.shiftLeft(Long.SIZE_BITS)
    private val LOCALE_CATEGORIES = setOf("all", "collate", "ctype", "monetary", "numeric", "time")
    private val LOCALE_TAG_PATTERN = Regex("[A-Za-z]{2,3}(-([A-Za-z]{2}|[0-9]{3}))?(-[A-Za-z0-9]+)*")
}
