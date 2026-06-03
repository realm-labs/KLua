package io.github.realmlabs.klua.core.parser

import io.github.realmlabs.klua.core.ast.BinaryExpression
import io.github.realmlabs.klua.core.ast.BinaryOperator
import io.github.realmlabs.klua.core.ast.AssignmentStatement
import io.github.realmlabs.klua.core.ast.AssignmentTarget
import io.github.realmlabs.klua.core.ast.BreakStatement
import io.github.realmlabs.klua.core.ast.BooleanExpression
import io.github.realmlabs.klua.core.ast.CallExpression
import io.github.realmlabs.klua.core.ast.CallStatement
import io.github.realmlabs.klua.core.ast.Chunk
import io.github.realmlabs.klua.core.ast.ElseIfBranch
import io.github.realmlabs.klua.core.ast.Expression
import io.github.realmlabs.klua.core.ast.FloatExpression
import io.github.realmlabs.klua.core.ast.FunctionExpression
import io.github.realmlabs.klua.core.ast.FunctionStatement
import io.github.realmlabs.klua.core.ast.IfStatement
import io.github.realmlabs.klua.core.ast.IndexExpression
import io.github.realmlabs.klua.core.ast.IndexAssignmentTarget
import io.github.realmlabs.klua.core.ast.IntegerExpression
import io.github.realmlabs.klua.core.ast.LocalAssignmentTarget
import io.github.realmlabs.klua.core.ast.LocalFunctionStatement
import io.github.realmlabs.klua.core.ast.LocalStatement
import io.github.realmlabs.klua.core.ast.NilExpression
import io.github.realmlabs.klua.core.ast.ListTableEntry
import io.github.realmlabs.klua.core.ast.NamedTableEntry
import io.github.realmlabs.klua.core.ast.NumericForStatement
import io.github.realmlabs.klua.core.ast.RepeatStatement
import io.github.realmlabs.klua.core.ast.ReturnStatement
import io.github.realmlabs.klua.core.ast.Statement
import io.github.realmlabs.klua.core.ast.StringExpression
import io.github.realmlabs.klua.core.ast.TableEntry
import io.github.realmlabs.klua.core.ast.TableExpression
import io.github.realmlabs.klua.core.ast.UnaryExpression
import io.github.realmlabs.klua.core.ast.UnaryOperator
import io.github.realmlabs.klua.core.ast.VarargExpression
import io.github.realmlabs.klua.core.ast.VariableExpression
import io.github.realmlabs.klua.core.ast.WhileStatement
import io.github.realmlabs.klua.core.lexer.Lexer
import io.github.realmlabs.klua.core.lexer.Token
import io.github.realmlabs.klua.core.lexer.TokenKind
import io.github.realmlabs.klua.core.source.SourceRange

