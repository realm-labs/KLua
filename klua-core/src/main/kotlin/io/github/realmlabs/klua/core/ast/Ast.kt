package io.github.realmlabs.klua.core.ast

import io.github.realmlabs.klua.core.source.SourceRange

internal data class Chunk(
    val statements: List<Statement>,
    val range: SourceRange,
)

internal sealed interface Statement {
    val range: SourceRange
}

internal data class LocalStatement(
    val names: List<String>,
    val values: List<Expression>,
    override val range: SourceRange,
) : Statement

internal data class AssignmentStatement(
    val names: List<String>,
    val values: List<Expression>,
    override val range: SourceRange,
) : Statement

internal data class ReturnStatement(
    val values: List<Expression>,
    override val range: SourceRange,
) : Statement

internal data class IfStatement(
    val condition: Expression,
    val thenBlock: List<Statement>,
    val elseifBranches: List<ElseIfBranch>,
    val elseBlock: List<Statement>?,
    override val range: SourceRange,
) : Statement

internal data class ElseIfBranch(
    val condition: Expression,
    val block: List<Statement>,
    val range: SourceRange,
)

internal sealed interface Expression {
    val range: SourceRange
}

internal data class NilExpression(
    override val range: SourceRange,
) : Expression

internal data class BooleanExpression(
    val value: Boolean,
    override val range: SourceRange,
) : Expression

internal data class IntegerExpression(
    val value: Long,
    override val range: SourceRange,
) : Expression

internal data class FloatExpression(
    val value: Double,
    override val range: SourceRange,
) : Expression

internal data class StringExpression(
    val value: String,
    override val range: SourceRange,
) : Expression

internal data class VariableExpression(
    val name: String,
    override val range: SourceRange,
) : Expression

internal data class UnaryExpression(
    val operator: UnaryOperator,
    val expression: Expression,
    override val range: SourceRange,
) : Expression

internal data class BinaryExpression(
    val left: Expression,
    val operator: BinaryOperator,
    val right: Expression,
    override val range: SourceRange,
) : Expression

internal enum class UnaryOperator {
    NEGATE,
    NOT,
    LENGTH,
    BITWISE_NOT,
}

internal enum class BinaryOperator {
    OR,
    AND,
    EQUAL,
    NOT_EQUAL,
    LESS,
    LESS_EQUAL,
    GREATER,
    GREATER_EQUAL,
    BITWISE_OR,
    BITWISE_XOR,
    BITWISE_AND,
    CONCAT,
    ADD,
    SUBTRACT,
    MULTIPLY,
    DIVIDE,
    FLOOR_DIVIDE,
    MODULO,
    POWER,
}
