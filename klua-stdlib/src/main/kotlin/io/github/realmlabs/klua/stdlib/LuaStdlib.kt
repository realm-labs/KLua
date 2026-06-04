package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaException
import io.github.realmlabs.klua.api.LuaFunction
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState
import java.util.function.Consumer

public object LuaStdlib {
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
        state.register("ipairs", ::ipairs)
        state.register("next", ::next)
        state.register("pairs", ::pairs)
        state.register("pcall", ::pcall)
        state.register("print") { context -> print(context, output) }
        state.register("rawequal", ::rawequal)
        state.register("rawget", ::rawget)
        state.register("rawlen", ::rawlen)
        state.register("rawset", ::rawset)
        state.register("select", ::select)
        state.register("setmetatable", ::setmetatable)
        state.register("tonumber", ::tonumber)
        state.register("tostring", ::tostring)
        state.register("type", ::type)
        state.register("xpcall", ::xpcall)
        return state
    }

    @JvmStatic
    public fun openMath(state: LuaState): LuaState {
        return LuaMathLibrary.open(state)
    }

    @JvmStatic
    public fun openString(state: LuaState): LuaState {
        return LuaStringLibrary.open(state)
    }

    @JvmStatic
    public fun openTable(state: LuaState): LuaState {
        state.newTable()
        setFunctionField(state, "concat", ::tableConcat)
        setFunctionField(state, "insert", ::tableInsert)
        setFunctionField(state, "move", ::tableMove)
        setFunctionField(state, "pack", ::tablePack)
        setFunctionField(state, "remove", ::tableRemove)
        setFunctionField(state, "unpack", ::tableUnpack)
        state.setGlobal("table")
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
        return LuaReturn.ofValues((1..context.argumentCount).map { index -> argumentValue(context, index) })
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

    private fun ipairs(context: LuaCallContext): LuaReturn {
        if (!context.isTable(1)) {
            throw LuaRuntimeException("bad argument #1 to 'ipairs' (table expected)")
        }
        val iterator = LuaFunction { iteratorContext ->
            if (!iteratorContext.isTable(1)) {
                throw LuaRuntimeException("bad argument #1 to 'ipairs iterator' (table expected)")
            }
            val nextIndex = (iteratorContext.toInteger(2) ?: 0L) + 1L
            val value = iteratorContext.getTableValue(1, nextIndex)
            if (value == null) {
                LuaReturn.of(null)
            } else {
                LuaReturn.of(nextIndex, value)
            }
        }
        return LuaReturn.ofValues(listOf(iterator, context.getTable(1), 0L))
    }

    private fun next(context: LuaCallContext): LuaReturn {
        if (!context.isTable(1)) {
            throw LuaRuntimeException("bad argument #1 to 'next' (table expected)")
        }
        val key = if (context.isNone(2) || context.isNil(2)) {
            null
        } else {
            argumentValue(context, 2)
        }
        return LuaReturn.ofValues(context.nextTableEntry(1, key) ?: listOf(null))
    }

    private fun pairs(context: LuaCallContext): LuaReturn {
        if (!context.isTable(1)) {
            throw LuaRuntimeException("bad argument #1 to 'pairs' (table expected)")
        }
        return LuaReturn.ofValues(listOf(LuaFunction(::next), context.getTable(1), null))
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
        return LuaReturn.of(context.getTableValue(1, argumentValue(context, 2)))
    }

    private fun rawlen(context: LuaCallContext): LuaReturn {
        return when {
            context.typeName(1) == "string" -> LuaReturn.of(requiredString(context, 1, "rawlen").length.toLong())
            context.isTable(1) -> LuaReturn.of(context.tableLength(1) ?: 0L)
            else -> throw LuaRuntimeException("bad argument #1 to 'rawlen' (table or string expected)")
        }
    }

    private fun rawset(context: LuaCallContext): LuaReturn {
        if (!context.isTable(1)) {
            throw LuaRuntimeException("bad argument #1 to 'rawset' (table expected)")
        }
        if (context.isNone(2) || context.isNil(2)) {
            throw LuaRuntimeException("table index is nil")
        }
        context.setTableValue(1, argumentValue(context, 2), argumentValue(context, 3))
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
        return LuaReturn.ofValues((start.toInt()..context.argumentCount).map { argument -> argumentValue(context, argument) })
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
        val value = argumentValue(context, 1) ?: return LuaReturn.of(null)
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
            table[index.toLong()] = argumentValue(context, index)
        }
        return LuaReturn.of(table)
    }

    private fun tableMove(context: LuaCallContext): LuaReturn {
        if (!context.isTable(1)) {
            throw LuaRuntimeException("bad argument #1 to 'table.move' (table expected)")
        }
        val first = requiredInteger(context, 2, "table.move")
        val last = requiredInteger(context, 3, "table.move")
        val target = requiredInteger(context, 4, "table.move")
        val destinationIndex = if (context.isNone(5) || context.isNil(5)) {
            1
        } else {
            if (!context.isTable(5)) {
                throw LuaRuntimeException("bad argument #5 to 'table.move' (table expected)")
            }
            5
        }

        if (first > last) {
            return LuaReturn.of(context.getTable(destinationIndex))
        }

        val sameTable = context.getTable(1) === context.getTable(destinationIndex)
        if (sameTable && target > first && target <= last) {
            var offset = last - first
            while (offset >= 0L) {
                context.setTableValue(destinationIndex, target + offset, context.getTableValue(1, first + offset))
                offset--
            }
        } else {
            var offset = 0L
            while (offset <= last - first) {
                context.setTableValue(destinationIndex, target + offset, context.getTableValue(1, first + offset))
                offset++
            }
        }
        return LuaReturn.of(context.getTable(destinationIndex))
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
        val validPosition = position in 1L..length ||
            position == length + 1L ||
            (length == 0L && position == 0L)
        if (!validPosition) {
            throw LuaRuntimeException("bad argument #2 to 'table.remove' (position out of bounds)")
        }
        if (position > length || position == 0L) {
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

    private fun requiredInteger(context: LuaCallContext, index: Int, functionName: String): Long {
        return context.toInteger(index)
            ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (integer expected)")
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

}