internal class Parser private constructor(
    private val tokens: List<Token>,
) {
    private var current = 0

    fun parseChunk(): Chunk {
        val start = peek().range.start
        val statements = parseBlock(setOf(TokenKind.EOF))
        val eof = consume(TokenKind.EOF, "expected end of input")
        return Chunk(statements, SourceRange(start, eof.range.end))
    }

    private fun parseBlock(stopKinds: Set<TokenKind>): List<Statement> {
        val statements = mutableListOf<Statement>()
        while (!checkAny(stopKinds)) {
            if (match(TokenKind.SEMICOLON)) {
                continue
            }
            statements += statement()
        }
        return statements
    }

    private fun statement(): Statement {
        return when {
            match(TokenKind.LOCAL) -> localStatement(previous())
            match(TokenKind.RETURN) -> returnStatement(previous())
            match(TokenKind.BREAK) -> breakStatement(previous())
            match(TokenKind.IF) -> ifStatement(previous())
            match(TokenKind.WHILE) -> whileStatement(previous())
            match(TokenKind.REPEAT) -> repeatStatement(previous())
            match(TokenKind.FOR) -> forStatement(previous())
            match(TokenKind.FUNCTION) -> functionStatement(previous())
            check(TokenKind.IDENTIFIER) && checkNext(TokenKind.LEFT_PAREN) -> callStatement()
            check(TokenKind.IDENTIFIER) -> assignmentStatement()
            else -> throw errorAt(peek(), "expected statement")
        }
    }

    private fun localStatement(start: Token): Statement {
        if (match(TokenKind.FUNCTION)) {
            return localFunctionStatement(start, previous())
        }

        val names = mutableListOf<String>()
        do {
            val name = consume(TokenKind.IDENTIFIER, "expected local variable name")
            names += name.literal as String
        } while (match(TokenKind.COMMA))

        val values = if (match(TokenKind.ASSIGN)) expressionList() else emptyList()
        val end = values.lastOrNull()?.range?.end ?: previous().range.end
        return LocalStatement(names, values, SourceRange(start.range.start, end))
    }

    private fun localFunctionStatement(localStart: Token, functionStart: Token): LocalFunctionStatement {
        val name = consume(TokenKind.IDENTIFIER, "expected local function name")
        val function = functionBody(functionStart)
        return LocalFunctionStatement(
            name = name.literal as String,
            function = function,
            range = SourceRange(localStart.range.start, function.range.end),
        )
    }

    private fun functionStatement(start: Token): FunctionStatement {
        val name = consume(TokenKind.IDENTIFIER, "expected function name")
        val function = functionBody(start)
        return FunctionStatement(
            name = name.literal as String,
            function = function,
            range = SourceRange(start.range.start, function.range.end),
        )
    }

    private fun assignmentStatement(): AssignmentStatement {
        val start = peek()
        val targets = mutableListOf<AssignmentTarget>()
        do {
            targets += assignmentTarget()
        } while (match(TokenKind.COMMA))

        consume(TokenKind.ASSIGN, "expected '=' in assignment")
        val values = expressionList()
        val end = values.last().range.end
        return AssignmentStatement(targets, values, SourceRange(start.range.start, end))
    }

    private fun assignmentTarget(): AssignmentTarget {
        return when (val target = postfix()) {
            is VariableExpression -> LocalAssignmentTarget(target.name, target.range)
            is IndexExpression -> IndexAssignmentTarget(target)
            else -> throw ParserException(target.range.start, "expected assignment target")
        }
    }

    private fun callStatement(): CallStatement {
        val expression = expression()
        val call = expression as? CallExpression
            ?: throw errorAt(previous(), "expected function call statement")
        return CallStatement(call, call.range)
    }

    private fun returnStatement(start: Token): ReturnStatement {
        val values = if (isReturnTerminator(peek().kind)) {
            emptyList()
        } else {
            expressionList()
        }

        if (match(TokenKind.SEMICOLON)) {
            return ReturnStatement(values, SourceRange(start.range.start, previous().range.end))
        }

        val end = values.lastOrNull()?.range?.end ?: start.range.end
        return ReturnStatement(values, SourceRange(start.range.start, end))
    }

    private fun breakStatement(start: Token): BreakStatement {
        if (match(TokenKind.SEMICOLON)) {
            return BreakStatement(SourceRange(start.range.start, previous().range.end))
        }
        return BreakStatement(start.range)
    }

    private fun ifStatement(start: Token): IfStatement {
        val condition = expression()
        consume(TokenKind.THEN, "expected 'then' after if condition")
        val thenBlock = parseBlock(setOf(TokenKind.ELSEIF, TokenKind.ELSE, TokenKind.END))
        val elseifBranches = mutableListOf<ElseIfBranch>()

        while (match(TokenKind.ELSEIF)) {
            val branchStart = previous()
            val branchCondition = expression()
            consume(TokenKind.THEN, "expected 'then' after elseif condition")
            val block = parseBlock(setOf(TokenKind.ELSEIF, TokenKind.ELSE, TokenKind.END))
            val end = block.lastOrNull()?.range?.end ?: branchCondition.range.end
            elseifBranches += ElseIfBranch(branchCondition, block, SourceRange(branchStart.range.start, end))
        }

        val elseBlock = if (match(TokenKind.ELSE)) {
            parseBlock(setOf(TokenKind.END))
        } else {
            null
        }

        val end = consume(TokenKind.END, "expected 'end' after if statement")
        return IfStatement(condition, thenBlock, elseifBranches, elseBlock, SourceRange(start.range.start, end.range.end))
    }

    private fun whileStatement(start: Token): WhileStatement {
        val condition = expression()
        consume(TokenKind.DO, "expected 'do' after while condition")
        val block = parseBlock(setOf(TokenKind.END))
        val end = consume(TokenKind.END, "expected 'end' after while statement")
        return WhileStatement(condition, block, SourceRange(start.range.start, end.range.end))
    }

    private fun repeatStatement(start: Token): RepeatStatement {
        val block = parseBlock(setOf(TokenKind.UNTIL))
        consume(TokenKind.UNTIL, "expected 'until' after repeat block")
        val condition = expression()
        return RepeatStatement(block, condition, SourceRange(start.range.start, condition.range.end))
    }

    private fun forStatement(start: Token): NumericForStatement {
        val name = consume(TokenKind.IDENTIFIER, "expected loop variable name")
        consume(TokenKind.ASSIGN, "expected '=' after loop variable")
        val initial = expression()
        consume(TokenKind.COMMA, "expected ',' after for initial value")
        val limit = expression()
        val step = if (match(TokenKind.COMMA)) expression() else null
        consume(TokenKind.DO, "expected 'do' after for control values")
        val block = parseBlock(setOf(TokenKind.END))
        val end = consume(TokenKind.END, "expected 'end' after for statement")
        return NumericForStatement(
            name = name.literal as String,
            start = initial,
            limit = limit,
            step = step,
            block = block,
            range = SourceRange(start.range.start, end.range.end),
        )
    }

    private fun expressionList(): List<Expression> {
        val expressions = mutableListOf<Expression>()
        do {
            expressions += expression()
        } while (match(TokenKind.COMMA))
        return expressions
    }

    private fun expression(minPrecedence: Int = 1): Expression {
        var left = unary()

        while (true) {
            val binary = binaryOperator(peek().kind) ?: break
            if (binary.precedence < minPrecedence) {
                break
            }

            advance()
            val nextMinPrecedence = if (binary.rightAssociative) binary.precedence else binary.precedence + 1
            val right = expression(nextMinPrecedence)
            left = BinaryExpression(
                left = left,
                operator = binary.operator,
                right = right,
                range = SourceRange(left.range.start, right.range.end),
            )
        }

        return left
    }

    private fun unary(): Expression {
        val operator = when {
            match(TokenKind.MINUS) -> UnaryOperator.NEGATE
            match(TokenKind.NOT) -> UnaryOperator.NOT
            match(TokenKind.HASH) -> UnaryOperator.LENGTH
            match(TokenKind.TILDE) -> UnaryOperator.BITWISE_NOT
            else -> null
        }

        if (operator != null) {
            val token = previous()
            val expression = unary()
            return UnaryExpression(operator, expression, SourceRange(token.range.start, expression.range.end))
        }

        return postfix()
    }

    private fun postfix(): Expression {
        var expression = primary()

        while (true) {
            when {
                match(TokenKind.LEFT_PAREN) -> {
                    val arguments = if (check(TokenKind.RIGHT_PAREN)) {
                        emptyList()
                    } else {
                        expressionList()
                    }
                    val end = consume(TokenKind.RIGHT_PAREN, "expected ')' after function arguments")
                    expression = CallExpression(
                        callee = expression,
                        arguments = arguments,
                        range = SourceRange(expression.range.start, end.range.end),
                    )
                }
                match(TokenKind.LEFT_BRACKET) -> {
                    val key = expression()
                    val end = consume(TokenKind.RIGHT_BRACKET, "expected ']' after table key")
                    expression = IndexExpression(
                        receiver = expression,
                        key = key,
                        range = SourceRange(expression.range.start, end.range.end),
                    )
                }
                match(TokenKind.DOT) -> {
                    val name = consume(TokenKind.IDENTIFIER, "expected field name after '.'")
                    expression = IndexExpression(
                        receiver = expression,
                        key = StringExpression(name.literal as String, name.range),
                        range = SourceRange(expression.range.start, name.range.end),
                    )
                }
                else -> return expression
            }
        }
    }

    private fun primary(): Expression {
        return when {
            match(TokenKind.NIL) -> NilExpression(previous().range)
            match(TokenKind.TRUE) -> BooleanExpression(true, previous().range)
            match(TokenKind.FALSE) -> BooleanExpression(false, previous().range)
            match(TokenKind.INTEGER) -> IntegerExpression(previous().literal as Long, previous().range)
            match(TokenKind.FLOAT) -> FloatExpression(previous().literal as Double, previous().range)
            match(TokenKind.STRING) -> StringExpression(previous().literal as String, previous().range)
            match(TokenKind.IDENTIFIER) -> VariableExpression(previous().literal as String, previous().range)
            match(TokenKind.DOT_DOT_DOT) -> VarargExpression(previous().range)
            match(TokenKind.FUNCTION) -> functionBody(previous())
            match(TokenKind.LEFT_PAREN) -> parenthesizedExpression(previous())
            match(TokenKind.LEFT_BRACE) -> tableExpression(previous())
            else -> throw errorAt(peek(), "expected expression")
        }
    }

    private fun tableExpression(start: Token): TableExpression {
        val entries = mutableListOf<TableEntry>()
        while (!check(TokenKind.RIGHT_BRACE)) {
            entries += tableEntry()
            if (!match(TokenKind.COMMA) && !match(TokenKind.SEMICOLON)) {
                break
            }
        }
        val end = consume(TokenKind.RIGHT_BRACE, "expected '}' after table constructor")
        return TableExpression(entries, SourceRange(start.range.start, end.range.end))
    }

    private fun tableEntry(): TableEntry {
        if (check(TokenKind.IDENTIFIER) && checkNext(TokenKind.ASSIGN)) {
            val name = advance()
            consume(TokenKind.ASSIGN, "expected '=' after table field name")
            val value = expression()
            return NamedTableEntry(
                name = name.literal as String,
                value = value,
                range = SourceRange(name.range.start, value.range.end),
            )
        }

        val value = expression()
        return ListTableEntry(value, value.range)
    }

    private fun functionBody(start: Token): FunctionExpression {
        consume(TokenKind.LEFT_PAREN, "expected '(' after function")
        val parameters = mutableListOf<String>()
        var isVararg = false

        if (!check(TokenKind.RIGHT_PAREN)) {
            do {
                if (match(TokenKind.DOT_DOT_DOT)) {
                    isVararg = true
                    break
                }
                val parameter = consume(TokenKind.IDENTIFIER, "expected function parameter name")
                parameters += parameter.literal as String
            } while (match(TokenKind.COMMA))
        }

        consume(TokenKind.RIGHT_PAREN, "expected ')' after function parameters")
        val body = parseBlock(setOf(TokenKind.END))
        val end = consume(TokenKind.END, "expected 'end' after function body")
        return FunctionExpression(
            parameters = parameters,
            isVararg = isVararg,
            body = body,
            range = SourceRange(start.range.start, end.range.end),
        )
    }

    private fun parenthesizedExpression(start: Token): Expression {
        val expression = expression()
        val end = consume(TokenKind.RIGHT_PAREN, "expected ')' after expression")
        return when (expression) {
            is BinaryExpression -> expression.copy(range = SourceRange(start.range.start, end.range.end))
            is BooleanExpression -> expression.copy(range = SourceRange(start.range.start, end.range.end))
            is CallExpression -> expression.copy(range = SourceRange(start.range.start, end.range.end))
            is FloatExpression -> expression.copy(range = SourceRange(start.range.start, end.range.end))
            is FunctionExpression -> expression.copy(range = SourceRange(start.range.start, end.range.end))
            is IndexExpression -> expression.copy(range = SourceRange(start.range.start, end.range.end))
            is IntegerExpression -> expression.copy(range = SourceRange(start.range.start, end.range.end))
            is NilExpression -> expression.copy(range = SourceRange(start.range.start, end.range.end))
            is StringExpression -> expression.copy(range = SourceRange(start.range.start, end.range.end))
            is TableExpression -> expression.copy(range = SourceRange(start.range.start, end.range.end))
            is UnaryExpression -> expression.copy(range = SourceRange(start.range.start, end.range.end))
            is VarargExpression -> expression.copy(range = SourceRange(start.range.start, end.range.end))
            is VariableExpression -> expression.copy(range = SourceRange(start.range.start, end.range.end))
        }
    }

    private fun binaryOperator(kind: TokenKind): BinaryInfo? = when (kind) {
        TokenKind.OR -> BinaryInfo(BinaryOperator.OR, 1)
        TokenKind.AND -> BinaryInfo(BinaryOperator.AND, 2)
        TokenKind.EQUAL_EQUAL -> BinaryInfo(BinaryOperator.EQUAL, 3)
        TokenKind.NOT_EQUAL -> BinaryInfo(BinaryOperator.NOT_EQUAL, 3)
        TokenKind.LESS -> BinaryInfo(BinaryOperator.LESS, 3)
        TokenKind.LESS_EQUAL -> BinaryInfo(BinaryOperator.LESS_EQUAL, 3)
        TokenKind.GREATER -> BinaryInfo(BinaryOperator.GREATER, 3)
        TokenKind.GREATER_EQUAL -> BinaryInfo(BinaryOperator.GREATER_EQUAL, 3)
        TokenKind.PIPE -> BinaryInfo(BinaryOperator.BITWISE_OR, 4)
        TokenKind.TILDE -> BinaryInfo(BinaryOperator.BITWISE_XOR, 5)
        TokenKind.AMPERSAND -> BinaryInfo(BinaryOperator.BITWISE_AND, 6)
        TokenKind.LEFT_SHIFT -> BinaryInfo(BinaryOperator.LEFT_SHIFT, 7)
        TokenKind.RIGHT_SHIFT -> BinaryInfo(BinaryOperator.RIGHT_SHIFT, 7)
        TokenKind.DOT_DOT -> BinaryInfo(BinaryOperator.CONCAT, 8, rightAssociative = true)
        TokenKind.PLUS -> BinaryInfo(BinaryOperator.ADD, 9)
        TokenKind.MINUS -> BinaryInfo(BinaryOperator.SUBTRACT, 9)
        TokenKind.STAR -> BinaryInfo(BinaryOperator.MULTIPLY, 10)
        TokenKind.SLASH -> BinaryInfo(BinaryOperator.DIVIDE, 10)
        TokenKind.DOUBLE_SLASH -> BinaryInfo(BinaryOperator.FLOOR_DIVIDE, 10)
        TokenKind.PERCENT -> BinaryInfo(BinaryOperator.MODULO, 10)
        TokenKind.CARET -> BinaryInfo(BinaryOperator.POWER, 12, rightAssociative = true)
        else -> null
    }

    private fun isReturnTerminator(kind: TokenKind): Boolean {
        return kind == TokenKind.EOF ||
            kind == TokenKind.END ||
            kind == TokenKind.ELSE ||
            kind == TokenKind.ELSEIF ||
            kind == TokenKind.UNTIL ||
            kind == TokenKind.SEMICOLON
    }

    private fun match(kind: TokenKind): Boolean {
        if (!check(kind)) {
            return false
        }
        advance()
        return true
    }

    private fun consume(kind: TokenKind, message: String): Token {
        if (check(kind)) {
            if (kind == TokenKind.EOF) {
                return peek()
            }
            return advance()
        }
        throw errorAt(peek(), message)
    }

    private fun check(kind: TokenKind): Boolean = peek().kind == kind

    private fun checkNext(kind: TokenKind): Boolean = tokens.getOrNull(current + 1)?.kind == kind

    private fun checkAny(kinds: Set<TokenKind>): Boolean = peek().kind in kinds

    private fun advance(): Token {
        if (!isAtEnd()) {
            current++
        }
        return previous()
    }

    private fun isAtEnd(): Boolean = peek().kind == TokenKind.EOF

    private fun peek(): Token = tokens[current]

    private fun previous(): Token = tokens[current - 1]

    private fun errorAt(token: Token, message: String): ParserException = ParserException(token.range.start, message)

    private data class BinaryInfo(
        val operator: BinaryOperator,
        val precedence: Int,
        val rightAssociative: Boolean = false,
    )

    companion object {
        fun parse(source: String, sourceName: String = "chunk"): Chunk {
            return Parser(Lexer(source, sourceName).tokenize()).parseChunk()
        }
    }
}
