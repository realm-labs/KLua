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
    val attributes: List<LocalAttribute> = List(names.size) { LocalAttribute.NONE },
    val values: List<Expression>,
    override val range: SourceRange,
) : Statement

internal enum class LocalAttribute {
    NONE,
    CONST,
    CLOSE,
}

internal data class LocalFunctionStatement(
    val name: String,
    val function: FunctionExpression,
    override val range: SourceRange,
) : Statement

internal data class FunctionStatement(
    val name: String,
    val function: FunctionExpression,
    override val range: SourceRange,
) : Statement

internal data class AssignmentStatement(
    val targets: List<AssignmentTarget>,
    val values: List<Expression>,
    override val range: SourceRange,
) : Statement

internal sealed interface AssignmentTarget {
    val range: SourceRange
}

internal data class LocalAssignmentTarget(
    val name: String,
    override val range: SourceRange,
) : AssignmentTarget

internal data class IndexAssignmentTarget(
    val index: IndexExpression,
) : AssignmentTarget {
    override val range: SourceRange = index.range
}

internal data class CallStatement(
    val call: Expression,
    override val range: SourceRange,
) : Statement

internal data class ReturnStatement(
    val values: List<Expression>,
    override val range: SourceRange,
) : Statement

internal data class BreakStatement(
    override val range: SourceRange,
) : Statement

internal data class DoStatement(
    val block: List<Statement>,
    override val range: SourceRange,
) : Statement

internal data class IfStatement(
    val condition: Expression,
    val thenBlock: List<Statement>,
    val elseifBranches: List<ElseIfBranch>,
    val elseBlock: List<Statement>?,
    override val range: SourceRange,
) : Statement

internal data class WhileStatement(
    val condition: Expression,
    val block: List<Statement>,
    override val range: SourceRange,
) : Statement

internal data class RepeatStatement(
    val block: List<Statement>,
    val condition: Expression,
    override val range: SourceRange,
) : Statement

internal data class NumericForStatement(
    val name: String,
    val start: Expression,
    val limit: Expression,
    val step: Expression?,
    val block: List<Statement>,
    override val range: SourceRange,
) : Statement

internal data class GenericForStatement(
    val names: List<String>,
    val values: List<Expression>,
    val block: List<Statement>,
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

internal data class VarargExpression(
    override val range: SourceRange,
) : Expression

internal data class FunctionExpression(
    val parameters: List<String>,
    val isVararg: Boolean,
    val body: List<Statement>,
    override val range: SourceRange,
) : Expression

internal data class CallExpression(
    val callee: Expression,
    val arguments: List<Expression>,
    override val range: SourceRange,
) : Expression

internal data class MethodCallExpression(
    val receiver: Expression,
    val methodName: String,
    val arguments: List<Expression>,
    override val range: SourceRange,
) : Expression

internal data class IndexExpression(
    val receiver: Expression,
    val key: Expression,
    override val range: SourceRange,
) : Expression

internal data class TableExpression(
    val entries: List<TableEntry>,
    override val range: SourceRange,
) : Expression

internal sealed interface TableEntry {
    val range: SourceRange
}

internal data class ListTableEntry(
    val value: Expression,
    override val range: SourceRange,
) : TableEntry

internal data class NamedTableEntry(
    val name: String,
    val value: Expression,
    override val range: SourceRange,
) : TableEntry

internal data class KeyedTableEntry(
    val key: Expression,
    val value: Expression,
    override val range: SourceRange,
) : TableEntry

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
    LEFT_SHIFT,
    RIGHT_SHIFT,
    CONCAT,
    ADD,
    SUBTRACT,
    MULTIPLY,
    DIVIDE,
    FLOOR_DIVIDE,
    MODULO,
    POWER,
}
