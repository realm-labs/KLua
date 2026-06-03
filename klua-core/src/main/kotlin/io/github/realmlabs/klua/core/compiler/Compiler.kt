package io.github.realmlabs.klua.core.compiler

import io.github.realmlabs.klua.core.ast.BooleanExpression
import io.github.realmlabs.klua.core.ast.Chunk
import io.github.realmlabs.klua.core.ast.Expression
import io.github.realmlabs.klua.core.ast.FloatExpression
import io.github.realmlabs.klua.core.ast.IntegerExpression
import io.github.realmlabs.klua.core.ast.NilExpression
import io.github.realmlabs.klua.core.ast.ReturnStatement
import io.github.realmlabs.klua.core.ast.Statement
import io.github.realmlabs.klua.core.ast.StringExpression
import io.github.realmlabs.klua.core.ast.UnaryExpression
import io.github.realmlabs.klua.core.ast.UnaryOperator
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
            if (index != statements.lastIndex) {
                throw unsupported(statement, "only a final return statement is supported by this compiler slice")
            }

            when (statement) {
                is ReturnStatement -> compileReturn(statement)
                else -> throw unsupported(statement, "only return statements are supported by this compiler slice")
            }
        }
    }

    private fun compileReturn(statement: ReturnStatement) {
        for ((register, expression) in statement.values.withIndex()) {
            compileLiteral(expression, register)
        }
        emitReturn(0, statement.values.size, statement.range.start.line)
    }

    private fun compileLiteral(expression: Expression, register: Int) {
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
            is UnaryExpression -> compileUnaryLiteral(expression, register)
            else -> throw unsupported(expression, "only literal return expressions are supported by this compiler slice")
        }
    }

    private fun compileUnaryLiteral(expression: UnaryExpression, register: Int) {
        if (expression.operator != UnaryOperator.NEGATE) {
            throw unsupported(expression, "only negative numeric literal expressions are supported by this compiler slice")
        }

        when (val inner = expression.expression) {
            is IntegerExpression -> emitInteger(register, -inner.value, expression.range.start.line)
            is FloatExpression -> {
                val constant = constants.add(LuaFloat(-inner.value))
                writer.emit(Instruction.abc(Opcode.LOAD_FLOAT, register, constant), expression.range.start.line)
            }
            else -> throw unsupported(expression, "only negative numeric literal expressions are supported by this compiler slice")
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
