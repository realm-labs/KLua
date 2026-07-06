package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaFunction
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState
import io.github.realmlabs.klua.core.value.luaRawBytes
import java.math.BigInteger
import java.util.Locale

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
        if (!isReadableLengthTableLike(context, 1)) {
            throw LuaRuntimeException("bad argument #1 to 'concat' (table expected)")
        }
        val length = tableConcatLength(context, 1)

        val separator = if (context.isNone(2) || context.isNil(2)) {
            ""
        } else {
            requiredString(context, 2, "concat")
        }
        val start = if (context.isNone(3) || context.isNil(3)) {
            1L
        } else {
            requiredInteger(context, 3, "concat")
        }
        val end = if (context.isNone(4) || context.isNil(4)) {
            length
        } else {
            requiredInteger(context, 4, "concat")
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
            builder.append(tableConcatValue(context, 1, index))
            index++
        }
        return LuaReturn.of(builder.toString())
    }

    private fun isReadableLengthTableLike(context: LuaCallContext, index: Int): Boolean {
        if (context.isTable(index)) {
            return true
        }
        val metatable = context.getRawMetatable(index) ?: return false
        return context.getTableField(metatable, "__index") != null &&
            context.getTableField(metatable, "__len") != null
    }

    private fun isReadWriteLengthTableLike(context: LuaCallContext, index: Int): Boolean {
        if (context.isTable(index)) {
            return true
        }
        val metatable = context.getRawMetatable(index) ?: return false
        return context.getTableField(metatable, "__index") != null &&
            context.getTableField(metatable, "__newindex") != null &&
            context.getTableField(metatable, "__len") != null
    }

    private fun isReadableTableLike(context: LuaCallContext, index: Int): Boolean {
        if (context.isTable(index)) {
            return true
        }
        val metatable = context.getRawMetatable(index) ?: return false
        return context.getTableField(metatable, "__index") != null
    }

    private fun isWritableTableLike(context: LuaCallContext, index: Int): Boolean {
        if (context.isTable(index)) {
            return true
        }
        val metatable = context.getRawMetatable(index) ?: return false
        return context.getTableField(metatable, "__newindex") != null
    }

    private fun tableConcatLength(context: LuaCallContext, index: Int): Long {
        if (context.typeName(index) == "string") {
            return requiredString(context, index, "concat").luaRawBytes().size.toLong()
        }
        return tableLength(context, index)
    }

    private fun tableConcatValue(context: LuaCallContext, receiverIndex: Int, key: Long): String {
        val value = tableIndexValue(context, receiverIndex, key)
        return when (value) {
            is Byte -> value.toLong().toString()
            is Short -> value.toLong().toString()
            is Int -> value.toLong().toString()
            is Long -> value.toString()
            is Float -> luaFloatToString(value.toDouble())
            is Double -> luaFloatToString(value)
            is CharSequence -> value.toString()
            else -> throw LuaRuntimeException(
                "invalid value (${context.valueTypeName(value)}) at index $key in table for 'concat'",
            )
        }
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
        val formatted = String.format(Locale.ROOT, "%.15g", value).lowercase(Locale.ROOT)
        val exponentIndex = formatted.indexOf('e')
        return if (exponentIndex >= 0) {
            val mantissa = formatted.substring(0, exponentIndex).trimLuaFloatTrailingZeros()
            mantissa + formatted.substring(exponentIndex)
        } else {
            val decimal = formatted.trimLuaFloatTrailingZeros()
            if (value.isFiniteWholeNumber() && '.' !in decimal) "$decimal.0" else decimal
        }
    }

    private fun String.trimLuaFloatTrailingZeros(): String {
        if ('.' !in this) {
            return this
        }
        return trimEnd('0').trimEnd('.')
    }

    private fun Double.isFiniteWholeNumber(): Boolean {
        return isFinite() && this % 1.0 == 0.0
    }

    private fun tableIndexValue(context: LuaCallContext, key: Any?): Any? {
        return tableIndexValue(context, 1, key)
    }

    private fun tableIndexValue(context: LuaCallContext, receiverIndex: Int, key: Any?): Any? {
        return tableIndexValue(
            context,
            receiver = context.getLuaValue(receiverIndex),
            metatable = if (context.isTable(receiverIndex)) {
                context.getMetatable(receiverIndex)
            } else {
                context.getRawMetatable(receiverIndex)
            },
            key = key,
            visited = identitySet(),
        )
    }

    private fun tableIndexValue(
        context: LuaCallContext,
        receiver: Any?,
        metatable: Any?,
        key: Any?,
        visited: MutableSet<Any>,
    ): Any? {
        if (receiver != null && !visited.add(receiver)) {
            throw LuaRuntimeException("'__index' chain too long; possible loop")
        }
        val rawValue = context.getTableField(receiver, key)
        if (rawValue != null) {
            return rawValue
        }
        val index = context.getTableField(metatable, "__index") ?: return null
        return if (context.isFunctionValue(index)) {
            context.call(index, listOf(receiver, key)).get(1)
        } else if (context.isTableValue(index)) {
            tableIndexValue(context, index, context.getTableMetatable(index), key, visited)
        } else if (index is CharSequence) {
            null
        } else {
            throw LuaRuntimeException("attempt to index a ${context.valueTypeName(index)} value")
        }
    }

    private fun tableSetValue(context: LuaCallContext, tableIndex: Int, key: Any?, value: Any?) {
        tableSetValue(
            context,
            receiver = context.getLuaValue(tableIndex),
            metatable = if (context.isTable(tableIndex)) {
                context.getMetatable(tableIndex)
            } else {
                context.getRawMetatable(tableIndex)
            },
            key = key,
            value = value,
            visited = identitySet(),
        )
    }

    private fun tableSetValue(
        context: LuaCallContext,
        receiver: Any?,
        metatable: Any?,
        key: Any?,
        value: Any?,
        visited: MutableSet<Any>,
    ) {
        if (receiver != null && !visited.add(receiver)) {
            throw LuaRuntimeException("'__newindex' chain too long; possible loop")
        }
        if (context.getTableField(receiver, key) != null) {
            context.setTableField(receiver, key, value)
            return
        }

        val newIndex = context.getTableField(metatable, "__newindex")
        if (newIndex == null) {
            context.setTableField(receiver, key, value)
            return
        }

        if (context.isTableValue(newIndex)) {
            tableSetValue(context, newIndex, context.getTableMetatable(newIndex), key, value, visited)
            return
        }

        if (context.isFunctionValue(newIndex)) {
            context.call(newIndex, listOf(receiver, key, value))
            return
        }

        throw LuaRuntimeException("attempt to index a ${context.valueTypeName(newIndex)} value")
    }

    private fun identitySet(): MutableSet<Any> {
        return java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
    }

    private fun tableLength(context: LuaCallContext, index: Int): Long {
        val metatable = if (context.isTable(index)) {
            context.getMetatable(index)
        } else {
            context.getRawMetatable(index)
        }
        val length = context.getTableField(metatable, "__len")
            ?: return context.tableLength(index) ?: 0L
        val value = context.getLuaValue(index)
        return luaInteger(context.call(length, listOf(value, value)).get(1))
            ?: throw LuaRuntimeException("object length is not an integer")
    }

    private fun requireTableLike(
        context: LuaCallContext,
        index: Int,
        functionName: String,
        read: Boolean,
        write: Boolean,
        length: Boolean,
    ) {
        if (context.isTable(index)) {
            return
        }
        val metatable = try {
            context.getMetatable(index)
        } catch (_: IllegalArgumentException) {
            null
        }
        val hasRequiredMetamethods =
            (!read || context.getTableField(metatable, "__index") != null) &&
                (!write || context.getTableField(metatable, "__newindex") != null) &&
                (!length || context.getTableField(metatable, "__len") != null)
        if (!hasRequiredMetamethods) {
            throw LuaRuntimeException("bad argument #$index to '$functionName' (table expected)")
        }
    }

    private fun tableCreate(context: LuaCallContext): LuaReturn {
        val sequenceSize = requiredInteger(context, 1, "create")
        val recordSize = if (context.isNone(2) || context.isNil(2)) {
            0L
        } else {
            requiredInteger(context, 2, "create")
        }
        checkTableCreateSize(sequenceSize, 1)
        checkTableCreateSize(recordSize, 2)
        return LuaReturn.of(linkedMapOf<Any, Any?>())
    }

    private fun tableInsert(context: LuaCallContext): LuaReturn {
        if (!isReadWriteLengthTableLike(context, 1)) {
            throw LuaRuntimeException("bad argument #1 to 'insert' (table expected)")
        }
        val length = tableLength(context, 1)
        val firstEmpty = length + 1L
        val position: Long
        val valueIndex: Int
        when (context.argumentCount) {
            2 -> {
                position = firstEmpty
                valueIndex = 2
            }
            3 -> {
                position = requiredInteger(context, 2, "insert")
                valueIndex = 3
            }
            else -> throw LuaRuntimeException("wrong number of arguments to 'insert'")
        }
        if (!validTableInsertPosition(position, firstEmpty)) {
            throw LuaRuntimeException("bad argument #2 to 'insert' (position out of bounds)")
        }

        var index = firstEmpty
        while (index > position) {
            tableSetValue(context, 1, index, tableIndexValue(context, index - 1L))
            index--
        }
        tableSetValue(context, 1, position, argumentValue(context, valueIndex))
        return LuaReturn.none()
    }

    private fun validTableInsertPosition(position: Long, firstEmpty: Long): Boolean {
        return java.lang.Long.compareUnsigned(position - 1L, firstEmpty) < 0
    }

    private fun tablePack(context: LuaCallContext): LuaReturn {
        val table = linkedMapOf<Any, Any?>("n" to context.argumentCount.toLong())
        for (index in 1..context.argumentCount) {
            table[index.toLong()] = argumentValue(context, index)
        }
        return LuaReturn.of(table)
    }

    private fun tableMove(context: LuaCallContext): LuaReturn {
        val first = requiredInteger(context, 2, "move")
        val last = requiredInteger(context, 3, "move")
        val target = requiredInteger(context, 4, "move")
        val hasDestination = !(context.isNone(5) || context.isNil(5))
        if (!isReadableTableLike(context, 1)) {
            throw LuaRuntimeException("bad argument #1 to 'move' (table expected)")
        }
        val destinationIndex = if (!hasDestination) {
            1
        } else {
            if (!isWritableTableLike(context, 5)) {
                throw LuaRuntimeException("bad argument #5 to 'move' (table expected)")
            }
            5
        }

        if (first > last) {
            return LuaReturn.of(context.getLuaValue(destinationIndex))
        }

        val count = tableMoveCount(first, last)
        tableMoveLastTarget(target, count)

        val equalDestination = context.equal(1, destinationIndex)
            ?: (context.luaEquals(1, destinationIndex) == true)
        val sameTable = destinationIndex == 1 || equalDestination
        if (sameTable && target > first && target <= last) {
            var offset = count - 1L
            while (offset >= 0L) {
                tableSetValue(
                    context,
                    destinationIndex,
                    target + offset,
                    tableIndexValue(context, 1, first + offset),
                )
                offset--
            }
        } else {
            var offset = 0L
            while (offset < count) {
                tableSetValue(
                    context,
                    destinationIndex,
                    target + offset,
                    tableIndexValue(context, 1, first + offset),
                )
                offset++
            }
        }
        return LuaReturn.of(context.getLuaValue(destinationIndex))
    }

    private fun tableMoveCount(first: Long, last: Long): Long {
        val span = try {
            java.lang.Math.subtractExact(last, first)
        } catch (_: ArithmeticException) {
            throw LuaRuntimeException("bad argument #3 to 'move' (too many elements to move)")
        }
        return try {
            java.lang.Math.addExact(span, 1L)
        } catch (_: ArithmeticException) {
            throw LuaRuntimeException("bad argument #3 to 'move' (too many elements to move)")
        }
    }

    private fun tableMoveLastTarget(target: Long, count: Long): Long {
        val offset = count - 1L
        return try {
            java.lang.Math.addExact(target, offset)
        } catch (_: ArithmeticException) {
            throw LuaRuntimeException("bad argument #4 to 'move' (destination wrap around)")
        }
    }

    private fun tableRemove(context: LuaCallContext): LuaReturn {
        if (!isReadWriteLengthTableLike(context, 1)) {
            throw LuaRuntimeException("bad argument #1 to 'remove' (table expected)")
        }
        val length = tableLength(context, 1)
        val position = if (context.isNone(2) || context.isNil(2)) {
            length
        } else {
            requiredInteger(context, 2, "remove")
        }
        if (!validTableRemovePosition(position, length)) {
            throw LuaRuntimeException("bad argument #2 to 'remove' (position out of bounds)")
        }
        if (position > length || position == 0L) {
            val removed = tableIndexValue(context, position)
            tableSetValue(context, 1, position, null)
            return LuaReturn.of(removed)
        }

        val removed = tableIndexValue(context, position)
        var index = position
        while (index < length) {
            tableSetValue(context, 1, index, tableIndexValue(context, index + 1L))
            index++
        }
        tableSetValue(context, 1, index, null)
        return LuaReturn.of(removed)
    }

    private fun validTableRemovePosition(position: Long, length: Long): Boolean {
        return position == length || java.lang.Long.compareUnsigned(position - 1L, length) <= 0
    }

    private fun tableSort(context: LuaCallContext): LuaReturn {
        if (!isReadWriteLengthTableLike(context, 1)) {
            throw LuaRuntimeException("bad argument #1 to 'sort' (table expected)")
        }
        val length = tableLength(context, 1)
        if (length <= 1L) {
            return LuaReturn.none()
        }
        if (length >= Int.MAX_VALUE) {
            throw LuaRuntimeException("bad argument #1 to 'sort' (array too big)")
        }
        val hasComparator = !(context.isNone(2) || context.isNil(2))
        if (hasComparator && context.typeName(2) != "function") {
            throw LuaRuntimeException("bad argument #2 to 'sort' (function expected)")
        }

        tableSortRange(context, 1L, length, hasComparator)
        return LuaReturn.none()
    }

    private fun tableSortRange(context: LuaCallContext, lowIndex: Long, highIndex: Long, hasComparator: Boolean) {
        var low = lowIndex
        var high = highIndex
        while (low < high) {
            if (tableSortBefore(context, tableIndexValue(context, high), tableIndexValue(context, low), hasComparator)) {
                tableSortSwap(context, low, high)
            }
            if (high - low == 1L) {
                return
            }

            val pivotIndex = (low + high) / 2L
            if (tableSortBefore(context, tableIndexValue(context, pivotIndex), tableIndexValue(context, low), hasComparator)) {
                tableSortSwap(context, pivotIndex, low)
            } else if (tableSortBefore(
                    context,
                    tableIndexValue(context, high),
                    tableIndexValue(context, pivotIndex),
                    hasComparator,
                )
            ) {
                tableSortSwap(context, pivotIndex, high)
            }
            if (high - low == 2L) {
                return
            }

            val pivot = tableIndexValue(context, pivotIndex)
            tableSetValue(context, 1, pivotIndex, tableIndexValue(context, high - 1L))
            tableSetValue(context, 1, high - 1L, pivot)
            val partitionIndex = tableSortPartition(context, low, high, pivot, hasComparator)

            if (partitionIndex - low < high - partitionIndex) {
                tableSortRange(context, low, partitionIndex - 1L, hasComparator)
                low = partitionIndex + 1L
            } else {
                tableSortRange(context, partitionIndex + 1L, high, hasComparator)
                high = partitionIndex - 1L
            }
        }
    }

    private fun tableSortPartition(
        context: LuaCallContext,
        low: Long,
        high: Long,
        pivot: Any?,
        hasComparator: Boolean,
    ): Long {
        var left = low
        var right = high - 1L
        while (true) {
            do {
                left++
                val leftValue = tableIndexValue(context, left)
                if (!tableSortBefore(context, leftValue, pivot, hasComparator)) {
                    break
                }
                if (left == high - 1L) {
                    throw LuaRuntimeException("invalid order function for sorting")
                }
            } while (true)

            do {
                right--
                val rightValue = tableIndexValue(context, right)
                if (!tableSortBefore(context, pivot, rightValue, hasComparator)) {
                    break
                }
                if (right < left) {
                    throw LuaRuntimeException("invalid order function for sorting")
                }
            } while (true)

            if (right < left) {
                tableSetValue(context, 1, high - 1L, tableIndexValue(context, left))
                tableSetValue(context, 1, left, pivot)
                return left
            }

            tableSortSwap(context, left, right)
        }
    }

    private fun tableSortSwap(context: LuaCallContext, left: Long, right: Long) {
        val leftValue = tableIndexValue(context, left)
        tableSetValue(context, 1, left, tableIndexValue(context, right))
        tableSetValue(context, 1, right, leftValue)
    }

    private fun tableSortBefore(
        context: LuaCallContext,
        left: Any?,
        right: Any?,
        hasComparator: Boolean,
    ): Boolean {
        if (!hasComparator) {
            return context.lessThanValues(left, right) ?: tableSortLessThan(context, left, right)
        }

        return context.call(2, listOf(left, right)).get(1).isLuaTruthy()
    }

    private fun tableSortLessThan(context: LuaCallContext, left: Any?, right: Any?): Boolean {
        val leftInteger = left.luaIntegerSubtype()
        val rightInteger = right.luaIntegerSubtype()
        if (leftInteger != null) {
            return if (rightInteger != null) {
                leftInteger < rightInteger
            } else if (right.asLuaNumber() != null) {
                luaIntegerLessThanFloat(leftInteger, (right as Number).toDouble())
            } else {
                throw LuaRuntimeException(tableSortComparisonError(context, left, right))
            }
        }
        val leftNumber = left.asLuaNumber()
        if (leftNumber != null) {
            return if (rightInteger != null) {
                luaFloatLessThanInteger((left as Number).toDouble(), rightInteger)
            } else if (right.asLuaNumber() != null) {
                (left as Number).toDouble() < (right as Number).toDouble()
            } else {
                throw LuaRuntimeException(tableSortComparisonError(context, left, right))
            }
        }
        if (left is CharSequence && right is CharSequence) {
            return luaByteCompare(left.toString(), right.toString()) < 0
        }
        throw LuaRuntimeException(tableSortComparisonError(context, left, right))
    }

    private fun luaIntegerLessThanFloat(left: Long, right: Double): Boolean {
        val rightCeiling = right.luaCeilToInteger()
        return if (rightCeiling != null) left < rightCeiling else right > 0.0
    }

    private fun luaFloatLessThanInteger(left: Double, right: Long): Boolean {
        val leftFloor = left.luaFloorToInteger()
        return if (leftFloor != null) leftFloor < right else left < 0.0
    }

    private fun luaByteCompare(left: String, right: String): Int {
        val leftBytes = left.luaRawBytes()
        val rightBytes = right.luaRawBytes()
        val limit = minOf(leftBytes.size, rightBytes.size)
        for (index in 0 until limit) {
            val comparison = (leftBytes[index].toInt() and 0xff) - (rightBytes[index].toInt() and 0xff)
            if (comparison != 0) {
                return comparison
            }
        }
        return leftBytes.size - rightBytes.size
    }

    private fun Double.luaFloorToInteger(): Long? {
        if (!isFinite()) {
            return null
        }
        return kotlin.math.floor(this).luaIntegerInRange()
    }

    private fun Double.luaCeilToInteger(): Long? {
        if (!isFinite()) {
            return null
        }
        return kotlin.math.ceil(this).luaIntegerInRange()
    }

    private fun Double.luaIntegerInRange(): Long? {
        if (this < Long.MIN_VALUE.toDouble() || this >= LUA_INTEGER_EXCLUSIVE_UPPER_BOUND) {
            return null
        }
        return toLong()
    }

    private fun tableSortComparisonError(context: LuaCallContext, left: Any?, right: Any?): String {
        val leftType = context.valueTypeName(left)
        val rightType = context.valueTypeName(right)
        return if (leftType == rightType) {
            "attempt to compare two $leftType values"
        } else {
            "attempt to compare $leftType with $rightType"
        }
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

    private fun Any?.luaIntegerSubtype(): Long? {
        return when (this) {
            is Byte -> toLong()
            is Short -> toLong()
            is Int -> toLong()
            is Long -> this
            else -> null
        }
    }

    private fun Any?.isLuaTruthy(): Boolean {
        return this != null && this != false
    }

    private fun tableUnpack(context: LuaCallContext): LuaReturn {
        val sourceType = context.typeName(1)
        val start = if (context.isNone(2) || context.isNil(2)) {
            1L
        } else {
            requiredInteger(context, 2, "unpack")
        }
        val end = if (context.isNone(3) || context.isNil(3)) {
            tableUnpackDefaultEnd(context, sourceType)
        } else {
            requiredInteger(context, 3, "unpack")
        }

        if (start > end) {
            return LuaReturn.none()
        }
        tableUnpackResultCount(start, end)

        val values = mutableListOf<Any?>()
        var index = start
        while (index <= end) {
            values += tableUnpackIndexValue(context, sourceType, index)
            index++
        }
        return LuaReturn.ofValues(values)
    }

    private fun tableUnpackDefaultEnd(context: LuaCallContext, sourceType: String): Long {
        val length = context.getTableField(context.getMetatable(1), "__len")
        if (length != null) {
            return luaInteger(context.call(length, listOf(context.getLuaValue(1))).get(1))
                ?: throw LuaRuntimeException("object length is not an integer")
        }
        return when {
            context.isTable(1) -> context.tableLength(1) ?: 0L
            sourceType == "string" -> context.toString(1)?.luaRawBytes()?.size?.toLong() ?: 0L
            else -> throw LuaRuntimeException("attempt to get length of a $sourceType value")
        }
    }

    private fun tableUnpackIndexValue(context: LuaCallContext, sourceType: String, key: Any?): Any? {
        return tableUnpackIndexValue(
            context,
            context.getLuaValue(1),
            context.getMetatable(1),
            sourceType,
            key,
            identitySet(),
        )
    }

    private fun tableUnpackIndexValue(
        context: LuaCallContext,
        table: Any?,
        metatable: Any?,
        sourceType: String,
        key: Any?,
        visited: MutableSet<Any>,
    ): Any? {
        if (table != null && !visited.add(table)) {
            throw LuaRuntimeException("'__index' chain too long; possible loop")
        }
        val rawValue = context.getTableField(table, key)
        if (rawValue != null) {
            return rawValue
        }
        val index = context.getTableField(metatable, "__index")
        if (index == null) {
            if (context.isTableValue(table)) {
                return null
            }
            throw LuaRuntimeException("attempt to index a $sourceType value")
        }
        if (context.isTableValue(index)) {
            return tableUnpackIndexValue(context, index, context.getTableMetatable(index), sourceType, key, visited)
        }
        if (context.isFunctionValue(index)) {
            return context.call(index, listOf(table, key)).get(1)
        }
        return try {
            context.getValueField(index, key)
        } catch (error: IllegalArgumentException) {
            throw LuaRuntimeException(error.message ?: "attempt to index a ${context.valueTypeName(index)} value")
        }
    }

    private fun tableUnpackResultCount(start: Long, end: Long): Long {
        val span = try {
            java.lang.Math.subtractExact(end, start)
        } catch (_: ArithmeticException) {
            throw LuaRuntimeException("too many results to unpack")
        }
        val count = try {
            java.lang.Math.addExact(span, 1L)
        } catch (_: ArithmeticException) {
            throw LuaRuntimeException("too many results to unpack")
        }
        if (count >= Int.MAX_VALUE) {
            throw LuaRuntimeException("too many results to unpack")
        }
        return count
    }

    private fun requiredString(context: LuaCallContext, index: Int, functionName: String): String {
        return context.toString(index)
            ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (string expected)")
    }

    private fun requiredInteger(context: LuaCallContext, index: Int, functionName: String): Long {
        return context.toInteger(index)
            ?: if (context.toNumber(index) != null || context.typeName(index) == "number") {
                throw LuaRuntimeException("bad argument #$index to '$functionName' (number has no integer representation)")
            } else {
                throw LuaRuntimeException("bad argument #$index to '$functionName' (number expected)")
            }
    }

    private fun checkTableCreateSize(value: Long, index: Int) {
        if (value < 0 || value > Int.MAX_VALUE) {
            throw LuaRuntimeException("bad argument #$index to 'create' (out of range)")
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
                parseHexInteger(text) ?: text.toLongOrNull() ?: text.toDoubleOrNull()?.luaInteger()
            }
            else -> null
        }
    }

    private fun String.trimLuaAsciiWhitespace(): String {
        return trim { char -> char == ' ' || char == '\u000C' || char == '\n' || char == '\r' || char == '\t' || char == '\u000B' }
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
        if (digits.isEmpty() || digits.any { digit -> digit.digitToIntOrNull(16) == null }) {
            return null
        }
        var value = BigInteger.ZERO
        val radix = BigInteger.valueOf(16L)
        for (digit in digits) {
            value = value.multiply(radix).add(BigInteger.valueOf(digit.digitToInt(16).toLong()))
        }
        if (sign < 0) {
            value = value.negate()
        }
        return value.mod(UINT64_MODULUS).toLong()
    }

    private fun Double.luaInteger(): Long? {
        if (!isFinite() || this < Long.MIN_VALUE.toDouble() || this >= LUA_INTEGER_EXCLUSIVE_UPPER_BOUND) {
            return null
        }
        val integer = toLong()
        return if (integer.toDouble() == this) integer else null
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

    private val UINT64_MODULUS: BigInteger = BigInteger.ONE.shiftLeft(Long.SIZE_BITS)
    private val LUA_INTEGER_EXCLUSIVE_UPPER_BOUND = -Long.MIN_VALUE.toDouble()
}
