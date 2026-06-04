package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaException
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState
import java.util.Locale
import java.util.function.Consumer

public object LuaStdlib {
    private const val FORMAT_CONVERSIONS = "diouxXfFeEgGcqs"

    @JvmStatic
    public fun openLibs(state: LuaState): LuaState {
        return openLibs(state, standardOutput())
    }

    @JvmStatic
    public fun openLibs(state: LuaState, output: Consumer<String>): LuaState {
        openBase(state, output)
        openMath(state)
        openString(state)
        openTable(state)
        openUtf8(state)
        return state
    }

    @JvmStatic
    public fun openBase(state: LuaState): LuaState {
        return openBase(state, standardOutput())
    }

    @JvmStatic
    public fun openBase(state: LuaState, output: Consumer<String>): LuaState {
        state.register("assert", ::assert)
        state.register("error", ::error)
        state.register("getmetatable", ::getmetatable)
        state.register("next", ::next)
        state.register("pcall", ::pcall)
        state.register("print") { context -> print(context, output) }
        state.register("rawequal", ::rawequal)
        state.register("rawget", ::rawget)
        state.register("rawset", ::rawset)
        state.register("select", ::select)
        state.register("setmetatable", ::setmetatable)
        state.register("tonumber", ::tonumber)
        state.register("tostring", ::tostring)
        state.register("type", ::type)
        state.register("xpcall", ::xpcall)
        installLuaSource(
            state,
            """
            pairs = function(tableValue)
                return next, tableValue, nil
            end
            ipairs = function(tableValue)
                return function(state, index)
                    local nextIndex = index + 1
                    local value = rawget(state, nextIndex)
                    if value == nil then
                        return nil
                    end
                    return nextIndex, value
                end, tableValue, 0
            end
            """.trimIndent(),
            "stdlib-base-iterators.lua",
        )
        return state
    }

    @JvmStatic
    public fun openMath(state: LuaState): LuaState {
        return LuaMathLibrary.open(state)
    }

    @JvmStatic
    public fun openString(state: LuaState): LuaState {
        state.newTable()
        setFunctionField(state, "byte", ::stringByte)
        setFunctionField(state, "char", ::stringChar)
        setFunctionField(state, "find", ::stringFind)
        setFunctionField(state, "format", ::stringFormat)
        setFunctionField(state, "gsub", ::stringGsub)
        setFunctionField(state, "len", ::stringLen)
        setFunctionField(state, "lower", ::stringLower)
        setFunctionField(state, "match", ::stringMatch)
        setFunctionField(state, "rep", ::stringRep)
        setFunctionField(state, "reverse", ::stringReverse)
        setFunctionField(state, "sub", ::stringSub)
        setFunctionField(state, "upper", ::stringUpper)
        state.setGlobal("string")
        installLuaSource(
            state,
            """
            string.gmatch = function(text, pattern)
                local init = 1
                local length = string.len(text)
                return function()
                    if init > length + 1 then
                        return nil
                    end
                    local first, last = string.find(text, pattern, init)
                    if first == nil then
                        return nil
                    end
                    if last < first then
                        init = first + 1
                    else
                        init = last + 1
                    end
                    return string.sub(text, first, last)
                end
            end
            """.trimIndent(),
            "stdlib-string-gmatch.lua",
        )
        return state
    }

