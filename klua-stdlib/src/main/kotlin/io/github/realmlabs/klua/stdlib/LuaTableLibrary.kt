package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaFunction
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState

internal object LuaTableLibrary {
    fun open(state: LuaState): LuaState {
        state.newTable()
        setFunctionField(state, "concat", ::tableConcat)
        setFunctionField(state, "create", ::tableCreate)
        setFunctionField(state, "insert", ::tableInsert)
        setFunctionField(state, "move", ::tableMove)
        setFunctionField(state, "pack", ::tablePack)
        setFunctionField(state, "remove", ::tableRemove)
        setFunctionField(state, "sort", ::tableSort)
        setFunctionField(state, "unpack", ::tableUnpack)
        state.setGlobal("table")
        return state
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
            else -> throw LuaRuntimeException(
                "invalid value (${tableConcatTypeName(value)}) at index $index in table for 'concat'",
            )
        }
    }

    private fun tableConcatTypeName(value: Any?): String {
        return when (value) {
            null -> "nil"
            is Boolean -> "boolean"
            is Number -> "number"
            is CharSequence -> "string"
            is LuaFunction -> "function"
            else -> "table"
        }
    }

    private fun tableCreate(context: LuaCallContext): LuaReturn {
        requiredNonNegativeInteger(context, 1, "table.create")
        if (!context.isNone(2) && !context.isNil(2)) {
            requiredNonNegativeInteger(context, 2, "table.create")
        }
        return LuaReturn.of(linkedMapOf<Any, Any?>())
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
        context.setTableValue(1, position, argumentValue(context, valueIndex))
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

        val count = tableMoveCount(first, last)
        tableMoveLastTarget(target, count)

        val sameTable = context.getTable(1) === context.getTable(destinationIndex)
        if (sameTable && target > first && target <= last) {
            var offset = count - 1L
            while (offset >= 0L) {
                context.setTableValue(destinationIndex, target + offset, context.getTableValue(1, first + offset))
                offset--
            }
        } else {
            var offset = 0L
            while (offset < count) {
                context.setTableValue(destinationIndex, target + offset, context.getTableValue(1, first + offset))
                offset++
            }
        }
        return LuaReturn.of(context.getTable(destinationIndex))
    }

    private fun tableMoveCount(first: Long, last: Long): Long {
        val span = try {
            java.lang.Math.subtractExact(last, first)
        } catch (_: ArithmeticException) {
            throw LuaRuntimeException("bad argument #3 to 'table.move' (too many elements to move)")
        }
        return try {
            java.lang.Math.addExact(span, 1L)
        } catch (_: ArithmeticException) {
            throw LuaRuntimeException("bad argument #3 to 'table.move' (too many elements to move)")
        }
    }

    private fun tableMoveLastTarget(target: Long, count: Long): Long {
        val offset = count - 1L
        return try {
            java.lang.Math.addExact(target, offset)
        } catch (_: ArithmeticException) {
            throw LuaRuntimeException("bad argument #4 to 'table.move' (destination wrap around)")
        }
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

    private fun tableSort(context: LuaCallContext): LuaReturn {
        if (!context.isTable(1)) {
            throw LuaRuntimeException("bad argument #1 to 'table.sort' (table expected)")
        }
        val hasComparator = !(context.isNone(2) || context.isNil(2))
        if (hasComparator && context.typeName(2) != "function") {
            throw LuaRuntimeException("bad argument #2 to 'table.sort' (function expected)")
        }

        val length = context.tableLength(1) ?: 0L
        val values = mutableListOf<Any?>()
        var index = 1L
        while (index <= length) {
            values += context.getTableValue(1, index)
            index++
        }

        for (currentIndex in 1 until values.size) {
            val value = values[currentIndex]
            var cursor = currentIndex - 1
            while (cursor >= 0 && tableSortBefore(context, value, values[cursor], hasComparator)) {
                values[cursor + 1] = values[cursor]
                cursor--
            }
            values[cursor + 1] = value
        }

        for (valueIndex in values.indices) {
            context.setTableValue(1, valueIndex.toLong() + 1L, values[valueIndex])
        }
        return LuaReturn.none()
    }

    private fun tableSortBefore(
        context: LuaCallContext,
        left: Any?,
        right: Any?,
        hasComparator: Boolean,
    ): Boolean {
        if (!hasComparator) {
            return compareTableSortValues(left, right) < 0
        }

        return context.call(2, listOf(left, right)).get(1).isLuaTruthy()
    }

    private fun compareTableSortValues(left: Any?, right: Any?): Int {
        val leftNumber = left.asLuaNumber()
        val rightNumber = right.asLuaNumber()
        if (leftNumber != null && rightNumber != null) {
            return leftNumber.compareTo(rightNumber)
        }
        if (left is CharSequence && right is CharSequence) {
            return left.toString().compareTo(right.toString())
        }
        throw LuaRuntimeException("attempt to compare ${tableSortTypeName(left)} with ${tableSortTypeName(right)}")
    }

    private fun Any?.asLuaNumber(): Double? {
        return when (this) {
            is Byte -> toDouble()
            is Short -> toDouble()
            is Int -> toDouble()
            is Long -> toDouble()
            is Float -> toDouble()
            is Double -> this
            else -> null
        }
    }

    private fun Any?.isLuaTruthy(): Boolean {
        return this != null && this != false
    }

    private fun tableSortTypeName(value: Any?): String {
        return when (value) {
            null -> "nil"
            is Boolean -> "boolean"
            is Number -> "number"
            is CharSequence -> "string"
            is LuaFunction -> "function"
            else -> "table"
        }
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

    private fun requiredNonNegativeInteger(context: LuaCallContext, index: Int, functionName: String): Long {
        val value = requiredInteger(context, index, functionName)
        if (value < 0) {
            throw LuaRuntimeException("bad argument #$index to '$functionName' (non-negative integer expected)")
        }
        return value
    }

    private fun argumentValue(context: LuaCallContext, index: Int): Any? {
        return when (context.typeName(index)) {
            "table" -> context.getTable(index)
            else -> context.get(index)
        }
    }

    private fun setFunctionField(state: LuaState, name: String, function: (LuaCallContext) -> LuaReturn) {
        state.pushFunction(function::invoke)
        state.setField(-2, name)
    }
}
