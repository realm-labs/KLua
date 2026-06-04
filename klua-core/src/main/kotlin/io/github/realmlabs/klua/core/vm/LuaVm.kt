package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.bytecode.Instruction
import io.github.realmlabs.klua.core.bytecode.OPEN_RESULT_COUNT
import io.github.realmlabs.klua.core.bytecode.Opcode
import io.github.realmlabs.klua.core.bytecode.Prototype
import io.github.realmlabs.klua.core.bytecode.UpvalueSource
import io.github.realmlabs.klua.core.value.LuaBoolean
import io.github.realmlabs.klua.core.value.LuaClosure
import io.github.realmlabs.klua.core.value.LuaFloat
import io.github.realmlabs.klua.core.value.LuaInteger
import io.github.realmlabs.klua.core.value.LuaNil
import io.github.realmlabs.klua.core.value.LuaNativeFunction
import io.github.realmlabs.klua.core.value.LuaString
import io.github.realmlabs.klua.core.value.LuaTable
import io.github.realmlabs.klua.core.value.LuaMetatableException
import io.github.realmlabs.klua.core.value.LuaTableKeyException
import io.github.realmlabs.klua.core.value.LuaUserData
import io.github.realmlabs.klua.core.value.LuaUpvalue
import io.github.realmlabs.klua.core.value.LuaValue

