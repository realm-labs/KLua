package io.github.realmlabs.klua.core.parser

import io.github.realmlabs.klua.core.ast.BinaryExpression
import io.github.realmlabs.klua.core.ast.BinaryOperator
import io.github.realmlabs.klua.core.ast.AssignmentStatement
import io.github.realmlabs.klua.core.ast.BooleanExpression
import io.github.realmlabs.klua.core.ast.FloatExpression
import io.github.realmlabs.klua.core.ast.IfStatement
import io.github.realmlabs.klua.core.ast.IntegerExpression
import io.github.realmlabs.klua.core.ast.LocalStatement
import io.github.realmlabs.klua.core.ast.RepeatStatement
import io.github.realmlabs.klua.core.ast.ReturnStatement
import io.github.realmlabs.klua.core.ast.StringExpression
import io.github.realmlabs.klua.core.ast.UnaryExpression
import io.github.realmlabs.klua.core.ast.UnaryOperator
import io.github.realmlabs.klua.core.ast.VariableExpression
import io.github.realmlabs.klua.core.ast.WhileStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ParserTest {
    @Test
    fun `parses empty chunk`() {
        val chunk = Parser.parse("", "empty.lua")

        assertEquals("empty.lua", chunk.range.start.sourceName)
        assertEquals(0, chunk.statements.size)
    }

    @Test
    fun `parses local declaration and return statement`() {
        val chunk = Parser.parse(
            """
            local x = 1 + 2 * 3
            return x
            """.trimIndent(),
            "simple.lua",
        )

        assertEquals(2, chunk.statements.size)

        val local = assertIs<LocalStatement>(chunk.statements[0])
        assertEquals(listOf("x"), local.names)
        val assignment = assertIs<BinaryExpression>(local.values.single())
        assertEquals(BinaryOperator.ADD, assignment.operator)
        assertIs<IntegerExpression>(assignment.left)
        val multiply = assertIs<BinaryExpression>(assignment.right)
        assertEquals(BinaryOperator.MULTIPLY, multiply.operator)

        val returnStatement = assertIs<ReturnStatement>(chunk.statements[1])
        val returned = assertIs<VariableExpression>(returnStatement.values.single())
        assertEquals("x", returned.name)
    }

    @Test
    fun `parses if elseif else blocks`() {
        val chunk = Parser.parse(
            """
            if score > 10 then
                return "high"
            elseif score > 0 then
                return "low"
            else
                return "none"
            end
            """.trimIndent(),
        )

        val statement = assertIs<IfStatement>(chunk.statements.single())
        val condition = assertIs<BinaryExpression>(statement.condition)
        assertEquals(BinaryOperator.GREATER, condition.operator)
        assertEquals(1, statement.thenBlock.size)
        assertEquals(1, statement.elseifBranches.size)
        assertEquals(1, statement.elseBlock?.size)

        val thenReturn = assertIs<ReturnStatement>(statement.thenBlock.single())
        assertEquals("high", assertIs<StringExpression>(thenReturn.values.single()).value)
    }

    @Test
    fun `parses while block`() {
        val chunk = Parser.parse(
            """
            while count < 3 do
                count = count + 1
            end
            """.trimIndent(),
        )

        val statement = assertIs<WhileStatement>(chunk.statements.single())
        val condition = assertIs<BinaryExpression>(statement.condition)
        assertEquals(BinaryOperator.LESS, condition.operator)
        assertEquals(1, statement.block.size)

        val assignment = assertIs<AssignmentStatement>(statement.block.single())
        assertEquals(listOf("count"), assignment.names)
    }

    @Test
    fun `parses repeat until block`() {
        val chunk = Parser.parse(
            """
            repeat
                count = count + 1
            until count >= 3
            """.trimIndent(),
        )

        val statement = assertIs<RepeatStatement>(chunk.statements.single())
        assertEquals(1, statement.block.size)
        val condition = assertIs<BinaryExpression>(statement.condition)
        assertEquals(BinaryOperator.GREATER_EQUAL, condition.operator)

        val assignment = assertIs<AssignmentStatement>(statement.block.single())
        assertEquals(listOf("count"), assignment.names)
    }

    @Test
    fun `parses simple assignment statement`() {
        val chunk = Parser.parse(
            """
            local x = 1
            x = x + 1
            return x
            """.trimIndent(),
        )

        assertEquals(3, chunk.statements.size)
        val assignment = assertIs<AssignmentStatement>(chunk.statements[1])
        assertEquals(listOf("x"), assignment.names)
        val value = assertIs<BinaryExpression>(assignment.values.single())
        assertEquals(BinaryOperator.ADD, value.operator)
    }

    @Test
    fun `parses expression precedence and associativity`() {
        val chunk = Parser.parse("return 1 + 2 * 3 ^ 4 ^ 5")
        val statement = assertIs<ReturnStatement>(chunk.statements.single())

        val add = assertIs<BinaryExpression>(statement.values.single())
        assertEquals(BinaryOperator.ADD, add.operator)

        val multiply = assertIs<BinaryExpression>(add.right)
        assertEquals(BinaryOperator.MULTIPLY, multiply.operator)

        val power = assertIs<BinaryExpression>(multiply.right)
        assertEquals(BinaryOperator.POWER, power.operator)
        assertEquals(3L, assertIs<IntegerExpression>(power.left).value)

        val nestedPower = assertIs<BinaryExpression>(power.right)
        assertEquals(BinaryOperator.POWER, nestedPower.operator)
    }

    @Test
    fun `parses unary and binary bitwise tilde by position`() {
        val chunk = Parser.parse("return ~mask ~ 255")
        val statement = assertIs<ReturnStatement>(chunk.statements.single())

        val binary = assertIs<BinaryExpression>(statement.values.single())
        assertEquals(BinaryOperator.BITWISE_XOR, binary.operator)
        val unary = assertIs<UnaryExpression>(binary.left)
        assertEquals(UnaryOperator.BITWISE_NOT, unary.operator)
        assertEquals("mask", assertIs<VariableExpression>(unary.expression).name)
    }

    @Test
    fun `parses literal expression values`() {
        val chunk = Parser.parse("""return nil, true, false, 42, 2.5, "ok"""")
        val values = assertIs<ReturnStatement>(chunk.statements.single()).values

        assertEquals(6, values.size)
        assertIs<io.github.realmlabs.klua.core.ast.NilExpression>(values[0])
        assertEquals(true, assertIs<BooleanExpression>(values[1]).value)
        assertEquals(false, assertIs<BooleanExpression>(values[2]).value)
        assertEquals(42L, assertIs<IntegerExpression>(values[3]).value)
        assertEquals(2.5, assertIs<FloatExpression>(values[4]).value)
        assertEquals("ok", assertIs<StringExpression>(values[5]).value)
    }

    @Test
    fun `reports missing end with source position`() {
        val error = assertFailsWith<ParserException> {
            Parser.parse(
                """
                if ready then
                    return true
                """.trimIndent(),
                "broken.lua",
            )
        }

        assertEquals("broken.lua", error.position.sourceName)
        assertEquals(2, error.position.line)
    }
}