    @JvmStatic
    public fun openTable(state: LuaState): LuaState {
        state.newTable()
        setFunctionField(state, "concat", ::tableConcat)
        setFunctionField(state, "insert", ::tableInsert)
        setFunctionField(state, "pack", ::tablePack)
        setFunctionField(state, "remove", ::tableRemove)
        setFunctionField(state, "unpack", ::tableUnpack)
        state.setGlobal("table")
        installLuaSource(
            state,
            """
            table.move = function(source, first, last, target, destination)
                if destination == nil then
                    destination = source
                end
                if first > last then
                    return destination
                end
                if source == destination and target > first and target <= last then
                    for offset = last - first, 0, -1 do
                        destination[target + offset] = source[first + offset]
                    end
                else
                    for offset = 0, last - first do
                        destination[target + offset] = source[first + offset]
                    end
                end
                return destination
            end
            """.trimIndent(),
            "stdlib-table-move.lua",
        )
        installLuaSource(
            state,
            """
            table.sort = function(values, comparator)
                local length = #values
                local function before(left, right)
                    if comparator == nil then
                        return left < right
                    end
                    return comparator(left, right)
                end
                for index = 2, length do
                    local value = values[index]
                    local cursor = index - 1
                    while cursor >= 1 and before(value, values[cursor]) do
                        values[cursor + 1] = values[cursor]
                        cursor = cursor - 1
                    end
                    values[cursor + 1] = value
                end
            end
            """.trimIndent(),
            "stdlib-table-sort.lua",
        )
        return state
    }

    @JvmStatic
    public fun openUtf8(state: LuaState): LuaState {
        return LuaUtf8Library.open(state)
    }

    private fun assert(context: LuaCallContext): LuaReturn {
        if (!context.toBoolean(1)) {
            throw LuaRuntimeException(context.toString(2) ?: "assertion failed!")
        }
        return LuaReturn.ofValues((1..context.argumentCount).map { index -> context.get(index) })
    }

    private fun error(context: LuaCallContext): LuaReturn {
        throw LuaRuntimeException(context.toString(1) ?: context.typeName(1))
    }

    private fun getmetatable(context: LuaCallContext): LuaReturn {
        if (!context.isTable(1)) {
            return LuaReturn.of(null)
        }
        return LuaReturn.of(context.getMetatable(1))
    }

    private fun next(context: LuaCallContext): LuaReturn {
        if (!context.isTable(1)) {
            throw LuaRuntimeException("bad argument #1 to 'next' (table expected)")
        }
        val key = if (context.isNone(2) || context.isNil(2)) {
            null
        } else {
            context.get(2)
        }
        return LuaReturn.ofValues(context.nextTableEntry(1, key) ?: listOf(null))
    }

    private fun pcall(context: LuaCallContext): LuaReturn {
        if (context.typeName(1) != "function") {
            throw LuaRuntimeException("bad argument #1 to 'pcall' (function expected)")
        }
        return protectedCall(context, functionIndex = 1, firstArgumentIndex = 2, handlerIndex = null)
    }

    private fun print(context: LuaCallContext, output: Consumer<String>): LuaReturn {
        output.accept((1..context.argumentCount).joinToString("\t") { index -> toLuaString(context, index) })
        return LuaReturn.none()
    }

    private fun rawequal(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(rawEqual(context, 1, 2))
    }

    private fun rawEqual(context: LuaCallContext, leftIndex: Int, rightIndex: Int): Boolean {
        val leftType = context.typeName(leftIndex)
        val rightType = context.typeName(rightIndex)
        if (leftType != rightType) {
            return false
        }
        return when (leftType) {
            "nil" -> true
            "boolean" -> context.toBoolean(leftIndex) == context.toBoolean(rightIndex)
            "number" -> context.toNumber(leftIndex) == context.toNumber(rightIndex)
            "string" -> context.toString(leftIndex) == context.toString(rightIndex)
            "table" -> context.getTable(leftIndex) === context.getTable(rightIndex)
            "userdata" -> context.toUserData(leftIndex) === context.toUserData(rightIndex)
            else -> false
        }
    }

    private fun rawget(context: LuaCallContext): LuaReturn {
        if (!context.isTable(1)) {
            throw LuaRuntimeException("bad argument #1 to 'rawget' (table expected)")
        }
        if (context.isNone(2) || context.isNil(2)) {
            throw LuaRuntimeException("table index is nil")
        }
        return LuaReturn.of(context.getTableValue(1, context.get(2)))
    }

    private fun rawset(context: LuaCallContext): LuaReturn {
        if (!context.isTable(1)) {
            throw LuaRuntimeException("bad argument #1 to 'rawset' (table expected)")
        }
        if (context.isNone(2) || context.isNil(2)) {
            throw LuaRuntimeException("table index is nil")
        }
        context.setTableValue(1, context.get(2), context.get(3))
        return LuaReturn.of(context.getTable(1))
    }