internal class LuaVm(
    private val globals: LuaTable = LuaTable(),
) {
    private val thread = LuaThread()

    fun execute(prototype: Prototype): List<LuaValue> {
        return executeReturned(prototype, emptyList(), emptyList())
    }

    fun execute(prototype: Prototype, arguments: List<LuaValue>): List<LuaValue> {
        return executeReturned(prototype, arguments, emptyList())
    }

    internal val activeCallDepth: Int
        get() = thread.callDepth

    internal val currentFrame: CallFrame?
        get() = thread.currentFrame

    internal fun call(callee: LuaValue, arguments: List<LuaValue>): List<LuaValue> {
        return returnedValues(callValue(callee, arguments))
    }

    internal fun executeYieldable(prototype: Prototype, arguments: List<LuaValue> = emptyList()): LuaExecutionResult {
        return executeFrame(prototype, arguments, emptyList())
    }

    private fun executeReturned(
        prototype: Prototype,
        arguments: List<LuaValue>,
        upvalues: List<LuaUpvalue>,
    ): List<LuaValue> {
        return returnedValues(executeFrame(prototype, arguments, upvalues))
    }

    private fun returnedValues(result: LuaExecutionResult): List<LuaValue> {
        return when (result) {
            is LuaExecutionResult.Returned -> result.values
            is LuaExecutionResult.Yielded -> {
                thread.clearFrames()
                throw LuaVmException("attempt to yield from outside a coroutine")
            }
        }
    }

    private fun executeFrame(
        prototype: Prototype,
        arguments: List<LuaValue>,
        upvalues: List<LuaUpvalue>,
    ): LuaExecutionResult {
        val frame = thread.pushCall(prototype, arguments, upvalues)
        val stack = frame.stack
        var yielded = false
        try {
            while (frame.pc < prototype.code.size) {
                val instruction = prototype.code[frame.pc++]
                when (Instruction.opcode(instruction)) {
                    Opcode.LOAD_NIL -> stack.set(register(frame, Instruction.a(instruction)), LuaNil)
                    Opcode.LOAD_BOOL -> {
                        stack.set(register(frame, Instruction.a(instruction)), LuaBoolean(Instruction.b(instruction) != 0))
                    }
                    Opcode.LOAD_INT -> {
                        stack.set(register(frame, Instruction.a(instruction)), LuaInteger(signedByte(Instruction.b(instruction)).toLong()))
                    }
                    Opcode.LOAD_FLOAT -> {
                        val constant = constant(prototype, Instruction.b(instruction))
                        if (constant !is LuaFloat) {
                            throw LuaVmException("LOAD_FLOAT expected float constant at K${Instruction.b(instruction)}")
                        }
                        stack.set(register(frame, Instruction.a(instruction)), constant)
                    }
                    Opcode.LOAD_K -> stack.set(register(frame, Instruction.a(instruction)), constant(prototype, Instruction.b(instruction)))
                    Opcode.VARARG -> loadVarargs(stack, frame, instruction)
                    Opcode.NEW_TABLE -> stack.set(register(frame, Instruction.a(instruction)), LuaTable())
                    Opcode.GET_TABLE -> getTable(stack, frame, instruction)
                    Opcode.SET_TABLE -> setTable(stack, frame, instruction)
                    Opcode.GET_FIELD -> getField(stack, frame, instruction)
                    Opcode.SET_FIELD -> setField(stack, frame, instruction)
                    Opcode.GET_GLOBAL -> getGlobal(stack, frame, instruction)
                    Opcode.SET_GLOBAL -> setGlobal(stack, frame, instruction)
                    Opcode.CLOSURE -> createClosure(stack, frame, instruction)
                    Opcode.GET_UPVALUE -> getUpvalue(stack, frame, instruction)
                    Opcode.SET_UPVALUE -> setUpvalue(stack, frame, instruction)
                    Opcode.CLOSE_UPVALUES -> stack.closeCapturesFrom(register(frame, Instruction.a(instruction)))
                    Opcode.MOVE -> stack.copy(register(frame, Instruction.b(instruction)), register(frame, Instruction.a(instruction)))
                    Opcode.ADD -> arithmetic(stack, frame, instruction, Arithmetic.ADD)
                    Opcode.SUB -> arithmetic(stack, frame, instruction, Arithmetic.SUB)
                    Opcode.MUL -> arithmetic(stack, frame, instruction, Arithmetic.MUL)
                    Opcode.DIV -> arithmetic(stack, frame, instruction, Arithmetic.DIV)
                    Opcode.IDIV -> arithmetic(stack, frame, instruction, Arithmetic.IDIV)
                    Opcode.MOD -> arithmetic(stack, frame, instruction, Arithmetic.MOD)
                    Opcode.POW -> arithmetic(stack, frame, instruction, Arithmetic.POW)
                    Opcode.CONCAT -> concat(stack, frame, instruction)
                    Opcode.BAND -> bitwise(stack, frame, instruction, Bitwise.AND)
                    Opcode.BOR -> bitwise(stack, frame, instruction, Bitwise.OR)
                    Opcode.BXOR -> bitwise(stack, frame, instruction, Bitwise.XOR)
                    Opcode.SHL -> bitwise(stack, frame, instruction, Bitwise.SHIFT_LEFT)
                    Opcode.SHR -> bitwise(stack, frame, instruction, Bitwise.SHIFT_RIGHT)
                    Opcode.BNOT -> bitwiseNot(stack, frame, instruction)
                    Opcode.LEN -> length(stack, frame, instruction)
                    Opcode.UNM -> unaryMinus(stack, frame, instruction)
                    Opcode.NOT -> logicalNot(stack, frame, instruction)
                    Opcode.EQ -> compare(stack, frame, instruction, Comparison.EQ)
                    Opcode.LT -> compare(stack, frame, instruction, Comparison.LT)
                    Opcode.LE -> compare(stack, frame, instruction, Comparison.LE)
                    Opcode.TEST -> {
                        if (!isTruthy(stack.get(register(frame, Instruction.a(instruction))))) {
                            frame.pc += signedByte(Instruction.b(instruction))
                        }
                    }
                    Opcode.JMP -> frame.pc += signedByte(Instruction.a(instruction))
                    Opcode.FOR_TEST -> {
                        if (!forLoopContinues(stack, frame, instruction)) {
                            frame.pc += signedByte(Instruction.b(instruction))
                        }
                    }
                    Opcode.FOR_LOOP -> {
                        incrementForIndex(stack, frame, instruction)
                        if (forLoopContinues(stack, frame, instruction)) {
                            frame.pc += signedByte(Instruction.b(instruction))
                        }
                    }
                    Opcode.CALL -> {
                        val result = call(stack, frame, instruction)
                        if (result != null) {
                            yielded = true
                            return result
                        }
                    }
                    Opcode.RETURN -> {
                        val base = register(frame, Instruction.a(instruction))
                        val count = returnCount(frame, base, Instruction.b(instruction))
                        return LuaExecutionResult.Returned(stack.slice(base, count))
                    }
                }
            }

            throw LuaVmException("prototype completed without RETURN")
        } finally {
            if (!yielded) {
                thread.popFrame(frame)
            }
        }
    }

    private fun register(frame: CallFrame, offset: Int): Int = frame.base + offset

    private fun constant(prototype: Prototype, index: Int): LuaValue {
        if (index !in prototype.constants.indices) {
            throw LuaVmException("constant index out of range: K$index")
        }
        return prototype.constants[index]
    }

    private fun nested(prototype: Prototype, index: Int): Prototype {
        if (index !in prototype.nested.indices) {
            throw LuaVmException("nested prototype index out of range: P$index")
        }
        return prototype.nested[index]
    }

    private fun call(stack: LuaStack, frame: CallFrame, instruction: Int): LuaExecutionResult? {
        val base = register(frame, Instruction.a(instruction))
        val callee = stack.get(base)
        val arguments = stack.slice(base + 1, argumentCount(frame, base, Instruction.b(instruction)))
        val results = callValue(callee, arguments)
        if (results is LuaExecutionResult.Yielded) {
            frame.pendingCallResultBase = base
            frame.pendingCallExpectedResults = Instruction.c(instruction)
            return results
        }
        val expectedResults = Instruction.c(instruction)
        val returnedValues = (results as LuaExecutionResult.Returned).values
        if (expectedResults == OPEN_RESULT_COUNT) {
            setOpenResults(stack, frame, base, returnedValues)
            return null
        }

        for (index in 0 until expectedResults) {
            stack.set(base + index, returnedValues.getOrElse(index) { LuaNil })
        }
        return null
    }

    private fun callValue(callee: LuaValue, arguments: List<LuaValue>): LuaExecutionResult {
        return when (callee) {
            is LuaClosure -> executeFrame(callee.prototype, arguments, callee.upvalues)
            is LuaNativeFunction -> callNativeResult(callee, arguments)
            is LuaTable -> {
                val call = callee.metatableRawGet(CALL_KEY)
                when (call) {
                    is LuaClosure -> executeFrame(call.prototype, listOf(callee) + arguments, call.upvalues)
                    is LuaNativeFunction -> callNativeResult(call, listOf(callee) + arguments)
                    else -> throw LuaVmException("attempt to call table")
                }
            }
            else -> throw LuaVmException("attempt to call ${typeName(callee)}")
        }
    }

    private fun callNative(function: LuaNativeFunction, arguments: List<LuaValue>): List<LuaValue> {
        return returnedValues(callNativeResult(function, arguments))
    }

    private fun callNativeResult(function: LuaNativeFunction, arguments: List<LuaValue>): LuaExecutionResult {
        return try {
            LuaExecutionResult.Returned(
                thread.runNativeCall {
                    function.function(arguments)
                },
            )
        } catch (yield: LuaYieldSignal) {
            LuaExecutionResult.Yielded(yield.values)
        }
    }

    private fun loadVarargs(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val base = register(frame, Instruction.a(instruction))
        val expectedResults = Instruction.b(instruction)
        if (expectedResults == OPEN_RESULT_COUNT) {
            setOpenResults(stack, frame, base, frame.varargs)
            return
        }

        for (index in 0 until expectedResults) {
            stack.set(base + index, frame.varargs.getOrElse(index) { LuaNil })
        }
    }

    private fun createClosure(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val prototype = nested(frame.prototype, Instruction.b(instruction))
        val upvalues = prototype.upvalues.map { descriptor ->
            when (descriptor.source) {
                UpvalueSource.LOCAL -> stack.capture(register(frame, descriptor.sourceIndex))
                UpvalueSource.UPVALUE -> {
                    val index = descriptor.sourceIndex
                    if (index !in frame.upvalues.indices) {
                        throw LuaVmException("upvalue index out of range: U$index")
                    }
                    frame.upvalues[index]
                }
            }
        }
        stack.set(register(frame, Instruction.a(instruction)), LuaClosure(prototype, upvalues))
    }

    private fun getUpvalue(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val index = Instruction.b(instruction)
        if (index !in frame.upvalues.indices) {
            throw LuaVmException("upvalue index out of range: U$index")
        }
        stack.set(register(frame, Instruction.a(instruction)), frame.upvalues[index].value)
    }

    private fun setUpvalue(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val index = Instruction.a(instruction)
        if (index !in frame.upvalues.indices) {
            throw LuaVmException("upvalue index out of range: U$index")
        }
        frame.upvalues[index].value = stack.get(register(frame, Instruction.b(instruction)))
    }

    private fun getTable(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val receiver = stack.get(register(frame, Instruction.b(instruction)))
        val key = stack.get(register(frame, Instruction.c(instruction)))
        stack.set(register(frame, Instruction.a(instruction)), indexGet(receiver, key))
    }

    private fun setTable(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val receiver = stack.get(register(frame, Instruction.a(instruction)))
        val key = stack.get(register(frame, Instruction.b(instruction)))
        val value = stack.get(register(frame, Instruction.c(instruction)))
        indexSet(receiver, key, value)
    }

    private fun getField(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val receiver = stack.get(register(frame, Instruction.b(instruction)))
        val key = stringConstant(frame.prototype, Instruction.c(instruction))
        stack.set(register(frame, Instruction.a(instruction)), indexGet(receiver, key))
    }

    private fun setField(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val receiver = stack.get(register(frame, Instruction.a(instruction)))
        val key = stringConstant(frame.prototype, Instruction.b(instruction))
        val value = stack.get(register(frame, Instruction.c(instruction)))
        indexSet(receiver, key, value)
    }

    private fun getGlobal(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val key = stringConstant(frame.prototype, Instruction.b(instruction))
        stack.set(register(frame, Instruction.a(instruction)), tableGet(globals, key))
    }

    private fun setGlobal(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val key = stringConstant(frame.prototype, Instruction.a(instruction))
        val value = stack.get(register(frame, Instruction.b(instruction)))
        tableSet(globals, key, value)
    }

    private fun tableGet(table: LuaTable, key: LuaValue): LuaValue {
        return try {
            tableGet(table, key, mutableSetOf())
        } catch (error: LuaTableKeyException) {
            throw LuaVmException(error.message ?: "invalid table key")
        } catch (error: LuaMetatableException) {
            throw LuaVmException(error.message ?: "invalid metatable operation")
        }
    }

    private fun indexGet(receiver: LuaValue, key: LuaValue): LuaValue {
        return when (receiver) {
            is LuaTable -> tableGet(receiver, key)
            is LuaUserData -> {
                if (key is LuaString) {
                    val property = receiver.type?.property(key.value)
                    if (property?.getter != null) {
                        callNative(property.getter, listOf(receiver)).firstOrNull() ?: LuaNil
                    } else {
                        receiver.type?.method(key.value) ?: LuaNil
                    }
                } else {
                    LuaNil
                }
            }
            else -> throw LuaVmException("attempt to index ${typeName(receiver)}")
        }
    }

    private fun tableGet(table: LuaTable, key: LuaValue, visited: MutableSet<LuaTable>): LuaValue {
        val value = table.rawGet(key)
        if (value != LuaNil) {
            return value
        }
        if (!visited.add(table)) {
            throw LuaMetatableException("cycle in __index chain")
        }

        return when (val index = table.metatableRawGet(INDEX_KEY)) {
            is LuaTable -> tableGet(index, key, visited)
            is LuaClosure -> executeReturned(index.prototype, listOf(table, key), index.upvalues).firstOrNull() ?: LuaNil
            else -> LuaNil
        }
    }

    private fun indexSet(receiver: LuaValue, key: LuaValue, value: LuaValue) {
        when (receiver) {
            is LuaTable -> tableSet(receiver, key, value)
            is LuaUserData -> {
                if (key !is LuaString) {
                    throw LuaVmException("attempt to index userdata with ${typeName(key)}")
                }
                val setter = receiver.type?.property(key.value)?.setter
                    ?: throw LuaVmException("attempt to set userdata field '${key.value}'")
                callNative(setter, listOf(receiver, value))
            }
            else -> throw LuaVmException("attempt to index ${typeName(receiver)}")
        }
    }

    private fun stringConstant(prototype: Prototype, index: Int): LuaString {
        val constant = constant(prototype, index)
        if (constant !is LuaString) {
            throw LuaVmException("field opcode expected string constant at K$index")
        }
        return constant
    }

    private fun tableSet(table: LuaTable, key: LuaValue, value: LuaValue) {
        try {
            tableSet(table, key, value, mutableSetOf())
        } catch (error: LuaTableKeyException) {
            throw LuaVmException(error.message ?: "invalid table key")
        } catch (error: LuaMetatableException) {
            throw LuaVmException(error.message ?: "invalid metatable operation")
        }
    }

    private fun tableSet(table: LuaTable, key: LuaValue, value: LuaValue, visited: MutableSet<LuaTable>) {
        if (table.rawGet(key) != LuaNil) {
            table.rawSet(key, value)
            return
        }
        if (!visited.add(table)) {
            throw LuaMetatableException("cycle in __newindex chain")
        }

        when (val newIndex = table.metatableRawGet(NEW_INDEX_KEY)) {
            is LuaTable -> tableSet(newIndex, key, value, visited)
            is LuaClosure -> executeReturned(newIndex.prototype, listOf(table, key, value), newIndex.upvalues)
            else -> table.rawSet(key, value)
        }
    }

    private fun setOpenResults(stack: LuaStack, frame: CallFrame, base: Int, results: List<LuaValue>) {
        for ((index, result) in results.withIndex()) {
            stack.set(base + index, result)
        }
        frame.openResultBase = base
        frame.openResultCount = results.size
    }

    private fun returnCount(frame: CallFrame, base: Int, count: Int): Int {
        if (count != OPEN_RESULT_COUNT) {
            return count
        }
        if (frame.openResultBase < base) {
            throw LuaVmException("open return result base is before return base")
        }
        return frame.openResultBase - base + frame.openResultCount
    }

    private fun argumentCount(frame: CallFrame, base: Int, count: Int): Int {
        if (count != OPEN_RESULT_COUNT) {
            return count
        }
        val argumentBase = base + 1
        if (frame.openResultBase < argumentBase) {
            throw LuaVmException("open argument result base is before argument base")
        }
        return frame.openResultBase - argumentBase + frame.openResultCount
    }

    private fun signedByte(value: Int): Int = if (value >= 128) value - 256 else value

    private fun arithmetic(stack: LuaStack, frame: CallFrame, instruction: Int, operation: Arithmetic) {
        val left = stack.get(register(frame, Instruction.b(instruction)))
        val right = stack.get(register(frame, Instruction.c(instruction)))
        stack.set(register(frame, Instruction.a(instruction)), arithmetic(left, right, operation))
    }

    private fun arithmetic(left: LuaValue, right: LuaValue, operation: Arithmetic): LuaValue {
        val metamethod = arithmeticMetamethod(left, right, operation)
        if (metamethod != null) {
            return executeReturned(metamethod.prototype, listOf(left, right), metamethod.upvalues).firstOrNull() ?: LuaNil
        }
        return operation.apply(left, right)
    }

    private fun arithmeticMetamethod(left: LuaValue, right: LuaValue, operation: Arithmetic): LuaClosure? {
        val key = when (operation) {
            Arithmetic.ADD -> ADD_KEY
            Arithmetic.SUB -> SUB_KEY
            Arithmetic.MUL -> MUL_KEY
            Arithmetic.DIV -> DIV_KEY
            Arithmetic.IDIV -> IDIV_KEY
            Arithmetic.MOD -> MOD_KEY
            Arithmetic.POW -> POW_KEY
        }
        return tableMetamethod(left, key) ?: tableMetamethod(right, key)
    }

    private fun tableMetamethod(value: LuaValue, key: LuaString): LuaClosure? {
        if (value !is LuaTable) {
            return null
        }
        return value.metatableRawGet(key) as? LuaClosure
    }

    private fun unaryMinus(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val value = stack.get(register(frame, Instruction.b(instruction)))
        val result = when (value) {
            is LuaInteger -> LuaInteger(-value.value)
            is LuaFloat -> LuaFloat(-value.value)
            else -> {
                val metamethod = tableMetamethod(value, UNM_KEY)
                if (metamethod != null) {
                    executeReturned(metamethod.prototype, listOf(value), metamethod.upvalues).firstOrNull() ?: LuaNil
                } else {
                    throw LuaVmException("attempt to perform arithmetic on ${typeName(value)}")
                }
            }
        }
        stack.set(register(frame, Instruction.a(instruction)), result)
    }

    private fun logicalNot(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val value = stack.get(register(frame, Instruction.b(instruction)))
        stack.set(register(frame, Instruction.a(instruction)), LuaBoolean(!isTruthy(value)))
    }

    private fun length(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val value = stack.get(register(frame, Instruction.b(instruction)))
        val result = when (value) {
            is LuaString -> LuaInteger(value.value.encodeToByteArray().size.toLong())
            is LuaTable -> tableLength(value)
            else -> throw LuaVmException("attempt to get length of ${typeName(value)}")
        }
        stack.set(register(frame, Instruction.a(instruction)), result)
    }

    private fun tableLength(table: LuaTable): LuaValue {
        return when (val length = table.metatableRawGet(LEN_KEY)) {
            is LuaClosure -> executeReturned(length.prototype, listOf(table), length.upvalues).firstOrNull() ?: LuaNil
            else -> LuaInteger(table.rawLength())
        }
    }

    private fun compare(stack: LuaStack, frame: CallFrame, instruction: Int, comparison: Comparison) {
        val left = stack.get(register(frame, Instruction.b(instruction)))
        val right = stack.get(register(frame, Instruction.c(instruction)))
        stack.set(register(frame, Instruction.a(instruction)), LuaBoolean(compare(left, right, comparison)))
    }

    private fun compare(left: LuaValue, right: LuaValue, comparison: Comparison): Boolean {
        if (comparison == Comparison.EQ) {
            if (comparison.apply(left, right)) {
                return true
            }
            if (left !is LuaTable || right !is LuaTable) {
                return false
            }
            val metamethod = tableMetamethod(left, EQ_KEY) ?: tableMetamethod(right, EQ_KEY)
            if (metamethod != null) {
                val result = executeReturned(metamethod.prototype, listOf(left, right), metamethod.upvalues).firstOrNull() ?: LuaNil
                return isTruthy(result)
            }
            return false
        }
        if (comparison == Comparison.LT) {
            val metamethod = tableMetamethod(left, LT_KEY) ?: tableMetamethod(right, LT_KEY)
            if (metamethod != null) {
                val result = executeReturned(metamethod.prototype, listOf(left, right), metamethod.upvalues).firstOrNull() ?: LuaNil
                return isTruthy(result)
            }
        }
        if (comparison == Comparison.LE) {
            val metamethod = tableMetamethod(left, LE_KEY) ?: tableMetamethod(right, LE_KEY)
            if (metamethod != null) {
                val result = executeReturned(metamethod.prototype, listOf(left, right), metamethod.upvalues).firstOrNull() ?: LuaNil
                return isTruthy(result)
            }
        }
        return comparison.apply(left, right)
    }

    private fun concat(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val leftValue = stack.get(register(frame, Instruction.b(instruction)))
        val rightValue = stack.get(register(frame, Instruction.c(instruction)))
        val left = stringCoercion(leftValue)
        val right = stringCoercion(rightValue)
        if (left == null || right == null) {
            val metamethod = tableMetamethod(leftValue, CONCAT_KEY) ?: tableMetamethod(rightValue, CONCAT_KEY)
            if (metamethod != null) {
                val result = executeReturned(metamethod.prototype, listOf(leftValue, rightValue), metamethod.upvalues).firstOrNull() ?: LuaNil
                stack.set(register(frame, Instruction.a(instruction)), result)
                return
            }
            val failedValue = if (left == null) leftValue else rightValue
            throw LuaVmException("attempt to concatenate ${typeName(failedValue)}")
        }
        stack.set(register(frame, Instruction.a(instruction)), LuaString(left + right))
    }

    private fun bitwise(stack: LuaStack, frame: CallFrame, instruction: Int, operation: Bitwise) {
        val leftValue = stack.get(register(frame, Instruction.b(instruction)))
        val rightValue = stack.get(register(frame, Instruction.c(instruction)))
        val left = integerValue(leftValue)
        val right = integerValue(rightValue)
        if (left == null || right == null) {
            val metamethod = bitwiseMetamethod(leftValue, rightValue, operation)
            if (metamethod != null) {
                val result = executeReturned(metamethod.prototype, listOf(leftValue, rightValue), metamethod.upvalues).firstOrNull() ?: LuaNil
                stack.set(register(frame, Instruction.a(instruction)), result)
                return
            }
            val failedValue = if (left == null) leftValue else rightValue
            throw LuaVmException("attempt to perform bitwise operation on ${typeName(failedValue)}")
        }
        stack.set(register(frame, Instruction.a(instruction)), LuaInteger(operation.apply(left, right)))
    }

    private fun bitwiseMetamethod(left: LuaValue, right: LuaValue, operation: Bitwise): LuaClosure? {
        val key = when (operation) {
            Bitwise.AND -> BAND_KEY
            Bitwise.OR -> BOR_KEY
            Bitwise.XOR -> BXOR_KEY
            Bitwise.SHIFT_LEFT -> SHL_KEY
            Bitwise.SHIFT_RIGHT -> SHR_KEY
        }
        return tableMetamethod(left, key) ?: tableMetamethod(right, key)
    }

    private fun bitwiseNot(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val value = stack.get(register(frame, Instruction.b(instruction)))
        val integer = integerValue(value)
        if (integer == null) {
            val metamethod = tableMetamethod(value, BNOT_KEY)
            if (metamethod != null) {
                val result = executeReturned(metamethod.prototype, listOf(value), metamethod.upvalues).firstOrNull() ?: LuaNil
                stack.set(register(frame, Instruction.a(instruction)), result)
                return
            }
            throw LuaVmException("attempt to perform bitwise operation on ${typeName(value)}")
        }
        stack.set(register(frame, Instruction.a(instruction)), LuaInteger(integer.inv()))
    }

    private fun forLoopContinues(stack: LuaStack, frame: CallFrame, instruction: Int): Boolean {
        val base = register(frame, Instruction.a(instruction))
        val index = numberValue(stack.get(base))
            ?: throw LuaVmException("numeric for index must be a number")
        val limit = numberValue(stack.get(base + 1))
            ?: throw LuaVmException("numeric for limit must be a number")
        val step = numberValue(stack.get(base + 2))
            ?: throw LuaVmException("numeric for step must be a number")
        return if (step >= 0.0) index <= limit else index >= limit
    }

    private fun incrementForIndex(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val base = register(frame, Instruction.a(instruction))
        val index = stack.get(base)
        val step = stack.get(base + 2)
        if (index is LuaInteger && step is LuaInteger) {
            stack.set(base, LuaInteger(index.value + step.value))
            return
        }

        val indexNumber = numberValue(index)
            ?: throw LuaVmException("numeric for index must be a number")
        val stepNumber = numberValue(step)
            ?: throw LuaVmException("numeric for step must be a number")
        stack.set(base, LuaFloat(indexNumber + stepNumber))
    }

    private enum class Arithmetic {
        ADD,
        SUB,
        MUL,
        DIV,
        IDIV,
        MOD,
        POW;

        fun apply(left: LuaValue, right: LuaValue): LuaValue {
            if (left is LuaInteger && right is LuaInteger) {
                return integerArithmetic(left.value, right.value)
            }

            val leftNumber = numberValue(left)
            val rightNumber = numberValue(right)
            if (leftNumber == null || rightNumber == null) {
                throw LuaVmException("attempt to perform arithmetic on ${typeName(if (leftNumber == null) left else right)}")
            }

            return when (this) {
                ADD -> LuaFloat(leftNumber + rightNumber)
                SUB -> LuaFloat(leftNumber - rightNumber)
                MUL -> LuaFloat(leftNumber * rightNumber)
                DIV -> LuaFloat(leftNumber / rightNumber)
                IDIV -> LuaFloat(kotlin.math.floor(leftNumber / rightNumber))
                MOD -> LuaFloat(leftNumber % rightNumber)
                POW -> LuaFloat(Math.pow(leftNumber, rightNumber))
            }
        }

        private fun integerArithmetic(left: Long, right: Long): LuaValue {
            return when (this) {
                ADD -> LuaInteger(left + right)
                SUB -> LuaInteger(left - right)
                MUL -> LuaInteger(left * right)
                DIV -> LuaFloat(left.toDouble() / right.toDouble())
                IDIV -> LuaInteger(Math.floorDiv(left, right))
                MOD -> LuaInteger(Math.floorMod(left, right))
                POW -> LuaFloat(Math.pow(left.toDouble(), right.toDouble()))
            }
        }
    }

    private enum class Comparison {
        EQ,
        LT,
        LE;

        fun apply(left: LuaValue, right: LuaValue): Boolean {
            return when (this) {
                EQ -> luaEquals(left, right)
                LT -> orderedCompare(left, right) < 0
                LE -> orderedCompare(left, right) <= 0
            }
        }

        private fun luaEquals(left: LuaValue, right: LuaValue): Boolean {
            val leftNumber = numberValue(left)
            val rightNumber = numberValue(right)
            if (leftNumber != null && rightNumber != null) {
                return leftNumber == rightNumber
            }
            if (left is LuaUserData && right is LuaUserData) {
                return left.value === right.value
            }
            return left == right
        }

        private fun orderedCompare(left: LuaValue, right: LuaValue): Int {
            val leftNumber = numberValue(left)
            val rightNumber = numberValue(right)
            if (leftNumber != null && rightNumber != null) {
                return leftNumber.compareTo(rightNumber)
            }
            if (left is LuaString && right is LuaString) {
                return left.value.compareTo(right.value)
            }
            throw LuaVmException("attempt to compare ${typeName(left)} with ${typeName(right)}")
        }
    }

    private enum class Bitwise {
        AND,
        OR,
        XOR,
        SHIFT_LEFT,
        SHIFT_RIGHT;

        fun apply(left: Long, right: Long): Long {
            return when (this) {
                AND -> left and right
                OR -> left or right
                XOR -> left xor right
                SHIFT_LEFT -> shiftLeft(left, right)
                SHIFT_RIGHT -> shiftRight(left, right)
            }
        }

        private fun shiftLeft(value: Long, distance: Long): Long {
            if (distance <= -LONG_BITS || distance >= LONG_BITS) {
                return 0L
            }
            return if (distance < 0) {
                value ushr (-distance).toInt()
            } else {
                value shl distance.toInt()
            }
        }

        private fun shiftRight(value: Long, distance: Long): Long {
            if (distance <= -LONG_BITS || distance >= LONG_BITS) {
                return 0L
            }
            return if (distance < 0) {
                value shl (-distance).toInt()
            } else {
                value ushr distance.toInt()
            }
        }
    }
}

