package io.github.realmlabs.klua.core.compiler

import io.github.realmlabs.klua.core.ast.AssignmentStatement
import io.github.realmlabs.klua.core.ast.BinaryExpression
import io.github.realmlabs.klua.core.ast.BinaryOperator
import io.github.realmlabs.klua.core.ast.BooleanExpression
import io.github.realmlabs.klua.core.ast.BreakStatement
import io.github.realmlabs.klua.core.ast.CallExpression
import io.github.realmlabs.klua.core.ast.CallStatement
import io.github.realmlabs.klua.core.ast.Chunk
import io.github.realmlabs.klua.core.ast.Expression
import io.github.realmlabs.klua.core.ast.FloatExpression
import io.github.realmlabs.klua.core.ast.FunctionExpression
import io.github.realmlabs.klua.core.ast.FunctionStatement
import io.github.realmlabs.klua.core.ast.IfStatement
import io.github.realmlabs.klua.core.ast.IntegerExpression
import io.github.realmlabs.klua.core.ast.LocalFunctionStatement
import io.github.realmlabs.klua.core.ast.LocalStatement
import io.github.realmlabs.klua.core.ast.NilExpression
import io.github.realmlabs.klua.core.ast.NumericForStatement
import io.github.realmlabs.klua.core.ast.RepeatStatement
import io.github.realmlabs.klua.core.ast.ReturnStatement
import io.github.realmlabs.klua.core.ast.Statement
import io.github.realmlabs.klua.core.ast.StringExpression
import io.github.realmlabs.klua.core.ast.TableExpression
import io.github.realmlabs.klua.core.ast.UnaryExpression
import io.github.realmlabs.klua.core.ast.UnaryOperator
import io.github.realmlabs.klua.core.ast.VarargExpression
import io.github.realmlabs.klua.core.ast.VariableExpression
import io.github.realmlabs.klua.core.ast.WhileStatement
import io.github.realmlabs.klua.core.bytecode.BytecodeWriter
import io.github.realmlabs.klua.core.bytecode.Instruction
import io.github.realmlabs.klua.core.bytecode.OPEN_RESULT_COUNT
import io.github.realmlabs.klua.core.bytecode.Opcode
import io.github.realmlabs.klua.core.bytecode.Prototype
import io.github.realmlabs.klua.core.parser.Parser
import io.github.realmlabs.klua.core.runtime.LuaSourceVersion
import io.github.realmlabs.klua.core.value.LuaFloat
import io.github.realmlabs.klua.core.value.LuaInteger
import io.github.realmlabs.klua.core.value.LuaString

