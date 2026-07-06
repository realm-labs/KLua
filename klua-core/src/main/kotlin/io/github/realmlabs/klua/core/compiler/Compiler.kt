package io.github.realmlabs.klua.core.compiler

import io.github.realmlabs.klua.core.ast.AssignmentStatement
import io.github.realmlabs.klua.core.ast.BinaryExpression
import io.github.realmlabs.klua.core.ast.BinaryOperator
import io.github.realmlabs.klua.core.ast.BooleanExpression
import io.github.realmlabs.klua.core.ast.BreakStatement
import io.github.realmlabs.klua.core.ast.CallExpression
import io.github.realmlabs.klua.core.ast.CallStatement
import io.github.realmlabs.klua.core.ast.Chunk
import io.github.realmlabs.klua.core.ast.DoStatement
import io.github.realmlabs.klua.core.ast.Expression
import io.github.realmlabs.klua.core.ast.FloatExpression
import io.github.realmlabs.klua.core.ast.FunctionExpression
import io.github.realmlabs.klua.core.ast.FunctionStatement
import io.github.realmlabs.klua.core.ast.GenericForStatement
import io.github.realmlabs.klua.core.ast.GlobalFunctionStatement
import io.github.realmlabs.klua.core.ast.GlobalStatement
import io.github.realmlabs.klua.core.ast.GotoStatement
import io.github.realmlabs.klua.core.ast.IfStatement
import io.github.realmlabs.klua.core.ast.IndexExpression
import io.github.realmlabs.klua.core.ast.IndexAssignmentTarget
import io.github.realmlabs.klua.core.ast.IntegerExpression
import io.github.realmlabs.klua.core.ast.KeyedTableEntry
import io.github.realmlabs.klua.core.ast.LabelStatement
import io.github.realmlabs.klua.core.ast.ListTableEntry
import io.github.realmlabs.klua.core.ast.LocalAssignmentTarget
import io.github.realmlabs.klua.core.ast.LocalAttribute
import io.github.realmlabs.klua.core.ast.LocalFunctionStatement
import io.github.realmlabs.klua.core.ast.LocalStatement
import io.github.realmlabs.klua.core.ast.MethodCallExpression
import io.github.realmlabs.klua.core.ast.NamedTableEntry
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
import io.github.realmlabs.klua.core.bytecode.CallSiteInfo
import io.github.realmlabs.klua.core.bytecode.Instruction
import io.github.realmlabs.klua.core.bytecode.LocalVarInfo
import io.github.realmlabs.klua.core.bytecode.OPEN_RESULT_COUNT
import io.github.realmlabs.klua.core.bytecode.Opcode
import io.github.realmlabs.klua.core.bytecode.Prototype
import io.github.realmlabs.klua.core.bytecode.UpvalueDescriptor
import io.github.realmlabs.klua.core.bytecode.UpvalueSource
import io.github.realmlabs.klua.core.parser.Parser
import io.github.realmlabs.klua.core.source.SourcePosition
import io.github.realmlabs.klua.core.value.LuaFloat
import io.github.realmlabs.klua.core.value.LuaInteger
import io.github.realmlabs.klua.core.value.LuaString

