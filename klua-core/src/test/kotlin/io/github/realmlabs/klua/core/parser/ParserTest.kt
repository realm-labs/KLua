package io.github.realmlabs.klua.core.parser

import io.github.realmlabs.klua.core.ast.BinaryExpression
import io.github.realmlabs.klua.core.ast.BinaryOperator
import io.github.realmlabs.klua.core.ast.AssignmentStatement
import io.github.realmlabs.klua.core.ast.BooleanExpression
import io.github.realmlabs.klua.core.ast.CallExpression
import io.github.realmlabs.klua.core.ast.CallStatement
import io.github.realmlabs.klua.core.ast.BreakStatement
import io.github.realmlabs.klua.core.ast.FloatExpression
import io.github.realmlabs.klua.core.ast.FunctionExpression
import io.github.realmlabs.klua.core.ast.FunctionStatement
import io.github.realmlabs.klua.core.ast.IfStatement
import io.github.realmlabs.klua.core.ast.IndexExpression
import io.github.realmlabs.klua.core.ast.IndexAssignmentTarget
import io.github.realmlabs.klua.core.ast.IntegerExpression
import io.github.realmlabs.klua.core.ast.ListTableEntry
import io.github.realmlabs.klua.core.ast.LocalAssignmentTarget
import io.github.realmlabs.klua.core.ast.LocalFunctionStatement
import io.github.realmlabs.klua.core.ast.LocalStatement
import io.github.realmlabs.klua.core.ast.NamedTableEntry
import io.github.realmlabs.klua.core.ast.NumericForStatement
import io.github.realmlabs.klua.core.ast.RepeatStatement
import io.github.realmlabs.klua.core.ast.ReturnStatement
import io.github.realmlabs.klua.core.ast.StringExpression
import io.github.realmlabs.klua.core.ast.TableExpression
import io.github.realmlabs.klua.core.ast.UnaryExpression
import io.github.realmlabs.klua.core.ast.UnaryOperator
import io.github.realmlabs.klua.core.ast.VarargExpression
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
        assertEquals("count", assertIs<LocalAssignmentTarget>(assignment.targets.single()).name)
    }

    @Test
    fun `parses break statement in loop block`() {
        val chunk = Parser.parse(
            """
            while true do
                break
            end
            """.trimIndent(),
        )

        val loop = assertIs<WhileStatement>(chunk.statements.single())
        assertIs<BreakStatement>(loop.block.single())
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
        assertEquals("count", assertIs<LocalAssignmentTarget>(assignment.targets.single()).name)
    }

    @Test
    fun `parses numeric for block`() {
        val chunk = Parser.parse(
            """
            for i = 1, 3, 1 do
                total = total + i
            end
            """.trimIndent(),
        )

        val statement = assertIs<NumericForStatement>(chunk.statements.single())
        assertEquals("i", statement.name)
        assertEquals(1L, assertIs<IntegerExpression>(statement.start).value)
        assertEquals(3L, assertIs<IntegerExpression>(statement.limit).value)
        assertEquals(1L, assertIs<IntegerExpression>(statement.step).value)
        assertEquals(1, statement.block.size)
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
        assertEquals("x", assertIs<LocalAssignmentTarget>(assignment.targets.single()).name)
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
    fun `parses shift precedence between bitwise and concatenation`() {
        val chunk = Parser.parse("""return 1 & 2 << 3 .. "x"""")
        val statement = assertIs<ReturnStatement>(chunk.statements.single())

        val bitwiseAnd = assertIs<BinaryExpression>(statement.values.single())
        assertEquals(BinaryOperator.BITWISE_AND, bitwiseAnd.operator)

        val shift = assertIs<BinaryExpression>(bitwiseAnd.right)
        assertEquals(BinaryOperator.LEFT_SHIFT, shift.operator)

        val concat = assertIs<BinaryExpression>(shift.right)
        assertEquals(BinaryOperator.CONCAT, concat.operator)
    }

    @Test
    fun `parses unary length before concatenation`() {
        val chunk = Parser.parse("""return #"abc" .. "x"""")
        val statement = assertIs<ReturnStatement>(chunk.statements.single())

        val concat = assertIs<BinaryExpression>(statement.values.single())
        assertEquals(BinaryOperator.CONCAT, concat.operator)

        val length = assertIs<UnaryExpression>(concat.left)
        assertEquals(UnaryOperator.LENGTH, length.operator)
        assertEquals("abc", assertIs<StringExpression>(length.expression).value)
    }

    @Test
    fun `parses local function declarations`() {
        val chunk = Parser.parse(
            """
            local function add(a, b)
                return a + b
            end
            """.trimIndent(),
        )

        val statement = assertIs<LocalFunctionStatement>(chunk.statements.single())
        assertEquals("add", statement.name)
        assertEquals(listOf("a", "b"), statement.function.parameters)
        assertEquals(false, statement.function.isVararg)

        val returned = assertIs<ReturnStatement>(statement.function.body.single())
        val expression = assertIs<BinaryExpression>(returned.values.single())
        assertEquals(BinaryOperator.ADD, expression.operator)
    }

    @Test
    fun `parses function statements`() {
        val chunk = Parser.parse(
            """
            function identity(value)
                return value
            end
            """.trimIndent(),
        )

        val statement = assertIs<FunctionStatement>(chunk.statements.single())
        assertEquals("identity", statement.name)
        assertEquals(listOf("value"), statement.function.parameters)
    }

    @Test
    fun `parses anonymous vararg function expressions`() {
        val chunk = Parser.parse("local f = function(first, ...) return ... end")
        val local = assertIs<LocalStatement>(chunk.statements.single())

        val function = assertIs<FunctionExpression>(local.values.single())
        assertEquals(listOf("first"), function.parameters)
        assertEquals(true, function.isVararg)
        val returned = assertIs<ReturnStatement>(function.body.single())
        assertIs<VarargExpression>(returned.values.single())
    }

    @Test
    fun `parses function call expressions`() {
        val chunk = Parser.parse("return add(1, 2)")
        val statement = assertIs<ReturnStatement>(chunk.statements.single())

        val call = assertIs<CallExpression>(statement.values.single())
        assertEquals("add", assertIs<VariableExpression>(call.callee).name)
        assertEquals(2, call.arguments.size)
        assertEquals(1L, assertIs<IntegerExpression>(call.arguments[0]).value)
        assertEquals(2L, assertIs<IntegerExpression>(call.arguments[1]).value)
    }

    @Test
    fun `parses function call statements`() {
        val chunk = Parser.parse("tick(1)")
        val statement = assertIs<CallStatement>(chunk.statements.single())

        assertEquals("tick", assertIs<VariableExpression>(statement.call.callee).name)
        assertEquals(1, statement.call.arguments.size)
    }

    @Test
    fun `parses empty table constructors`() {
        val chunk = Parser.parse("return {}")
        val statement = assertIs<ReturnStatement>(chunk.statements.single())

        assertIs<TableExpression>(statement.values.single())
    }

    @Test
    fun `parses list table constructors`() {
        val chunk = Parser.parse("return {10, 20; 30,}")
        val statement = assertIs<ReturnStatement>(chunk.statements.single())
        val table = assertIs<TableExpression>(statement.values.single())

        assertEquals(
            listOf(10L, 20L, 30L),
            table.entries.map { assertIs<IntegerExpression>(assertIs<ListTableEntry>(it).value).value },
        )
    }

    @Test
    fun `parses named table constructor fields`() {
        val chunk = Parser.parse("return { answer = 42 }")
        val statement = assertIs<ReturnStatement>(chunk.statements.single())
        val table = assertIs<TableExpression>(statement.values.single())
        val field = assertIs<NamedTableEntry>(table.entries.single())

        assertEquals("answer", field.name)
        assertEquals(42L, assertIs<IntegerExpression>(field.value).value)
    }

    @Test
    fun `parses bracket index expressions`() {
        val chunk = Parser.parse("return t[1]")
        val statement = assertIs<ReturnStatement>(chunk.statements.single())
        val index = assertIs<IndexExpression>(statement.values.single())

        assertEquals("t", assertIs<VariableExpression>(index.receiver).name)
        assertEquals(1L, assertIs<IntegerExpression>(index.key).value)
    }

    @Test
    fun `parses dot index expressions`() {
        val chunk = Parser.parse("return t.answer")
        val statement = assertIs<ReturnStatement>(chunk.statements.single())
        val index = assertIs<IndexExpression>(statement.values.single())

        assertEquals("t", assertIs<VariableExpression>(index.receiver).name)
        assertEquals("answer", assertIs<StringExpression>(index.key).value)
    }

    @Test
    fun `parses indexed assignment targets`() {
        val chunk = Parser.parse("t[1] = 42")
        val statement = assertIs<AssignmentStatement>(chunk.statements.single())
        val target = assertIs<IndexAssignmentTarget>(statement.targets.single()).index

        assertEquals("t", assertIs<VariableExpression>(target.receiver).name)
        assertEquals(1L, assertIs<IntegerExpression>(target.key).value)
        assertEquals(42L, assertIs<IntegerExpression>(statement.values.single()).value)
    }

    @Test
    fun `parses dot assignment targets`() {
        val chunk = Parser.parse("t.answer = 42")
        val statement = assertIs<AssignmentStatement>(chunk.statements.single())
        val target = assertIs<IndexAssignmentTarget>(statement.targets.single()).index

        assertEquals("t", assertIs<VariableExpression>(target.receiver).name)
        assertEquals("answer", assertIs<StringExpression>(target.key).value)
        assertEquals(42L, assertIs<IntegerExpression>(statement.values.single()).value)
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