private fun isTruthy(value: LuaValue): Boolean {
    return value != LuaNil && value != LuaBoolean(false)
}

private fun numberValue(value: LuaValue): Double? {
    return when (value) {
        is LuaInteger -> value.value.toDouble()
        is LuaFloat -> value.value
        else -> null
    }
}

private const val LONG_BITS = 64L
private const val LONG_MAX_EXCLUSIVE = 9223372036854775808.0

private fun integerValue(value: LuaValue): Long? {
    return when (value) {
        is LuaInteger -> value.value
        is LuaFloat -> {
            if (
                java.lang.Double.isFinite(value.value) &&
                value.value % 1.0 == 0.0 &&
                value.value >= Long.MIN_VALUE.toDouble() &&
                value.value < LONG_MAX_EXCLUSIVE
            ) {
                value.value.toLong()
            } else {
                null
            }
        }
        else -> null
    }
}

private fun stringCoercion(value: LuaValue): String? {
    return when (value) {
        is LuaString -> value.value
        is LuaInteger -> value.value.toString()
        is LuaFloat -> value.value.toString()
        else -> null
    }
}

private fun typeName(value: LuaValue): String {
    return when (value) {
        is LuaBoolean -> "boolean"
        is LuaClosure,
        is LuaNativeFunction,
        -> "function"
        is LuaFloat, is LuaInteger -> "number"
        LuaNil -> "nil"
        is LuaString -> "string"
        is LuaTable -> "table"
        is LuaUserData -> "userdata"
    }
}

private val INDEX_KEY = LuaString("__index")
private val NEW_INDEX_KEY = LuaString("__newindex")
private val CALL_KEY = LuaString("__call")
private val LEN_KEY = LuaString("__len")
private val EQ_KEY = LuaString("__eq")
private val LT_KEY = LuaString("__lt")
private val LE_KEY = LuaString("__le")
private val CONCAT_KEY = LuaString("__concat")
private val UNM_KEY = LuaString("__unm")
private val BAND_KEY = LuaString("__band")
private val BOR_KEY = LuaString("__bor")
private val BXOR_KEY = LuaString("__bxor")
private val SHL_KEY = LuaString("__shl")
private val SHR_KEY = LuaString("__shr")
private val BNOT_KEY = LuaString("__bnot")
private val ADD_KEY = LuaString("__add")
private val SUB_KEY = LuaString("__sub")
private val MUL_KEY = LuaString("__mul")
private val DIV_KEY = LuaString("__div")
private val IDIV_KEY = LuaString("__idiv")
private val MOD_KEY = LuaString("__mod")
private val POW_KEY = LuaString("__pow")
