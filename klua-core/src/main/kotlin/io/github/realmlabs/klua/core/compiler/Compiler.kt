package io.github.realmlabs.klua.core.compiler

import io.github.realmlabs.klua.core.ast.BinaryExpression
import io.github.realmlabs.klua.core.ast.BinaryOperator
import io.github.realmlabs.klua.core.ast.BooleanExpression
import io.github.realmlabs.klua.core.ast.Chunk
import io.github.realmlabs.klua.core.ast.Expression
import io.github.realmlabs.klua.core.ast.FloatExpression
import io.github.realmlabs.klua.core.ast.IntegerExpression
import io.github.realmlabs.klua.core.ast.LocalStatement
import io.github.realmlabs.klua.core.ast.NilExpression
import io.github.realmlabs.klua.core.ast.ReturnStatement
import io.github.realmlabs.klua.core.ast.Statement
import io.github.realmlabs.klua.core.ast.StringExpression
import io.github.realmlabs.klua.core.ast.UnaryExpression
import io.github.realmlabs.klua.core.ast.UnaryOperator
import io.github.realmlabs.klua.core.ast.VariableExpression
import io.github.realmlabs.klua.core.bytecode.BytecodeWriter
import io.github.realmlabs.klua.core.bytecode.Instruction
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
) {
    private val writer = BytecodeWriter()
    private val constants = ConstantPool()
    private val locals = linkedMapOf<String, Int>()
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
            lineInfo = writer.lineInfo(),
            maxStackSize = maxRegister.coerceAtLeast(1),
        )
    }

    private fun compileStatements(statements: List<Statement>) {
        for ((index, statement) in statements.withIndex()) {
            when (statement) {
                is LocalStatement -> compileLocal(statement)
                is ReturnStatement -> {
                    if (index != statements.lastIndex) {
                        throw unsupported(statement, "return must be the final statement in this compiler slice")
                    }
                    compileReturn(statement)
                }
                else -> throw unsupported(statement, "only local and return statements are supported by this compiler slice")
            }
        }
    }

    private fun compileLocal(statement: LocalStatement) {
        val slots = statement.names.map { name ->
            val slot = nextLocalRegister++
            maxRegister = maxRegister.coerceAtLeast(slot + 1)
            slot
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

    private fun compileReturn(statement: ReturnStatement) {
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
            is VariableExpression -> compileVariable(expression, register)
            is UnaryExpression -> compileUnaryExpression(expression, register)
            is BinaryExpression -> compileBinaryExpression(expression, register)
        }
    }

    private fun compileVariable(expression: VariableExpression, register: Int) {
        val source = locals[expression.name]
            ?: throw unsupported(expression, "unknown local '${expression.name}'")
        if (source != register) {
            writer.emit(Instruction.abc(Opcode.MOVE, register, source), expression.range.start.line)
        }
    }

    private fun compileUnaryExpression(expression: UnaryExpression, register: Int) {
        if (expression.operator != UnaryOperator.NEGATE) {
            throw unsupported(expression, "only numeric negation is supported by this compiler slice")
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
        val opcode = arithmeticOpcode(expression.operator)
            ?: throw unsupported(expression, "only arithmetic binary expressions are supported by this compiler slice")
        val rightRegister = register + 1

        compileExpression(expression.left, register)
        compileExpression(expression.right, rightRegister)
        writer.emit(Instruction.abc(opcode, register, register, rightRegister), expression.range.start.line)
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
