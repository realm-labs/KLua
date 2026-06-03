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
import io.github.realmlabs.klua.core.value.LuaString
import io.github.realmlabs.klua.core.value.LuaTable
import io.github.realmlabs.klua.core.value.LuaTableKeyException
import io.github.realmlabs.klua.core.value.LuaUpvalue
import io.github.realmlabs.klua.core.value.LuaValue

internal class LuaVm {
    fun execute(prototype: Prototype): List<LuaValue> {
        return execute(prototype, emptyList(), emptyList())
    }

    private fun execute(prototype: Prototype, arguments: List<LuaValue>, upvalues: List<LuaUpvalue>): List<LuaValue> {
        val stack = LuaStack(prototype.maxStackSize.coerceAtLeast(arguments.size))
        for (index in 0 until prototype.numParams) {
            stack.set(index, arguments.getOrElse(index) { LuaNil })
        }
        val varargs = if (prototype.isVararg) {
            arguments.drop(prototype.numParams)
        } else {
            emptyList()
        }
        val frame = CallFrame(prototype, varargs, upvalues)

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
                Opcode.CALL -> call(stack, frame, instruction)
                Opcode.RETURN -> {
                    val base = register(frame, Instruction.a(instruction))
                    val count = returnCount(frame, base, Instruction.b(instruction))
                    return stack.slice(base, count)
                }
            }
        }

        throw LuaVmException("prototype completed without RETURN")
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

    private fun call(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val base = register(frame, Instruction.a(instruction))
        val callee = stack.get(base)
        if (callee !is LuaClosure) {
            throw LuaVmException("attempt to call ${typeName(callee)}")
        }

        val arguments = stack.slice(base + 1, argumentCount(frame, base, Instruction.b(instruction)))
        val results = execute(callee.prototype, arguments, callee.upvalues)
        val expectedResults = Instruction.c(instruction)
        if (expectedResults == OPEN_RESULT_COUNT) {
            setOpenResults(stack, frame, base, results)
            return
        }

        for (index in 0 until expectedResults) {
            stack.set(base + index, results.getOrElse(index) { LuaNil })
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
        val table = stack.get(register(frame, Instruction.b(instruction)))
        if (table !is LuaTable) {
            throw LuaVmException("attempt to index ${typeName(table)}")
        }
        val key = stack.get(register(frame, Instruction.c(instruction)))
        stack.set(register(frame, Instruction.a(instruction)), tableGet(table, key))
    }

    private fun setTable(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val table = stack.get(register(frame, Instruction.a(instruction)))
        if (table !is LuaTable) {
            throw LuaVmException("attempt to index ${typeName(table)}")
        }
        val key = stack.get(register(frame, Instruction.b(instruction)))
        val value = stack.get(register(frame, Instruction.c(instruction)))
        tableSet(table, key, value)
    }

    private fun getField(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val table = stack.get(register(frame, Instruction.b(instruction)))
        if (table !is LuaTable) {
            throw LuaVmException("attempt to index ${typeName(table)}")
        }
        val key = stringConstant(frame.prototype, Instruction.c(instruction))
        stack.set(register(frame, Instruction.a(instruction)), tableGet(table, key))
    }

    private fun setField(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val table = stack.get(register(frame, Instruction.a(instruction)))
        if (table !is LuaTable) {
            throw LuaVmException("attempt to index ${typeName(table)}")
        }
        val key = stringConstant(frame.prototype, Instruction.b(instruction))
        val value = stack.get(register(frame, Instruction.c(instruction)))
        tableSet(table, key, value)
    }

    private fun tableGet(table: LuaTable, key: LuaValue): LuaValue {
        return try {
            table.get(key)
        } catch (error: LuaTableKeyException) {
            throw LuaVmException(error.message ?: "invalid table key")
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
            table.set(key, value)
        } catch (error: LuaTableKeyException) {
            throw LuaVmException(error.message ?: "invalid table key")
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
        stack.set(register(frame, Instruction.a(instruction)), operation.apply(left, right))
    }

    private fun unaryMinus(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val value = stack.get(register(frame, Instruction.b(instruction)))
        val result = when (value) {
            is LuaInteger -> LuaInteger(-value.value)
            is LuaFloat -> LuaFloat(-value.value)
            else -> throw LuaVmException("attempt to perform arithmetic on ${typeName(value)}")
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
            is LuaTable -> LuaInteger(value.rawLength())
            else -> throw LuaVmException("attempt to get length of ${typeName(value)}")
        }
        stack.set(register(frame, Instruction.a(instruction)), result)
    }

    private fun compare(stack: LuaStack, frame: CallFrame, instruction: Int, comparison: Comparison) {
        val left = stack.get(register(frame, Instruction.b(instruction)))
        val right = stack.get(register(frame, Instruction.c(instruction)))
        stack.set(register(frame, Instruction.a(instruction)), LuaBoolean(comparison.apply(left, right)))
    }

    private fun concat(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val leftValue = stack.get(register(frame, Instruction.b(instruction)))
        val rightValue = stack.get(register(frame, Instruction.c(instruction)))
        val left = stringCoercion(leftValue)
            ?: throw LuaVmException("attempt to concatenate ${typeName(leftValue)}")
        val right = stringCoercion(rightValue)
            ?: throw LuaVmException("attempt to concatenate ${typeName(rightValue)}")
        stack.set(register(frame, Instruction.a(instruction)), LuaString(left + right))
    }

    private fun bitwise(stack: LuaStack, frame: CallFrame, instruction: Int, operation: Bitwise) {
        val leftValue = stack.get(register(frame, Instruction.b(instruction)))
        val rightValue = stack.get(register(frame, Instruction.c(instruction)))
        val left = integerValue(leftValue)
            ?: throw LuaVmException("attempt to perform bitwise operation on ${typeName(leftValue)}")
        val right = integerValue(rightValue)
            ?: throw LuaVmException("attempt to perform bitwise operation on ${typeName(rightValue)}")
        stack.set(register(frame, Instruction.a(instruction)), LuaInteger(operation.apply(left, right)))
    }

    private fun bitwiseNot(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val value = stack.get(register(frame, Instruction.b(instruction)))
        val integer = integerValue(value)
            ?: throw LuaVmException("attempt to perform bitwise operation on ${typeName(value)}")
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
        is LuaClosure -> "function"
        is LuaFloat, is LuaInteger -> "number"
        LuaNil -> "nil"
        is LuaString -> "string"
        is LuaTable -> "table"
    }
}