internal class Compiler private constructor(
    private val sourceName: String,
    private val version: LuaSourceVersion,
    private val isVarargFunction: Boolean = false,
) {
    private val writer = BytecodeWriter()
    private val constants = ConstantPool()
    private val nested = mutableListOf<Prototype>()
    private val locals = linkedMapOf<String, Int>()
    private val loopBreaks = mutableListOf<MutableList<Int>>()
    private var nextLocalRegister = 0
    private var maxRegister = 0

    fun compile(chunk: Chunk): Prototype {
        if (chunk.statements.isEmpty()) {
            emitReturn(0, 0, chunk.range.start.line)
        } else {
            compileStatements(chunk.statements)
        }

        return Prototype(
            sourceName = sourceName,
            version = version,
            code = writer.code(),
            constants = constants.toArray(),
            nested = nested.toTypedArray(),
            lineInfo = writer.lineInfo(),
            maxStackSize = maxRegister.coerceAtLeast(1),
        )
    }

    private fun compileStatements(statements: List<Statement>) {
        for (statement in statements) {
            when (statement) {
                is LocalStatement -> compileLocal(statement)
                is AssignmentStatement -> compileAssignment(statement)
                is CallStatement -> compileCallStatement(statement)
                is IfStatement -> compileIf(statement)
                is WhileStatement -> compileWhile(statement)
                is RepeatStatement -> compileRepeat(statement)
                is NumericForStatement -> compileNumericFor(statement)
                is FunctionStatement -> throw unsupported(statement, "function declarations are not supported by this compiler slice")
                is LocalFunctionStatement -> compileLocalFunction(statement)
                is ReturnStatement -> compileReturn(statement)
                is BreakStatement -> compileBreak(statement)
            }
        }
    }

    private fun compileCallStatement(statement: CallStatement) {
        compileCallExpression(statement.call, nextLocalRegister, 0)
    }

    private fun compileScopedBlock(statements: List<Statement>) {
        val savedLocals = LinkedHashMap(locals)
        val savedNextLocalRegister = nextLocalRegister
        compileStatements(statements)
        locals.clear()
        locals.putAll(savedLocals)
        nextLocalRegister = savedNextLocalRegister
    }

    private fun compileNumericFor(statement: NumericForStatement) {
        val breaks = pushLoopBreaks()
        val savedLocals = LinkedHashMap(locals)
        val savedNextLocalRegister = nextLocalRegister
        val baseRegister = nextLocalRegister
        nextLocalRegister += 3
        maxRegister = maxRegister.coerceAtLeast(nextLocalRegister)

        compileExpression(statement.start, baseRegister)
        compileExpression(statement.limit, baseRegister + 1)
        if (statement.step == null) {
            emitInteger(baseRegister + 2, 1, statement.range.start.line)
        } else {
            compileExpression(statement.step, baseRegister + 2)
        }

        locals[statement.name] = baseRegister
        val testIndex = writer.size
        writer.emit(Instruction.abc(Opcode.FOR_TEST, baseRegister), statement.range.start.line)
        val loopStart = writer.size

        compileStatements(statement.block)

        val loopIndex = writer.size
        writer.emit(Instruction.abc(Opcode.FOR_LOOP, baseRegister, writer.jumpOffset(loopIndex, loopStart)), statement.range.start.line)
        patchForTest(testIndex, writer.size)
        patchLoopBreaks(breaks, writer.size)

        locals.clear()
        locals.putAll(savedLocals)
        nextLocalRegister = savedNextLocalRegister
    }

    private fun compileLocal(statement: LocalStatement) {
        val slots = statement.names.map { name ->
            val slot = nextLocalRegister++
            maxRegister = maxRegister.coerceAtLeast(slot + 1)
            slot
        }

        val onlyValue = statement.values.singleOrNull()
        if (onlyValue is VarargExpression) {
            compileVarargExpression(onlyValue, slots.first(), slots.size)
            for ((index, name) in statement.names.withIndex()) {
                locals[name] = slots[index]
            }
            return
        }
        if (onlyValue is CallExpression) {
            compileCallExpression(onlyValue, slots.first(), slots.size)
            for ((index, name) in statement.names.withIndex()) {
                locals[name] = slots[index]
            }
            return
        }

        for ((index, slot) in slots.withIndex()) {
            val value = statement.values.getOrNull(index)
            if (value == null) {
                writer.emit(Instruction.abc(Opcode.LOAD_NIL, slot), statement.range.start.line)
            } else {
                compileExpression(value, slot)
            }
        }

        for ((index, name) in statement.names.withIndex()) {
            locals[name] = slots[index]
        }
    }

    private fun compileLocalFunction(statement: LocalFunctionStatement) {
        val slot = nextLocalRegister++
        maxRegister = maxRegister.coerceAtLeast(slot + 1)
        locals[statement.name] = slot
        compileFunctionExpression(statement.function, slot)
    }

    private fun compileAssignment(statement: AssignmentStatement) {
        val targetSlots = statement.names.map { name ->
            locals[name] ?: throw unsupported(statement, "unknown local '$name'")
        }
        val tempBase = nextLocalRegister
        val onlyValue = statement.values.singleOrNull()

        if (onlyValue is VarargExpression) {
            compileVarargExpression(onlyValue, tempBase, targetSlots.size)
            for ((index, targetSlot) in targetSlots.withIndex()) {
                writer.emit(Instruction.abc(Opcode.MOVE, targetSlot, tempBase + index), statement.range.start.line)
            }
            return
        }

        if (onlyValue is CallExpression) {
            compileCallExpression(onlyValue, tempBase, targetSlots.size)
            for ((index, targetSlot) in targetSlots.withIndex()) {
                writer.emit(Instruction.abc(Opcode.MOVE, targetSlot, tempBase + index), statement.range.start.line)
            }
            return
        }

        for (index in statement.names.indices) {
            val value = statement.values.getOrNull(index)
            if (value == null) {
                writer.emit(Instruction.abc(Opcode.LOAD_NIL, tempBase + index), statement.range.start.line)
                maxRegister = maxRegister.coerceAtLeast(tempBase + index + 1)
            } else {
                compileExpression(value, tempBase + index)
            }
        }

        for ((index, targetSlot) in targetSlots.withIndex()) {
            writer.emit(Instruction.abc(Opcode.MOVE, targetSlot, tempBase + index), statement.range.start.line)
        }
    }

    private fun compileIf(statement: IfStatement) {
        val endJumps = mutableListOf<Int>()

        compileConditionalBlock(statement.condition, statement.thenBlock, statement.range.start.line, endJumps)
        for (branch in statement.elseifBranches) {
            compileConditionalBlock(branch.condition, branch.block, branch.range.start.line, endJumps)
        }
        statement.elseBlock?.let { compileScopedBlock(it) }

        val endIndex = writer.size
        for (jump in endJumps) {
            patchJump(jump, endIndex)
        }
    }

    private fun compileConditionalBlock(
        condition: Expression,
        block: List<Statement>,
        line: Int,
        endJumps: MutableList<Int>,
    ) {
        val conditionRegister = nextLocalRegister
        compileExpression(condition, conditionRegister)

        val testIndex = writer.size
        writer.emit(Instruction.abc(Opcode.TEST, conditionRegister), line)

        compileScopedBlock(block)

        val endJump = writer.size
        writer.emit(Instruction.abc(Opcode.JMP, 0), line)
        endJumps += endJump

        patchTest(testIndex, writer.size)
    }

    private fun compileWhile(statement: WhileStatement) {
        val breaks = pushLoopBreaks()
        val loopStart = writer.size
        val conditionRegister = nextLocalRegister
        compileExpression(statement.condition, conditionRegister)

        val testIndex = writer.size
        writer.emit(Instruction.abc(Opcode.TEST, conditionRegister), statement.range.start.line)

        compileScopedBlock(statement.block)

        val backJump = writer.size
        writer.emit(Instruction.abc(Opcode.JMP, 0), statement.range.start.line)
        patchJump(backJump, loopStart)
        patchTest(testIndex, writer.size)
        patchLoopBreaks(breaks, writer.size)
    }

    private fun compileRepeat(statement: RepeatStatement) {
        val breaks = pushLoopBreaks()
        val savedLocals = LinkedHashMap(locals)
        val savedNextLocalRegister = nextLocalRegister
        val loopStart = writer.size

        compileStatements(statement.block)

        val conditionRegister = nextLocalRegister
        compileExpression(statement.condition, conditionRegister)

        val testIndex = writer.size
        writer.emit(Instruction.abc(Opcode.TEST, conditionRegister), statement.condition.range.start.line)
        patchTest(testIndex, loopStart)
        patchLoopBreaks(breaks, writer.size)

        locals.clear()
        locals.putAll(savedLocals)
        nextLocalRegister = savedNextLocalRegister
    }

    private fun compileBreak(statement: BreakStatement) {
        val breaks = loopBreaks.lastOrNull()
            ?: throw unsupported(statement, "'break' outside loop")
        val breakJump = writer.size
        writer.emit(Instruction.abc(Opcode.JMP, 0), statement.range.start.line)
        breaks += breakJump
    }

    private fun compileReturn(statement: ReturnStatement) {
        if (statement.values.lastOrNull().isOpenResultExpression()) {
            compileOpenReturn(statement)
            return
        }

        if (nextLocalRegister == 0) {
            for ((register, expression) in statement.values.withIndex()) {
                compileExpression(expression, register)
            }
            emitReturn(0, statement.values.size, statement.range.start.line)
            return
        }

        val tempBase = nextLocalRegister
        for ((register, expression) in statement.values.withIndex()) {
            compileExpression(expression, tempBase + register)
        }
        for (register in statement.values.indices) {
            writer.emit(Instruction.abc(Opcode.MOVE, register, tempBase + register), statement.range.start.line)
        }
        emitReturn(0, statement.values.size, statement.range.start.line)
    }

    private fun compileOpenReturn(statement: ReturnStatement) {
        val tempBase = nextLocalRegister
        val lastIndex = statement.values.lastIndex
        for (index in 0 until lastIndex) {
            compileExpression(statement.values[index], tempBase + index)
        }
        compileOpenResultExpression(statement.values[lastIndex], tempBase + lastIndex)
        emitReturn(tempBase, OPEN_RESULT_COUNT, statement.range.start.line)
    }

    private fun compileExpression(expression: Expression, register: Int) {
        maxRegister = maxRegister.coerceAtLeast(register + 1)
        val line = expression.range.start.line

        when (expression) {
            is NilExpression -> writer.emit(Instruction.abc(Opcode.LOAD_NIL, register), line)
            is BooleanExpression -> writer.emit(Instruction.abc(Opcode.LOAD_BOOL, register, if (expression.value) 1 else 0), line)
            is IntegerExpression -> emitInteger(register, expression.value, line)
            is FloatExpression -> {
                val constant = constants.add(LuaFloat(expression.value))
                writer.emit(Instruction.abc(Opcode.LOAD_FLOAT, register, constant), line)
            }
            is StringExpression -> {
                val constant = constants.add(LuaString(expression.value))
                writer.emit(Instruction.abc(Opcode.LOAD_K, register, constant), line)
            }
            is CallExpression -> compileCallExpression(expression, register)
            is VariableExpression -> compileVariable(expression, register)
            is VarargExpression -> compileVarargExpression(expression, register, 1)
            is FunctionExpression -> compileFunctionExpression(expression, register)
            is TableExpression -> writer.emit(Instruction.abc(Opcode.NEW_TABLE, register), line)
            is UnaryExpression -> compileUnaryExpression(expression, register)
            is BinaryExpression -> compileBinaryExpression(expression, register)
        }
    }

    private fun compileOpenResultExpression(expression: Expression, register: Int) {
        when (expression) {
            is CallExpression -> compileCallExpression(expression, register, OPEN_RESULT_COUNT)
            is VarargExpression -> compileVarargExpression(expression, register, OPEN_RESULT_COUNT)
            else -> throw unsupported(expression, "not an open result expression")
        }
    }

    private fun compileVarargExpression(expression: VarargExpression, register: Int, resultCount: Int) {
        if (!isVarargFunction) {
            throw unsupported(expression, "cannot use '...' outside a vararg function")
        }
        if (resultCount !in 0..255) {
            throw unsupported(expression, "too many vararg results")
        }

        val minimumResultSlots = if (resultCount == OPEN_RESULT_COUNT) 1 else resultCount
        maxRegister = maxRegister.coerceAtLeast(register + minimumResultSlots)
        writer.emit(Instruction.abc(Opcode.VARARG, register, resultCount), expression.range.start.line)
    }

    private fun compileCallExpression(expression: CallExpression, register: Int, resultCount: Int = 1) {
        if (expression.arguments.size >= OPEN_RESULT_COUNT) {
            throw unsupported(expression, "too many function arguments")
        }
        if (resultCount !in 0..255) {
            throw unsupported(expression, "too many function results")
        }

        compileExpression(expression.callee, register)
        val argumentCount = if (expression.arguments.lastOrNull().isOpenResultExpression()) {
            val lastIndex = expression.arguments.lastIndex
            for (index in 0 until lastIndex) {
                compileExpression(expression.arguments[index], register + index + 1)
            }
            compileOpenResultExpression(expression.arguments[lastIndex], register + lastIndex + 1)
            OPEN_RESULT_COUNT
        } else {
            for ((index, argument) in expression.arguments.withIndex()) {
                compileExpression(argument, register + index + 1)
            }
            expression.arguments.size
        }
        val minimumResultSlots = if (resultCount == OPEN_RESULT_COUNT) 1 else resultCount
        maxRegister = maxRegister.coerceAtLeast(register + maxOf(expression.arguments.size + 1, minimumResultSlots))
        writer.emit(Instruction.abc(Opcode.CALL, register, argumentCount, resultCount), expression.range.start.line)
    }

    private fun compileFunctionExpression(expression: FunctionExpression, register: Int) {
        val prototype = compileNestedFunction(expression)
        val nestedIndex = nested.size
        if (nestedIndex > 255) {
            throw unsupported(expression, "too many nested function prototypes")
        }
        nested += prototype
        writer.emit(Instruction.abc(Opcode.CLOSURE, register, nestedIndex), expression.range.start.line)
    }

    private fun compileNestedFunction(expression: FunctionExpression): Prototype {
        val compiler = Compiler(sourceName, version, expression.isVararg)
        for (parameter in expression.parameters) {
            val slot = compiler.nextLocalRegister++
            compiler.maxRegister = compiler.maxRegister.coerceAtLeast(slot + 1)
            compiler.locals[parameter] = slot
        }
        if (expression.body.isEmpty()) {
            compiler.emitReturn(0, 0, expression.range.start.line)
        } else {
            compiler.compileStatements(expression.body)
            if (expression.body.last() !is ReturnStatement) {
                compiler.emitReturn(0, 0, expression.range.end.line)
            }
        }
        return Prototype(
            sourceName = sourceName,
            version = version,
            code = compiler.writer.code(),
            constants = compiler.constants.toArray(),
            nested = compiler.nested.toTypedArray(),
            lineInfo = compiler.writer.lineInfo(),
            maxStackSize = compiler.maxRegister.coerceAtLeast(1),
            numParams = expression.parameters.size,
            isVararg = expression.isVararg,
        )
    }

    private fun compileVariable(expression: VariableExpression, register: Int) {
        val source = locals[expression.name]
            ?: throw unsupported(expression, "unknown local '${expression.name}'")
        if (source != register) {
            writer.emit(Instruction.abc(Opcode.MOVE, register, source), expression.range.start.line)
        }
    }

    private fun compileUnaryExpression(expression: UnaryExpression, register: Int) {
        if (expression.operator == UnaryOperator.NOT) {
            compileExpression(expression.expression, register)
            writer.emit(Instruction.abc(Opcode.NOT, register, register), expression.range.start.line)
            return
        }

        if (expression.operator != UnaryOperator.NEGATE) {
            if (expression.operator == UnaryOperator.BITWISE_NOT) {
                compileExpression(expression.expression, register)
                writer.emit(Instruction.abc(Opcode.BNOT, register, register), expression.range.start.line)
                return
            }
            if (expression.operator == UnaryOperator.LENGTH) {
                compileExpression(expression.expression, register)
                writer.emit(Instruction.abc(Opcode.LEN, register, register), expression.range.start.line)
                return
            }
            throw unsupported(expression, "only numeric negation, not, bitwise not, and length are supported by this compiler slice")
        }
        when (val inner = expression.expression) {
            is IntegerExpression -> emitInteger(register, -inner.value, expression.range.start.line)
            is FloatExpression -> {
                val constant = constants.add(LuaFloat(-inner.value))
                writer.emit(Instruction.abc(Opcode.LOAD_FLOAT, register, constant), expression.range.start.line)
            }
            else -> {
                compileExpression(inner, register)
                writer.emit(Instruction.abc(Opcode.UNM, register, register), expression.range.start.line)
            }
        }
    }

    private fun compileBinaryExpression(expression: BinaryExpression, register: Int) {
        if (expression.operator == BinaryOperator.AND || expression.operator == BinaryOperator.OR) {
            compileLogicalExpression(expression, register)
            return
        }

        if (expression.operator == BinaryOperator.CONCAT) {
            compileBinaryOperation(expression, register, Opcode.CONCAT)
            return
        }

        val bitwiseOpcode = bitwiseOpcode(expression.operator)
        if (bitwiseOpcode != null) {
            compileBinaryOperation(expression, register, bitwiseOpcode)
            return
        }

        val arithmeticOpcode = arithmeticOpcode(expression.operator)
        if (arithmeticOpcode != null) {
            compileBinaryOperation(expression, register, arithmeticOpcode)
            return
        }

        if (isComparisonOperator(expression.operator)) {
            compileComparisonExpression(expression, register)
            return
        }

        throw unsupported(expression, "only arithmetic, bitwise, comparison, concatenation, and logical binary expressions are supported by this compiler slice")
    }

    private fun compileLogicalExpression(expression: BinaryExpression, register: Int) {
        compileExpression(expression.left, register)

        when (expression.operator) {
            BinaryOperator.AND -> {
                val testIndex = writer.size
                writer.emit(Instruction.abc(Opcode.TEST, register), expression.range.start.line)
                compileExpression(expression.right, register)
                patchTest(testIndex, writer.size)
            }
            BinaryOperator.OR -> {
                val truthTestRegister = register + 1
                maxRegister = maxRegister.coerceAtLeast(truthTestRegister + 1)
                writer.emit(Instruction.abc(Opcode.NOT, truthTestRegister, register), expression.range.start.line)
                val testIndex = writer.size
                writer.emit(Instruction.abc(Opcode.TEST, truthTestRegister), expression.range.start.line)
                compileExpression(expression.right, register)
                patchTest(testIndex, writer.size)
            }
            else -> throw unsupported(expression, "not a logical operator")
        }
    }

    private fun compileBinaryOperation(expression: BinaryExpression, register: Int, opcode: Opcode) {
        val rightRegister = register + 1

        compileExpression(expression.left, register)
        compileExpression(expression.right, rightRegister)
        writer.emit(Instruction.abc(opcode, register, register, rightRegister), expression.range.start.line)
        maxRegister = maxRegister.coerceAtLeast(rightRegister + 1)
    }

    private fun compileComparisonExpression(expression: BinaryExpression, register: Int) {
        val rightRegister = register + 1
        compileExpression(expression.left, register)
        compileExpression(expression.right, rightRegister)

        when (expression.operator) {
            BinaryOperator.EQUAL -> writer.emit(Instruction.abc(Opcode.EQ, register, register, rightRegister), expression.range.start.line)
            BinaryOperator.NOT_EQUAL -> {
                writer.emit(Instruction.abc(Opcode.EQ, register, register, rightRegister), expression.range.start.line)
                writer.emit(Instruction.abc(Opcode.NOT, register, register), expression.range.start.line)
            }
            BinaryOperator.LESS -> writer.emit(Instruction.abc(Opcode.LT, register, register, rightRegister), expression.range.start.line)
            BinaryOperator.LESS_EQUAL -> writer.emit(Instruction.abc(Opcode.LE, register, register, rightRegister), expression.range.start.line)
            BinaryOperator.GREATER -> writer.emit(Instruction.abc(Opcode.LT, register, rightRegister, register), expression.range.start.line)
            BinaryOperator.GREATER_EQUAL -> writer.emit(Instruction.abc(Opcode.LE, register, rightRegister, register), expression.range.start.line)
            else -> throw unsupported(expression, "not a comparison operator")
        }

        maxRegister = maxRegister.coerceAtLeast(rightRegister + 1)
    }

    private fun arithmeticOpcode(operator: BinaryOperator): Opcode? {
        return when (operator) {
            BinaryOperator.ADD -> Opcode.ADD
            BinaryOperator.SUBTRACT -> Opcode.SUB
            BinaryOperator.MULTIPLY -> Opcode.MUL
            BinaryOperator.DIVIDE -> Opcode.DIV
            BinaryOperator.FLOOR_DIVIDE -> Opcode.IDIV
            BinaryOperator.MODULO -> Opcode.MOD
            BinaryOperator.POWER -> Opcode.POW
            else -> null
        }
    }

    private fun bitwiseOpcode(operator: BinaryOperator): Opcode? {
        return when (operator) {
            BinaryOperator.BITWISE_AND -> Opcode.BAND
            BinaryOperator.BITWISE_OR -> Opcode.BOR
            BinaryOperator.BITWISE_XOR -> Opcode.BXOR
            BinaryOperator.LEFT_SHIFT -> Opcode.SHL
            BinaryOperator.RIGHT_SHIFT -> Opcode.SHR
            else -> null
        }
    }

    private fun isComparisonOperator(operator: BinaryOperator): Boolean {
        return operator == BinaryOperator.EQUAL ||
            operator == BinaryOperator.NOT_EQUAL ||
            operator == BinaryOperator.LESS ||
            operator == BinaryOperator.LESS_EQUAL ||
            operator == BinaryOperator.GREATER ||
            operator == BinaryOperator.GREATER_EQUAL
    }

    private fun Expression?.isOpenResultExpression(): Boolean = this is CallExpression || this is VarargExpression

    private fun emitInteger(register: Int, value: Long, line: Int) {
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            writer.emit(Instruction.abc(Opcode.LOAD_INT, register, value.toInt() and 0xFF), line)
        } else {
            val constant = constants.add(LuaInteger(value))
            writer.emit(Instruction.abc(Opcode.LOAD_K, register, constant), line)
        }
    }

    private fun emitReturn(register: Int, count: Int, line: Int) {
        writer.emit(Instruction.abc(Opcode.RETURN, register, count), line)
    }

    private fun patchTest(index: Int, targetIndex: Int) {
        val register = Instruction.a(writer.code()[index])
        writer.patch(index, Instruction.abc(Opcode.TEST, register, writer.jumpOffset(index, targetIndex)))
    }

    private fun patchJump(index: Int, targetIndex: Int) {
        writer.patch(index, Instruction.abc(Opcode.JMP, writer.jumpOffset(index, targetIndex)))
    }

    private fun patchForTest(index: Int, targetIndex: Int) {
        val register = Instruction.a(writer.code()[index])
        writer.patch(index, Instruction.abc(Opcode.FOR_TEST, register, writer.jumpOffset(index, targetIndex)))
    }

    private fun pushLoopBreaks(): MutableList<Int> {
        val breaks = mutableListOf<Int>()
        loopBreaks += breaks
        return breaks
    }

    private fun patchLoopBreaks(breaks: MutableList<Int>, targetIndex: Int) {
        require(loopBreaks.removeLast() === breaks) { "loop break stack is unbalanced" }
        for (jump in breaks) {
            patchJump(jump, targetIndex)
        }
    }

    private fun unsupported(statement: Statement, message: String): CompilerException {
        return CompilerException(statement.range.start, message)
    }

    private fun unsupported(expression: Expression, message: String): CompilerException {
        return CompilerException(expression.range.start, message)
    }

    companion object {
        fun compile(
            source: String,
            sourceName: String = "chunk",
            version: LuaSourceVersion = LuaSourceVersion.LUA_54,
        ): Prototype {
            val chunk = Parser.parse(source, sourceName)
            return Compiler(sourceName, version).compile(chunk)
        }
    }
}