    private fun select(context: LuaCallContext): LuaReturn {
        if (context.toString(1) == "#") {
            return LuaReturn.of((context.argumentCount - 1).toLong())
        }

        val index = context.toInteger(1)
            ?: throw LuaRuntimeException("bad argument #1 to 'select' (number expected)")
        val start = when {
            index > 0L -> index + 1L
            index < 0L -> context.argumentCount + index + 1L
            else -> throw LuaRuntimeException("bad argument #1 to 'select' (index out of range)")
        }
        if (start < 2L || start > context.argumentCount.toLong()) {
            throw LuaRuntimeException("bad argument #1 to 'select' (index out of range)")
        }
        return LuaReturn.ofValues((start.toInt()..context.argumentCount).map { argument -> context.get(argument) })
    }

    private fun setmetatable(context: LuaCallContext): LuaReturn {
        if (!context.isTable(1)) {
            throw LuaRuntimeException("bad argument #1 to 'setmetatable' (table expected)")
        }
        if (!context.isNone(2) && !context.isNil(2) && !context.isTable(2)) {
            throw LuaRuntimeException("bad argument #2 to 'setmetatable' (nil or table expected)")
        }
        context.setMetatable(1, context.getTable(2))
        return LuaReturn.of(context.getTable(1))
    }

    private fun xpcall(context: LuaCallContext): LuaReturn {
        if (context.typeName(1) != "function") {
            throw LuaRuntimeException("bad argument #1 to 'xpcall' (function expected)")
        }
        if (context.typeName(2) != "function") {
            throw LuaRuntimeException("bad argument #2 to 'xpcall' (function expected)")
        }
        return protectedCall(context, functionIndex = 1, firstArgumentIndex = 3, handlerIndex = 2)
    }

    private fun tonumber(context: LuaCallContext): LuaReturn {
        val value = context.get(1) ?: return LuaReturn.of(null)
        if (!context.isNone(2) && !context.isNil(2)) {
            val base = context.toInteger(2)
                ?: throw LuaRuntimeException("bad argument #2 to 'tonumber' (number expected)")
            if (base !in 2L..36L) {
                throw LuaRuntimeException("bad argument #2 to 'tonumber' (base out of range)")
            }
            return when (value) {
                is CharSequence -> LuaReturn.of(value.toString().trim().toLongOrNull(base.toInt()))
                else -> LuaReturn.of(null)
            }
        }
        return when (value) {
            is Byte -> LuaReturn.of(value.toLong())
            is Short -> LuaReturn.of(value.toLong())
            is Int -> LuaReturn.of(value.toLong())
            is Long -> LuaReturn.of(value)
            is Float -> LuaReturn.of(value.toDouble())
            is Double -> LuaReturn.of(value)
            is CharSequence -> LuaReturn.of(parseNumber(value.toString()))
            else -> LuaReturn.of(null)
        }
    }

    private fun tostring(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(toLuaString(context, 1))
    }

    private fun type(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(context.typeName(1))
    }

    private fun protectedCall(
        context: LuaCallContext,
        functionIndex: Int,
        firstArgumentIndex: Int,
        handlerIndex: Int?,
    ): LuaReturn {
        return try {
            val result = context.call(functionIndex, (firstArgumentIndex..context.argumentCount).map { index -> argumentValue(context, index) })
            LuaReturn.ofValues(listOf(true) + result.values)
        } catch (exception: LuaException) {
            protectedCallError(context, exception.message ?: exception::class.java.simpleName, handlerIndex)
        } catch (exception: RuntimeException) {
            protectedCallError(context, exception.message ?: exception::class.java.simpleName, handlerIndex)
        }
    }

    private fun protectedCallError(context: LuaCallContext, message: String, handlerIndex: Int?): LuaReturn {
        if (handlerIndex == null) {
            return LuaReturn.of(false, message)
        }
        val handlerResult = context.call(handlerIndex, listOf(message))
        return LuaReturn.ofValues(listOf(false) + handlerResult.values)
    }