internal class Compiler private constructor(
    private val sourceName: String,
    private val isVarargFunction: Boolean = false,
    private val parentLocalResolver: ((String) -> Int?)? = null,
    private val parentUpvalueResolver: ((String) -> Int?)? = null,
    private val parentConstResolver: ((String) -> Boolean)? = null,
    private val parentGlobalResolver: ((String) -> GlobalLookup)? = null,
) {
    private val writer = BytecodeWriter()
    private val constants = ConstantPool()
    private val nested = mutableListOf<Prototype>()
    private val locals = linkedMapOf<String, Int>()
    private val localAttributes = linkedMapOf<String, LocalAttribute>()
    private val globalDeclarations = mutableListOf<GlobalDeclaration>()
    private val upvalues = mutableListOf<UpvalueDescriptor>()
    private val upvalueIndexes = linkedMapOf<String, Int>()
    private val localVars = mutableListOf<LocalVarBuilder>()
    private val callSiteInfo = mutableListOf<CallSiteInfo>()
    private val loopBreaks = mutableListOf<MutableList<BreakJump>>()
    private val activeLabels = mutableListOf<LabelTarget>()
    private val pendingGotos = mutableListOf<PendingGoto>()
    private val blockScopePath = mutableListOf(0)
    private var nextLocalRegister = 0
    private var maxRegister = 0
    private var hasCapturedLocals = false
    private var nextBlockScopeId = 1

    fun compile(chunk: Chunk): Prototype {
        if (chunk.statements.isEmpty()) {
            emitImplicitReturn(chunk.range.start.line)
        } else {
            compileStatements(chunk.statements, endLabelLocalDepth = 0)
            resolvePendingGotos()
            if (chunk.statements.last() !is ReturnStatement) {
                emitImplicitReturn(chunk.range.end.line)
            }
        }

        return Prototype(
            sourceName = sourceName,
            code = writer.code(),
            constants = constants.toArray(),
            nested = nested.toTypedArray(),
            upvalues = upvalues.toTypedArray(),
            localVars = localVarInfo(writer.size),
            lineInfo = writer.lineInfo(),
            callSiteInfo = callSiteInfo.toTypedArray(),
            maxStackSize = maxRegister.coerceAtLeast(1),
            isVararg = isVarargFunction,
        )
    }

    private fun compileStatements(statements: List<Statement>, endLabelLocalDepth: Int? = null) {
        for ((index, statement) in statements.withIndex()) {
            when (statement) {
                is LocalStatement -> compileLocal(statement)
                is GlobalStatement -> compileGlobal(statement)
                is GlobalFunctionStatement -> compileGlobalFunction(statement)
                is AssignmentStatement -> compileAssignment(statement)
                is CallStatement -> compileCallStatement(statement)
                is DoStatement -> compileScopedBlock(statement.block)
                is IfStatement -> compileIf(statement)
                is WhileStatement -> compileWhile(statement)
                is RepeatStatement -> compileRepeat(statement)
                is NumericForStatement -> compileNumericFor(statement)
                is GenericForStatement -> compileGenericFor(statement)
                is FunctionStatement -> compileFunctionStatement(statement)
                is LocalFunctionStatement -> compileLocalFunction(statement)
                is ReturnStatement -> compileReturn(statement)
                is BreakStatement -> compileBreak(statement)
                is GotoStatement -> compileGoto(statement)
                is LabelStatement -> compileLabel(statement, endLabelLocalDepth?.takeIf {
                    statements.drop(index + 1).all { following -> following is LabelStatement }
                })
            }
        }
    }

    private fun compileCallStatement(statement: CallStatement) {
        when (val call = statement.call) {
            is CallExpression -> compileCallExpression(call, nextLocalRegister, 0)
            is MethodCallExpression -> compileMethodCallExpression(call, nextLocalRegister, 0)
            else -> throw unsupported(statement, "not a call statement")
        }
    }

    private fun compileScopedBlock(statements: List<Statement>) {
        val savedLocals = LinkedHashMap(locals)
        val savedLocalAttributes = LinkedHashMap(localAttributes)
        val savedNextLocalRegister = nextLocalRegister
        val savedGlobalDeclarationCount = globalDeclarations.size
        enterBlockScope()
        compileStatements(statements, endLabelLocalDepth = savedNextLocalRegister)
        exitBlockScope(savedNextLocalRegister)
        if (hasCapturedLocals) {
            writer.emit(
                Instruction.abc(Opcode.CLOSE_UPVALUES, savedNextLocalRegister),
                statements.lastOrNull()?.range?.end?.line ?: 0,
            )
        }
        restoreLocals(savedLocals, savedLocalAttributes, savedNextLocalRegister)
        restoreGlobalDeclarations(savedGlobalDeclarationCount)
    }

    private fun compileNumericFor(statement: NumericForStatement) {
        val breaks = pushLoopBreaks()
        val savedLocals = LinkedHashMap(locals)
        val savedLocalAttributes = LinkedHashMap(localAttributes)
        val savedNextLocalRegister = nextLocalRegister
        val savedGlobalDeclarationCount = globalDeclarations.size
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

        registerLocal(statement.name, baseRegister, LocalAttribute.CONST)
        val testIndex = writer.size
        writer.emit(Instruction.abc(Opcode.FOR_TEST, baseRegister), statement.range.start.line)
        val loopStart = writer.size

        enterBlockScope()
        compileStatements(statement.block, endLabelLocalDepth = nextLocalRegister)
        exitBlockScope(savedNextLocalRegister)
        if (hasCapturedLocals) {
            writer.emit(
                Instruction.abc(Opcode.CLOSE_UPVALUES, savedNextLocalRegister),
                statement.range.start.line,
            )
        }

        val loopIndex = writer.size
        writer.emit(Instruction.abc(Opcode.FOR_LOOP, baseRegister, writer.jumpOffset(loopIndex, loopStart)), statement.range.start.line)
        patchForTest(testIndex, writer.size)
        patchLoopBreaks(breaks, writer.size, savedNextLocalRegister)

        restoreLocals(savedLocals, savedLocalAttributes, savedNextLocalRegister)
        restoreGlobalDeclarations(savedGlobalDeclarationCount)
    }

    private fun compileGenericFor(statement: GenericForStatement) {
        val breaks = pushLoopBreaks()
        val savedLocals = LinkedHashMap(locals)
        val savedLocalAttributes = LinkedHashMap(localAttributes)
        val savedNextLocalRegister = nextLocalRegister
        val savedGlobalDeclarationCount = globalDeclarations.size
        val iteratorBase = nextLocalRegister
        val valueBase = iteratorBase + 3
        val valueSlots = maxOf(statement.names.size, 3)
        nextLocalRegister += 3 + valueSlots
        maxRegister = maxRegister.coerceAtLeast(nextLocalRegister)

        compileIteratorValues(statement.values, iteratorBase, statement.range.start.line)

        for ((index, name) in statement.names.withIndex()) {
            val attribute = if (index == 0) LocalAttribute.CONST else LocalAttribute.NONE
            registerLocal(name, valueBase + index, attribute)
        }

        val loopStart = writer.size
        writer.emit(Instruction.abc(Opcode.MOVE, valueBase, iteratorBase), statement.range.start.line)
        writer.emit(Instruction.abc(Opcode.MOVE, valueBase + 1, iteratorBase + 1), statement.range.start.line)
        writer.emit(Instruction.abc(Opcode.MOVE, valueBase + 2, iteratorBase + 2), statement.range.start.line)
        emitCall(
            valueBase,
            2,
            statement.names.size,
            statement.range.start.line,
            CallSiteInfo(writer.size, "for iterator", "for iterator"),
        )
        writer.emit(Instruction.abc(Opcode.MOVE, iteratorBase + 2, valueBase), statement.range.start.line)

        val testIndex = writer.size
        writer.emit(Instruction.abc(Opcode.TEST, valueBase), statement.range.start.line)

        enterBlockScope()
        compileStatements(statement.block, endLabelLocalDepth = nextLocalRegister)
        exitBlockScope(savedNextLocalRegister)
        if (hasCapturedLocals) {
            writer.emit(
                Instruction.abc(Opcode.CLOSE_UPVALUES, savedNextLocalRegister),
                statement.range.start.line,
            )
        }

        val backJump = writer.size
        writer.emit(Instruction.abc(Opcode.JMP, 0), statement.range.start.line)
        patchJump(backJump, loopStart)
        patchTest(testIndex, writer.size)
        patchLoopBreaks(breaks, writer.size, savedNextLocalRegister)

        restoreLocals(savedLocals, savedLocalAttributes, savedNextLocalRegister)
        restoreGlobalDeclarations(savedGlobalDeclarationCount)
    }

    private fun compileIteratorValues(values: List<Expression>, baseRegister: Int, line: Int) {
        compileAdjustedValues(values, baseRegister, 3, line)
    }

    private fun compileLocal(statement: LocalStatement) {
        if (statement.attributes.any { attribute -> attribute == LocalAttribute.CLOSE }) {
            throw unsupported(statement, "to-be-closed local variables are not supported")
        }

        val slots = statement.names.map { name ->
            val slot = nextLocalRegister++
            maxRegister = maxRegister.coerceAtLeast(slot + 1)
            slot
        }

        compileAdjustedValues(statement.values, slots.first(), slots.size, statement.range.start.line)
        registerLocalDeclarations(statement, slots)
    }

    private fun compileGlobal(statement: GlobalStatement) {
        if (statement.wildcard) {
            globalDeclarations += GlobalDeclaration(name = null, isConst = statement.attributes.singleOrNull() == LocalAttribute.CONST)
            return
        }
        if (statement.values.isEmpty()) {
            declareGlobalNames(statement.names, statement.attributes)
            return
        }

        val valueBase = nextLocalRegister
        compileAdjustedValues(statement.values, valueBase, statement.names.size, statement.range.start.line)
        for (index in statement.names.indices.reversed()) {
            val name = stringConstantIndex(statement.names[index])
            writer.emit(Instruction.abc(Opcode.CHECK_GLOBAL_NIL, name), statement.range.start.line)
            writer.emit(Instruction.abc(Opcode.SET_GLOBAL, name, valueBase + index), statement.range.start.line)
        }
        declareGlobalNames(statement.names, statement.attributes)
    }

    private fun declareGlobalNames(names: List<String>, attributes: List<LocalAttribute> = List(names.size) { LocalAttribute.NONE }) {
        for ((index, name) in names.withIndex()) {
            globalDeclarations += GlobalDeclaration(name, isConst = attributes[index] == LocalAttribute.CONST)
        }
    }

    private fun registerLocalDeclarations(statement: LocalStatement, slots: List<Int>) {
        for ((index, name) in statement.names.withIndex()) {
            registerLocal(name, slots[index], statement.attributes[index])
        }
    }

    private fun registerLocal(name: String, slot: Int, attribute: LocalAttribute) {
        locals[name] = slot
        localAttributes[name] = attribute
        localVars += LocalVarBuilder(name, slot, writer.size)
    }

    private fun compileLocalFunction(statement: LocalFunctionStatement) {
        val slot = nextLocalRegister++
        maxRegister = maxRegister.coerceAtLeast(slot + 1)
        registerLocal(statement.name, slot, LocalAttribute.NONE)
        compileFunctionExpression(statement.function, slot)
    }

    private fun compileFunctionStatement(statement: FunctionStatement) {
        val register = nextLocalRegister
        compileFunctionExpression(statement.function, register)
        val name = stringConstantIndex(statement.name)
        writer.emit(Instruction.abc(Opcode.SET_GLOBAL, name, register), statement.range.start.line)
    }

    private fun compileGlobalFunction(statement: GlobalFunctionStatement) {
        declareGlobalNames(listOf(statement.name))
        val register = nextLocalRegister
        compileFunctionExpression(statement.function, register)
        val name = stringConstantIndex(statement.name)
        writer.emit(Instruction.abc(Opcode.CHECK_GLOBAL_NIL, name), statement.range.start.line)
        writer.emit(Instruction.abc(Opcode.SET_GLOBAL, name, register), statement.range.start.line)
    }

    private fun compileAssignment(statement: AssignmentStatement) {
        val targetCount = statement.targets.size
        val preparedTargets = prepareAssignmentTargets(statement, nextLocalRegister)
        val tempBase = nextLocalRegister + preparedTargets.registerCount
        compileAdjustedValues(statement.values, tempBase, targetCount, statement.range.start.line)
        assignTargets(statement, preparedTargets.targets, tempBase)
    }

    private fun compileAdjustedValues(
        values: List<Expression>,
        baseRegister: Int,
        targetCount: Int,
        line: Int,
    ) {
        for ((index, value) in values.withIndex()) {
            val register = if (index < targetCount) baseRegister + index else baseRegister + targetCount
            if (index == values.lastIndex && value.isOpenResultExpression()) {
                compileOpenResultExpression(value, register, (targetCount - index).coerceAtLeast(0))
            } else {
                compileExpression(value, register)
            }
        }

        if (!values.lastOrNull().isOpenResultExpression()) {
            for (index in values.size until targetCount) {
                writer.emit(Instruction.abc(Opcode.LOAD_NIL, baseRegister + index), line)
                maxRegister = maxRegister.coerceAtLeast(baseRegister + index + 1)
            }
        }
    }

    private fun prepareAssignmentTargets(
        statement: AssignmentStatement,
        baseRegister: Int,
    ): PreparedAssignmentTargets {
        val targets = mutableListOf<PreparedAssignmentTarget>()
        var nextRegister = baseRegister
        for (target in statement.targets) {
            when (target) {
                is LocalAssignmentTarget -> {
                    validateAssignmentTarget(target, statement)
                    targets += PreparedAssignmentTarget.Local(target)
                }
                is IndexAssignmentTarget -> {
                    val receiverRegister = nextRegister++
                    compileExpression(target.index.receiver, receiverRegister)
                    val key = if (target.index.key is StringExpression) {
                        PreparedAssignmentKey.Field(target.index.key.value)
                    } else {
                        val keyRegister = nextRegister++
                        compileExpression(target.index.key, keyRegister)
                        PreparedAssignmentKey.Register(keyRegister)
                    }
                    targets += PreparedAssignmentTarget.Index(target, receiverRegister, key)
                }
            }
        }
        return PreparedAssignmentTargets(targets, nextRegister - baseRegister)
    }

    private fun validateAssignmentTarget(target: LocalAssignmentTarget, statement: AssignmentStatement) {
        if (locals[target.name] != null && localAttributes[target.name] == LocalAttribute.CONST) {
            throw constAssignmentError(target)
        }
        if (locals[target.name] == null && parentConstResolver?.invoke(target.name) == true) {
            throw constAssignmentError(target)
        }
        val upvalue = if (locals[target.name] == null) resolveUpvalue(target.name) else null
        if (upvalue != null && upvalue > 255) {
            throw unsupported(statement, "too many upvalues")
        }
        if (locals[target.name] == null && upvalue == null) {
            val global = requireDeclaredGlobal(target.name, target.range.start)
            if (global.isConst) {
                throw constAssignmentError(target)
            }
        }
    }

    private fun assignTargets(
        statement: AssignmentStatement,
        targets: List<PreparedAssignmentTarget>,
        valueBase: Int,
    ) {
        for ((index, target) in targets.withIndex()) {
            when (target) {
                is PreparedAssignmentTarget.Local -> assignLocalTarget(statement, target.target, valueBase + index)
                is PreparedAssignmentTarget.Index -> {
                    when (val key = target.key) {
                        is PreparedAssignmentKey.Field -> {
                            val field = stringConstantIndex(key.name)
                            writer.emit(
                                Instruction.abc(Opcode.SET_FIELD, target.receiverRegister, field, valueBase + index),
                                target.target.range.start.line,
                            )
                        }
                        is PreparedAssignmentKey.Register -> {
                            writer.emit(
                                Instruction.abc(Opcode.SET_TABLE, target.receiverRegister, key.register, valueBase + index),
                                target.target.range.start.line,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun assignLocalTarget(statement: AssignmentStatement, target: LocalAssignmentTarget, valueRegister: Int) {
        val targetSlot = locals[target.name]
        if (targetSlot != null) {
            writer.emit(Instruction.abc(Opcode.MOVE, targetSlot, valueRegister), statement.range.start.line)
        } else {
            val upvalue = resolveUpvalue(target.name)
            if (upvalue != null) {
                writer.emit(Instruction.abc(Opcode.SET_UPVALUE, upvalue, valueRegister), statement.range.start.line)
            } else {
                val name = stringConstantIndex(target.name)
                writer.emit(Instruction.abc(Opcode.SET_GLOBAL, name, valueRegister), statement.range.start.line)
            }
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
        val savedNextLocalRegister = nextLocalRegister
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
        patchLoopBreaks(breaks, writer.size, savedNextLocalRegister)
    }

    private fun compileRepeat(statement: RepeatStatement) {
        val breaks = pushLoopBreaks()
        val savedLocals = LinkedHashMap(locals)
        val savedLocalAttributes = LinkedHashMap(localAttributes)
        val savedNextLocalRegister = nextLocalRegister
        val savedGlobalDeclarationCount = globalDeclarations.size
        val loopStart = writer.size

        enterBlockScope()
        compileStatements(statement.block, endLabelLocalDepth = savedNextLocalRegister)
        exitBlockScope(savedNextLocalRegister)

        val conditionRegister = nextLocalRegister
        compileExpression(statement.condition, conditionRegister)

        val testIndex = writer.size
        writer.emit(Instruction.abc(Opcode.TEST, conditionRegister), statement.condition.range.start.line)
        if (hasCapturedLocals) {
            writer.emit(Instruction.abc(Opcode.CLOSE_UPVALUES, savedNextLocalRegister), statement.condition.range.start.line)
            val exitJump = writer.size
            writer.emit(Instruction.abc(Opcode.JMP, 0), statement.condition.range.start.line)
            patchTest(testIndex, writer.size)
            writer.emit(Instruction.abc(Opcode.CLOSE_UPVALUES, savedNextLocalRegister), statement.condition.range.start.line)
            val repeatJump = writer.size
            writer.emit(Instruction.abc(Opcode.JMP, 0), statement.condition.range.start.line)
            patchJump(repeatJump, loopStart)
            patchJump(exitJump, writer.size)
        } else {
            patchTest(testIndex, loopStart)
        }
        patchLoopBreaks(breaks, writer.size, savedNextLocalRegister)

        restoreLocals(savedLocals, savedLocalAttributes, savedNextLocalRegister)
        restoreGlobalDeclarations(savedGlobalDeclarationCount)
    }

    private fun compileBreak(statement: BreakStatement) {
        val breaks = loopBreaks.lastOrNull()
            ?: throw unsupported(statement, "'break' outside loop")
        val breakJump = writer.size
        writer.emit(Instruction.abc(Opcode.JMP, 0), statement.range.start.line)
        val close = if (hasCapturedLocals) {
            writer.size.also { writer.emit(Instruction.abc(Opcode.CLOSE_UPVALUES, 0), statement.range.start.line) }
        } else {
            null
        }
        breaks += BreakJump(breakJump, close, nextLocalRegister)
    }

    private fun compileGoto(statement: GotoStatement) {
        val jump = writer.size
        writer.emit(Instruction.abc(Opcode.JMP, 0), statement.range.start.line)
        val close = writer.size
        writer.emit(Instruction.abc(Opcode.CLOSE_UPVALUES, 0), statement.range.start.line)
        val pending = PendingGoto(
            name = statement.label,
            jump = jump,
            close = close,
            line = statement.range.start.line,
            localDepth = nextLocalRegister,
            scopePath = blockScopePath.toList(),
            closeDepth = null,
            statement = statement,
        )
        val label = findVisibleLabel(statement.label, blockScopePath)
        if (label == null) {
            pendingGotos += pending
            return
        }
        patchGoto(pending, label)
    }

    private fun compileLabel(statement: LabelStatement, localDepthOverride: Int? = null) {
        val duplicate = activeLabels.firstOrNull { label -> label.name == statement.name }
        if (duplicate != null) {
            throw unsupported(
                statement,
                "label '${statement.name}' already defined on line ${duplicate.line}",
            )
        }
        val label = LabelTarget(
            name = statement.name,
            pc = writer.size,
            line = statement.range.start.line,
            localDepth = localDepthOverride ?: nextLocalRegister,
            scopePath = blockScopePath.toList(),
        )
        activeLabels += label

        val matching = pendingGotos.filter { pending ->
            pending.name == statement.name && pending.scopePath == label.scopePath
        }
        for (pending in matching) {
            patchGoto(pending, label)
        }
        pendingGotos.removeAll(matching.toSet())
    }

    private fun findVisibleLabel(name: String, scopePath: List<Int>): LabelTarget? {
        return activeLabels.lastOrNull { label -> label.name == name && scopePath.hasPrefix(label.scopePath) }
    }

    private fun patchGoto(goto: PendingGoto, label: LabelTarget) {
        if (!goto.scopePath.hasPrefix(label.scopePath)) {
            throw unsupported(goto.statement, "goto across block scopes is not supported")
        }
        if (goto.localDepth < label.localDepth) {
            throw unsupported(
                goto.statement,
                "<goto ${goto.name}> at line ${goto.line} " +
                    "jumps into the scope of '${localNameAtDepth(goto.localDepth, label)}'",
            )
        }
        val closeDepth = goto.closeDepth ?: label.localDepth.takeIf { depth ->
            hasCapturedLocals && depth < goto.localDepth
        }
        if (closeDepth != null) {
            writer.patch(goto.jump, Instruction.abc(Opcode.CLOSE_UPVALUES, closeDepth))
            patchJump(goto.close, label.pc)
            return
        }
        patchJump(goto.jump, label.pc)
    }

    private fun localNameAtDepth(localDepth: Int, label: LabelTarget): String {
        return localVars.lastOrNull { local ->
            val endPc = local.endPc
            local.slot == localDepth &&
                local.startPc <= label.pc &&
                (endPc == null || label.pc <= endPc)
        }?.name ?: "*"
    }

    private fun resolvePendingGotos() {
        val pending = pendingGotos.firstOrNull() ?: return
        throw unsupported(pending.statement, "no visible label '${pending.name}' for <goto> at line ${pending.line}")
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
        if (hasCapturedLocals) {
            writer.emit(Instruction.abc(Opcode.CLOSE_UPVALUES, 0), statement.range.start.line)
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
            is IndexExpression -> compileIndexExpression(expression, register)
            is CallExpression -> compileCallExpression(expression, register)
            is MethodCallExpression -> compileMethodCallExpression(expression, register)
            is VariableExpression -> compileVariable(expression, register)
            is VarargExpression -> compileVarargExpression(expression, register, 1)
            is FunctionExpression -> compileFunctionExpression(expression, register)
            is TableExpression -> compileTableExpression(expression, register)
            is UnaryExpression -> compileUnaryExpression(expression, register)
            is BinaryExpression -> compileBinaryExpression(expression, register)
        }
    }

    private fun compileIndexExpression(expression: IndexExpression, register: Int) {
        if (expression.key is StringExpression) {
            compileExpression(expression.receiver, register)
            val field = stringConstantIndex(expression.key.value)
            writer.emit(Instruction.abc(Opcode.GET_FIELD, register, register, field), expression.range.start.line)
            return
        }

        val keyRegister = register + 1
        compileExpression(expression.receiver, register)
        compileExpression(expression.key, keyRegister)
        writer.emit(Instruction.abc(Opcode.GET_TABLE, register, register, keyRegister), expression.range.start.line)
    }

    private fun compileTableExpression(expression: TableExpression, register: Int) {
        writer.emit(Instruction.abc(Opcode.NEW_TABLE, register), expression.range.start.line)
        if (expression.entries.isEmpty()) {
            return
        }

        val keyRegister = register + 1
        val valueRegister = register + 2
        maxRegister = maxRegister.coerceAtLeast(valueRegister + 1)
        var listIndex = 1L
        for (entry in expression.entries) {
            when (entry) {
                is ListTableEntry -> {
                    compileExpression(entry.value, valueRegister)
                    emitInteger(keyRegister, listIndex, entry.range.start.line)
                    listIndex++
                }
                is NamedTableEntry -> {
                    compileExpression(entry.value, valueRegister)
                    val field = stringConstantIndex(entry.name)
                    writer.emit(Instruction.abc(Opcode.SET_FIELD, register, field, valueRegister), entry.range.start.line)
                }
                is KeyedTableEntry -> {
                    compileExpression(entry.key, keyRegister)
                    compileExpression(entry.value, valueRegister)
                    writer.emit(Instruction.abc(Opcode.SET_TABLE, register, keyRegister, valueRegister), entry.range.start.line)
                }
            }
            if (entry is ListTableEntry) {
                writer.emit(Instruction.abc(Opcode.SET_TABLE, register, keyRegister, valueRegister), entry.range.start.line)
            }
        }
    }

    private fun compileOpenResultExpression(
        expression: Expression,
        register: Int,
        resultCount: Int = OPEN_RESULT_COUNT,
    ) {
        when (expression) {
            is CallExpression -> compileCallExpression(expression, register, resultCount)
            is MethodCallExpression -> compileMethodCallExpression(expression, register, resultCount)
            is VarargExpression -> compileVarargExpression(expression, register, resultCount)
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
        emitCall(register, argumentCount, resultCount, expression.range.start.line, callSiteInfo(expression.callee))
    }

    private fun compileMethodCallExpression(expression: MethodCallExpression, register: Int, resultCount: Int = 1) {
        if (expression.arguments.size >= OPEN_RESULT_COUNT - 1) {
            throw unsupported(expression, "too many method arguments")
        }
        if (resultCount !in 0..255) {
            throw unsupported(expression, "too many function results")
        }

        compileExpression(expression.receiver, register + 1)
        val method = stringConstantIndex(expression.methodName)
        writer.emit(Instruction.abc(Opcode.GET_FIELD, register, register + 1, method), expression.range.start.line)
        val argumentCount = if (expression.arguments.lastOrNull().isOpenResultExpression()) {
            val lastIndex = expression.arguments.lastIndex
            for (index in 0 until lastIndex) {
                compileExpression(expression.arguments[index], register + index + 2)
            }
            compileOpenResultExpression(expression.arguments[lastIndex], register + lastIndex + 2)
            OPEN_RESULT_COUNT
        } else {
            for ((index, argument) in expression.arguments.withIndex()) {
                compileExpression(argument, register + index + 2)
            }
            expression.arguments.size + 1
        }
        val minimumResultSlots = if (resultCount == OPEN_RESULT_COUNT) 1 else resultCount
        maxRegister = maxRegister.coerceAtLeast(register + maxOf(expression.arguments.size + 2, minimumResultSlots))
        emitCall(
            register,
            argumentCount,
            resultCount,
            expression.range.start.line,
            CallSiteInfo(writer.size, expression.methodName, "method"),
        )
    }

    private fun emitCall(register: Int, argumentCount: Int, resultCount: Int, line: Int, info: CallSiteInfo?) {
        val pc = writer.size
        writer.emit(Instruction.abc(Opcode.CALL, register, argumentCount, resultCount), line)
        if (info != null) {
            callSiteInfo += info.copy(pc = pc)
        }
    }

    private fun callSiteInfo(callee: Expression): CallSiteInfo? {
        return when (callee) {
            is VariableExpression -> CallSiteInfo(0, callee.name, callSiteNameWhat(callee.name))
            is IndexExpression -> {
                val name = when (val key = callee.key) {
                    is StringExpression -> key.value
                    is IntegerExpression -> "integer index"
                    else -> "?"
                }
                val nameWhat = if (callee.receiver is VariableExpression && callee.receiver.name == "_ENV") {
                    "global"
                } else {
                    "field"
                }
                CallSiteInfo(0, name, nameWhat)
            }
            else -> null
        }
    }

    private fun callSiteNameWhat(name: String): String {
        if (locals.containsKey(name)) {
            return "local"
        }
        if (resolveUpvalue(name) != null) {
            return "upvalue"
        }
        return "global"
    }

    private fun compileFunctionExpression(expression: FunctionExpression, register: Int) {
        val prototype = compileNestedFunction(expression)
        if (prototype.upvalues.any { it.source == UpvalueSource.LOCAL }) {
            hasCapturedLocals = true
        }
        val nestedIndex = nested.size
        if (nestedIndex > 255) {
            throw unsupported(expression, "too many nested function prototypes")
        }
        nested += prototype
        writer.emit(Instruction.abc(Opcode.CLOSURE, register, nestedIndex), expression.range.start.line)
    }

    private fun compileNestedFunction(expression: FunctionExpression): Prototype {
        val compiler = Compiler(
            sourceName = sourceName,
            isVarargFunction = expression.isVararg,
            parentLocalResolver = { name -> locals[name] },
            parentUpvalueResolver = { name -> resolveUpvalue(name) },
            parentConstResolver = { name -> isConstBinding(name) },
            parentGlobalResolver = { name -> resolveGlobalDeclaration(name) },
        )
        for (parameter in expression.parameters) {
            val slot = compiler.nextLocalRegister++
            compiler.maxRegister = compiler.maxRegister.coerceAtLeast(slot + 1)
            compiler.registerLocal(parameter, slot, LocalAttribute.NONE)
        }
        if (expression.body.isEmpty()) {
            compiler.emitImplicitReturn(expression.range.start.line)
        } else {
            compiler.compileStatements(expression.body, endLabelLocalDepth = 0)
            if (expression.body.last() !is ReturnStatement) {
                compiler.emitImplicitReturn(expression.range.end.line)
            }
        }
        return Prototype(
            sourceName = sourceName,
            code = compiler.writer.code(),
            constants = compiler.constants.toArray(),
            nested = compiler.nested.toTypedArray(),
            upvalues = compiler.upvalues.toTypedArray(),
            localVars = compiler.localVarInfo(compiler.writer.size),
            lineInfo = compiler.writer.lineInfo(),
            callSiteInfo = compiler.callSiteInfo.toTypedArray(),
            maxStackSize = compiler.maxRegister.coerceAtLeast(1),
            numParams = expression.parameters.size,
            isVararg = expression.isVararg,
            lineDefined = expression.range.start.line,
            lastLineDefined = expression.range.end.line,
        )
    }

    private fun compileVariable(expression: VariableExpression, register: Int) {
        val source = locals[expression.name]
        if (source != null) {
            if (source != register) {
                writer.emit(Instruction.abc(Opcode.MOVE, register, source), expression.range.start.line)
            }
            return
        }

        val upvalue = resolveUpvalue(expression.name)
        if (upvalue != null) {
            if (upvalue > 255) {
                throw unsupported(expression, "too many upvalues")
            }
            writer.emit(Instruction.abc(Opcode.GET_UPVALUE, register, upvalue), expression.range.start.line)
            return
        }

        val name = stringConstantIndex(expression.name)
        requireDeclaredGlobal(expression.name, expression.range.start)
        writer.emit(Instruction.abc(Opcode.GET_GLOBAL, register, name), expression.range.start.line)
    }

    private fun requireDeclaredGlobal(name: String, position: SourcePosition): GlobalLookup {
        val lookup = resolveGlobalDeclaration(name)
        if (!lookup.allowed) {
            throw CompilerException(position, "variable '$name' not declared")
        }
        return lookup
    }

    private fun resolveGlobalDeclaration(name: String): GlobalLookup {
        var hasCurrentGlobalDeclaration = false
        for (declaration in globalDeclarations.asReversed()) {
            hasCurrentGlobalDeclaration = true
            if (declaration.name == null || declaration.name == name) {
                return GlobalLookup(allowed = true, restricted = true, isConst = declaration.isConst)
            }
        }
        val parentLookup = parentGlobalResolver?.invoke(name)
        if (parentLookup != null && parentLookup.restricted) {
            return parentLookup
        }
        if (hasCurrentGlobalDeclaration) {
            return GlobalLookup(allowed = false, restricted = true, isConst = false)
        }
        return parentLookup ?: GlobalLookup(allowed = true, restricted = false, isConst = false)
    }

    private fun resolveUpvalue(name: String): Int? {
        upvalueIndexes[name]?.let { return it }
        val index = upvalues.size
        val descriptor = resolveParentLocal(name) ?: resolveParentUpvalue(name) ?: return null
        upvalues += descriptor
        upvalueIndexes[name] = index
        return index
    }

    private fun isConstBinding(name: String): Boolean {
        if (locals.containsKey(name)) {
            return localAttributes[name] == LocalAttribute.CONST
        }
        return parentConstResolver?.invoke(name) == true
    }

    private fun resolveParentLocal(name: String): UpvalueDescriptor? {
        val parentRegister = parentLocalResolver?.invoke(name) ?: return null
        return UpvalueDescriptor(name, UpvalueSource.LOCAL, parentRegister)
    }

    private fun resolveParentUpvalue(name: String): UpvalueDescriptor? {
        val parentUpvalue = parentUpvalueResolver?.invoke(name) ?: return null
        return UpvalueDescriptor(name, UpvalueSource.UPVALUE, parentUpvalue)
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

    private fun Expression?.isOpenResultExpression(): Boolean =
        this is CallExpression || this is MethodCallExpression || this is VarargExpression

    private fun emitInteger(register: Int, value: Long, line: Int) {
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            writer.emit(Instruction.abc(Opcode.LOAD_INT, register, value.toInt() and 0xFF), line)
        } else {
            val constant = constants.add(LuaInteger(value))
            writer.emit(Instruction.abc(Opcode.LOAD_K, register, constant), line)
        }
    }

    private fun emitString(register: Int, value: String, line: Int) {
        val constant = stringConstantIndex(value)
        writer.emit(Instruction.abc(Opcode.LOAD_K, register, constant), line)
    }

    private fun stringConstantIndex(value: String): Int = constants.add(LuaString(value))

    private fun emitReturn(register: Int, count: Int, line: Int) {
        writer.emit(Instruction.abc(Opcode.RETURN, register, count), line)
    }

    private fun emitImplicitReturn(line: Int) {
        if (hasCapturedLocals) {
            writer.emit(Instruction.abc(Opcode.CLOSE_UPVALUES, 0), line)
        }
        emitReturn(0, 0, line)
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

    private fun pushLoopBreaks(): MutableList<BreakJump> {
        val breaks = mutableListOf<BreakJump>()
        loopBreaks += breaks
        return breaks
    }

    private fun patchLoopBreaks(breaks: MutableList<BreakJump>, targetIndex: Int, closeDepth: Int) {
        require(loopBreaks.removeLast() === breaks) { "loop break stack is unbalanced" }
        for (breakJump in breaks) {
            if (breakJump.close != null && closeDepth < breakJump.localDepth) {
                writer.patch(breakJump.jump, Instruction.abc(Opcode.CLOSE_UPVALUES, closeDepth))
                patchJump(breakJump.close, targetIndex)
            } else {
                patchJump(breakJump.jump, targetIndex)
            }
        }
    }

    private fun constAssignmentError(target: LocalAssignmentTarget): CompilerException {
        return CompilerException(target.range.start, "attempt to assign to const variable '${target.name}'")
    }

    private fun enterBlockScope() {
        blockScopePath += nextBlockScopeId++
    }

    private fun exitBlockScope(outerLocalDepth: Int) {
        val exitingScopePath = blockScopePath.toList()
        val outerScopePath = exitingScopePath.dropLast(1)
        for (pending in pendingGotos) {
            if (pending.scopePath.hasPrefix(exitingScopePath)) {
                if (hasCapturedLocals && pending.localDepth > outerLocalDepth) {
                    pending.closeDepth = pending.closeDepth ?: outerLocalDepth
                }
                pending.localDepth = outerLocalDepth
                pending.scopePath = outerScopePath
            }
        }
        activeLabels.removeAll { label -> label.scopePath.hasPrefix(exitingScopePath) }
        blockScopePath.removeLast()
    }

    private fun List<Int>.hasPrefix(prefix: List<Int>): Boolean {
        return size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }
    }

    private fun restoreLocals(
        savedLocals: LinkedHashMap<String, Int>,
        savedLocalAttributes: LinkedHashMap<String, LocalAttribute>,
        savedNextLocalRegister: Int,
    ) {
        closeInactiveLocals(savedLocals, writer.size)
        locals.clear()
        locals.putAll(savedLocals)
        localAttributes.clear()
        localAttributes.putAll(savedLocalAttributes)
        nextLocalRegister = savedNextLocalRegister
    }

    private fun restoreGlobalDeclarations(savedCount: Int) {
        while (globalDeclarations.size > savedCount) {
            globalDeclarations.removeLast()
        }
    }

    private fun closeInactiveLocals(savedLocals: Map<String, Int>, endPc: Int) {
        for (local in localVars) {
            if (local.endPc == null && savedLocals[local.name] != local.slot) {
                local.endPc = endPc
            }
        }
    }

    private fun localVarInfo(endPc: Int): Array<LocalVarInfo> {
        return localVars.map { local ->
            LocalVarInfo(
                name = local.name,
                slot = local.slot,
                startPc = local.startPc,
                endPc = local.endPc ?: endPc,
            )
        }.toTypedArray()
    }

    private data class LocalVarBuilder(
        val name: String,
        val slot: Int,
        val startPc: Int,
        var endPc: Int? = null,
    )

    private data class GlobalDeclaration(
        val name: String?,
        val isConst: Boolean,
    )

    private data class GlobalLookup(
        val allowed: Boolean,
        val restricted: Boolean,
        val isConst: Boolean,
    )

    private data class PreparedAssignmentTargets(
        val targets: List<PreparedAssignmentTarget>,
        val registerCount: Int,
    )

    private sealed interface PreparedAssignmentTarget {
        data class Local(val target: LocalAssignmentTarget) : PreparedAssignmentTarget

        data class Index(
            val target: IndexAssignmentTarget,
            val receiverRegister: Int,
            val key: PreparedAssignmentKey,
        ) : PreparedAssignmentTarget
    }

    private sealed interface PreparedAssignmentKey {
        data class Field(val name: String) : PreparedAssignmentKey

        data class Register(val register: Int) : PreparedAssignmentKey
    }

    private data class BreakJump(
        val jump: Int,
        val close: Int?,
        val localDepth: Int,
    )

    private data class LabelTarget(
        val name: String,
        val pc: Int,
        val line: Int,
        val localDepth: Int,
        val scopePath: List<Int>,
    )

    private data class PendingGoto(
        val name: String,
        val jump: Int,
        val close: Int,
        val line: Int,
        var localDepth: Int,
        var scopePath: List<Int>,
        var closeDepth: Int?,
        val statement: GotoStatement,
    )

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
            isVarargChunk: Boolean = false,
        ): Prototype {
            val chunk = Parser.parse(source, sourceName)
            return Compiler(sourceName, isVarargFunction = isVarargChunk).compile(chunk)
        }
    }
}