    private fun stringByte(context: LuaCallContext): LuaReturn {
        val text = requiredString(context, 1, "string.byte")
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
        val range = text.luaIndexRange(start, end)
        if (range.isEmpty()) {
            return LuaReturn.none()
        }
        return LuaReturn.ofValues(range.map { index -> text[index - 1].code.toLong() })
    }

    private fun stringChar(context: LuaCallContext): LuaReturn {
        val chars = StringBuilder()
        for (index in 1..context.argumentCount) {
            val code = requiredInteger(context, index, "string.char")
            if (code !in 0L..255L) {
                throw LuaRuntimeException("bad argument #$index to 'string.char' (value out of range)")
            }
            chars.append(code.toInt().toChar())
        }
        return LuaReturn.of(chars.toString())
    }

    private fun stringLen(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(requiredString(context, 1, "string.len").length.toLong())
    }

    private fun stringGsub(context: LuaCallContext): LuaReturn {
        val text = requiredString(context, 1, "string.gsub")
        val pattern = requiredString(context, 2, "string.gsub")
        val replacement = requiredString(context, 3, "string.gsub")
        val limit = if (context.isNone(4) || context.isNil(4)) {
            Long.MAX_VALUE
        } else {
            requiredInteger(context, 4, "string.gsub")
        }
        if (pattern.isEmpty()) {
            throw LuaRuntimeException("empty patterns are not supported")
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
            result.append(replacement)
            cursor = if (match.startIndex == match.endIndex && match.endIndex < text.length) {
                result.append(text[match.endIndex])
                match.endIndex + 1
            } else if (match.startIndex == match.endIndex) {
                match.endIndex + 1
            } else {
                match.endIndex
            }
            replacements++
        }
        if (cursor <= text.length) {
            result.append(text, cursor, text.length)
        }
        return LuaReturn.of(result.toString(), replacements)
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
        val startIndex = text.normalizeSearchStart(start) - 1
        val match = if (plain) {
            LuaStringPattern.literal(pattern).find(text, startIndex)
        } else {
            LuaStringPattern.compile(pattern).find(text, startIndex)
        }
        if (match == null) {
            return LuaReturn.of(null)
        }
        return LuaReturn.of(match.startIndex + 1L, match.endIndex.toLong())
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
            result.append(formatValue(context, argument, specifier, conversion))
            argument++
            cursor++
        }
        return LuaReturn.of(result.toString())
    }

    private fun stringLower(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(requiredString(context, 1, "string.lower").lowercase())
    }

    private fun stringMatch(context: LuaCallContext): LuaReturn {
        val text = requiredString(context, 1, "string.match")
        val pattern = requiredString(context, 2, "string.match")
        val start = if (context.isNone(3) || context.isNil(3)) {
            1L
        } else {
            requiredInteger(context, 3, "string.match")
        }
        val startIndex = text.normalizeSearchStart(start) - 1
        val match = LuaStringPattern.compile(pattern).find(text, startIndex)
        if (match == null) {
            return LuaReturn.of(null)
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
        if (count > Int.MAX_VALUE) {
            throw LuaRuntimeException("bad argument #2 to 'string.rep' (repeat count too large)")
        }
        return LuaReturn.of(
            buildString {
                repeat(count.toInt()) { index ->
                    if (index > 0) {
                        append(separator)
                    }
                    append(text)
                }
            },
        )
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
        return LuaReturn.of(text.substringByLuaRange(start, end))
    }

    private fun stringUpper(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(requiredString(context, 1, "string.upper").uppercase())
    }

    private fun tableConcat(context: LuaCallContext): LuaReturn {
        if (!context.isTable(1)) {
            throw LuaRuntimeException("bad argument #1 to 'table.concat' (table expected)")
        }

        val separator = if (context.isNone(2) || context.isNil(2)) {
            ""
        } else {
            requiredString(context, 2, "table.concat")
        }
        val start = if (context.isNone(3) || context.isNil(3)) {
            1L
        } else {
            requiredInteger(context, 3, "table.concat")
        }
        val end = if (context.isNone(4) || context.isNil(4)) {
            context.tableLength(1) ?: 0L
        } else {
            requiredInteger(context, 4, "table.concat")
        }

        if (start > end) {
            return LuaReturn.of("")
        }

        val builder = StringBuilder()
        var index = start
        while (index <= end) {
            if (index > start) {
                builder.append(separator)
            }
            builder.append(tableConcatValue(context, index))
            index++
        }
        return LuaReturn.of(builder.toString())
    }

    private fun tableConcatValue(context: LuaCallContext, index: Long): String {
        val value = try {
            context.getTableValue(1, index)
        } catch (_: IllegalArgumentException) {
            null
        }
        return when (value) {
            is Byte -> value.toLong().toString()
            is Short -> value.toLong().toString()
            is Int -> value.toLong().toString()
            is Long -> value.toString()
            is Float -> value.toDouble().toString()
            is Double -> value.toString()
            is CharSequence -> value.toString()
            else -> throw LuaRuntimeException("invalid value at index $index in table for 'concat'")
        }
    }

    private fun tableInsert(context: LuaCallContext): LuaReturn {
        if (!context.isTable(1)) {
            throw LuaRuntimeException("bad argument #1 to 'table.insert' (table expected)")
        }
        val length = context.tableLength(1) ?: 0L
        val position: Long
        val valueIndex: Int
        when (context.argumentCount) {
            2 -> {
                position = length + 1L
                valueIndex = 2
            }
            3 -> {
                position = requiredInteger(context, 2, "table.insert")
                valueIndex = 3
            }
            else -> throw LuaRuntimeException("wrong number of arguments to 'table.insert'")
        }
        if (position !in 1L..(length + 1L)) {
            throw LuaRuntimeException("bad argument #2 to 'table.insert' (position out of bounds)")
        }

        var index = length
        while (index >= position) {
            context.setTableValue(1, index + 1L, context.getTableValue(1, index))
            index--
        }
        context.setTableValue(1, position, context.get(valueIndex))
        return LuaReturn.none()
    }

    private fun tablePack(context: LuaCallContext): LuaReturn {
        val table = linkedMapOf<Any, Any?>("n" to context.argumentCount.toLong())
        for (index in 1..context.argumentCount) {
            table[index.toLong()] = context.get(index)
        }
        return LuaReturn.of(table)
    }

    private fun tableRemove(context: LuaCallContext): LuaReturn {
        if (!context.isTable(1)) {
            throw LuaRuntimeException("bad argument #1 to 'table.remove' (table expected)")
        }
        val length = context.tableLength(1) ?: 0L
        if (length == 0L && (context.isNone(2) || context.isNil(2))) {
            return LuaReturn.of(null)
        }
        val position = if (context.isNone(2) || context.isNil(2)) {
            length
        } else {
            requiredInteger(context, 2, "table.remove")
        }
        if (position < 1L || position > length) {
            return LuaReturn.of(null)
        }

        val removed = context.getTableValue(1, position)
        var index = position
        while (index < length) {
            context.setTableValue(1, index, context.getTableValue(1, index + 1L))
            index++
        }
        context.setTableValue(1, length, null)
        return LuaReturn.of(removed)
    }

    private fun tableUnpack(context: LuaCallContext): LuaReturn {
        if (!context.isTable(1)) {
            throw LuaRuntimeException("bad argument #1 to 'table.unpack' (table expected)")
        }

        val start = if (context.isNone(2) || context.isNil(2)) {
            1L
        } else {
            requiredInteger(context, 2, "table.unpack")
        }
        val end = if (context.isNone(3) || context.isNil(3)) {
            context.tableLength(1) ?: 0L
        } else {
            requiredInteger(context, 3, "table.unpack")
        }

        if (start > end) {
            return LuaReturn.none()
        }

        val values = mutableListOf<Any?>()
        var index = start
        while (index <= end) {
            values += try {
                context.getTableValue(1, index)
            } catch (_: IllegalArgumentException) {
                throw LuaRuntimeException("invalid value at index $index in table for 'unpack'")
            }
            index++
        }
        return LuaReturn.ofValues(values)
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
            's' -> specifier.formatWith(toLuaString(context, index))
            'd',
            'i',
            'o',
            'u',
            'x',
            'X',
            -> specifier.javaIntegerSpecifier(conversion).formatWith(requiredInteger(context, index, "string.format"))
            'f',
            'F',
            'e',
            'E',
            'g',
            'G',
            -> specifier.formatWith(requiredNumber(context, index, "string.format"))
            'c' -> {
                val code = requiredInteger(context, index, "string.format")
                if (code !in 0L..255L) {
                    throw LuaRuntimeException("bad argument #$index to 'string.format' (value out of range)")
                }
                code.toInt().toChar().toString()
            }
            'q' -> quoteValue(context, index)
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

    private fun quoteString(value: String): String {
        val result = StringBuilder("\"")
        for (char in value) {
            when (char) {
                '\\' -> result.append("\\\\")
                '"' -> result.append("\\\"")
                '\u0007' -> result.append("\\a")
                '\b' -> result.append("\\b")
                '\u000C' -> result.append("\\f")
                '\n' -> result.append("\\n")
                '\r' -> result.append("\\r")
                '\t' -> result.append("\\t")
                '\u000B' -> result.append("\\v")
                in '\u0000'..'\u001F',
                '\u007F',
                -> result.append("\\").append(char.code.toString().padStart(3, '0'))
                else -> result.append(char)
            }
        }
        return result.append('"').toString()
    }

    private fun quoteValue(context: LuaCallContext, index: Int): String {
        return when (context.typeName(index)) {
            "nil" -> "nil"
            "boolean" -> context.toBoolean(index).toString()
            "number" -> context.toString(index) ?: context.typeName(index)
            "string" -> quoteString(context.toString(index) ?: "")
            else -> throw LuaRuntimeException("bad argument #$index to 'string.format' (value has no literal form)")
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
            else -> context.typeName(index)
        }
    }

    private fun argumentValue(context: LuaCallContext, index: Int): Any? {
        return when (context.typeName(index)) {
            "table" -> context.getTable(index)
            else -> context.get(index)
        }
    }

    private fun parseNumber(text: String): Number? {
        val trimmed = text.trim()
        return trimmed.toLongOrNull() ?: trimmed.toDoubleOrNull()
    }

    private fun standardOutput(): Consumer<String> {
        return Consumer { line -> println(line) }
    }

    private fun setFunctionField(state: LuaState, name: String, function: (LuaCallContext) -> LuaReturn) {
        state.pushFunction(function::invoke)
        state.setField(-2, name)
    }

    private fun String.substringByLuaRange(start: Long, end: Long): String {
        val range = luaIndexRange(start, end)
        if (range.isEmpty()) {
            return ""
        }
        return substring(range.first - 1, range.last)
    }

    private fun String.luaIndexRange(start: Long, end: Long): IntRange {
        val normalizedStart = normalizeStringIndex(start)
        val normalizedEnd = normalizeStringIndex(end)
        if (normalizedStart > normalizedEnd) {
            return IntRange.EMPTY
        }
        val first = normalizedStart.coerceIn(1, length + 1)
        val last = normalizedEnd.coerceIn(0, length)
        if (first > last) {
            return IntRange.EMPTY
        }
        return first..last
    }

    private fun String.normalizeStringIndex(index: Long): Int {
        return when {
            index > Int.MAX_VALUE -> Int.MAX_VALUE
            index < Int.MIN_VALUE -> Int.MIN_VALUE
            index >= 0L -> index.toInt()
            else -> (length + index + 1L).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        }
    }

    private fun String.normalizeSearchStart(index: Long): Int {
        val normalized = when {
            index >= 0L -> index
            else -> length + index + 1L
        }
        return normalized.coerceIn(1L, length + 1L).toInt()
    }

}
