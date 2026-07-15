package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.bytecode.CallSiteInfo
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
import io.github.realmlabs.klua.core.value.LuaNativeFunction
import io.github.realmlabs.klua.core.value.LuaNativeLocalVariable
import io.github.realmlabs.klua.core.value.LuaString
import io.github.realmlabs.klua.core.value.LuaTable
import io.github.realmlabs.klua.core.value.LuaMetatableException
import io.github.realmlabs.klua.core.value.LuaNativeCallContext
import io.github.realmlabs.klua.core.value.LuaNativeDebugHook
import io.github.realmlabs.klua.core.value.LuaTableKeyException
import io.github.realmlabs.klua.core.value.LuaNativeStackFrame
import io.github.realmlabs.klua.core.value.LuaNativeUpvalue
import io.github.realmlabs.klua.core.value.LuaUserData
import io.github.realmlabs.klua.core.value.LuaUpvalue
import io.github.realmlabs.klua.core.value.LuaValue
import io.github.realmlabs.klua.core.value.luaRawBytes
import io.github.realmlabs.klua.core.value.toLuaByteString
import java.math.BigInteger
import java.text.DecimalFormatSymbols
import java.util.Locale

internal interface LuaVmMetatableProvider {
    fun stringMetatable(): LuaTable?

    fun isStringMetatableConfigured(): Boolean

    fun rawTypeMetatable(typeName: String): LuaTable?

    fun userDataMetatable(value: Any): LuaTable?
}

internal class LuaVm(
    private val globals: LuaTable = LuaTable(),
    private val environment: LuaValue = globals,
    private val stringMetatable: LuaTable? = null,
    private val stringMetatableConfigured: Boolean = false,
    private val metatables: LuaVmMetatableProvider? = null,
    private val instructionLimit: Long = 0,
) {
    private val thread = LuaThread()
    private val rootEnvironment = LuaUpvalue(environment)
    private var debugHook: DebugHookState? = null
    private var debugObserver: LuaVmDebugObserver? = null
    private var debugSuspended = false
    private var runningDebugHook = false
    private var remainingInstructions = instructionLimit

    init {
        require(instructionLimit >= 0) { "instructionLimit must be non-negative" }
    }

    fun execute(prototype: Prototype): List<LuaValue> {
        return executeReturned(prototype, emptyList(), emptyList())
    }

    fun execute(prototype: Prototype, arguments: List<LuaValue>): List<LuaValue> {
        return executeReturned(prototype, arguments, emptyList())
    }

    internal val activeCallDepth: Int
        get() = thread.callDepth

    internal val currentFrame: CallFrame?
        get() = thread.currentFrame

    internal fun stackFrames(): List<LuaNativeStackFrame> {
        return luaStackFrames(thread.stackFrames())
    }

    internal fun setDebugObserver(observer: LuaVmDebugObserver?) {
        debugObserver = observer
    }

    internal fun setLocal(level: Int, index: Int, value: LuaValue): String? {
        return setLocal(thread.stackFrames(), level, index, value)
    }

    internal fun setDebugHook(function: LuaValue, mask: String, count: Int): Boolean {
        if (function == LuaNil) {
            debugHook = null
            return true
        }
        if (function !is LuaClosure && function !is LuaNativeFunction) {
            throw LuaVmException("bad argument #1 to 'debug.sethook' (function expected)")
        }
        val canonicalMask = normalizedDebugHookMask(mask)
        if (canonicalMask.isEmpty() && count <= 0) {
            debugHook = null
            return true
        }
        debugHook = DebugHookState(
            function,
            canonicalMask,
            count,
        )
        return true
    }

    internal fun getDebugHook(): LuaNativeDebugHook? {
        return debugHook?.toNativeHook()
    }

    internal fun call(callee: LuaValue, arguments: List<LuaValue>): List<LuaValue> {
        return returnedValues(callValue(callee, arguments))
    }

    internal fun lessThan(left: LuaValue, right: LuaValue): Boolean {
        return compare(left, right, Comparison.LT)
    }

    internal fun luaEquals(left: LuaValue, right: LuaValue): Boolean {
        return compare(left, right, Comparison.EQ)
    }

    internal fun equal(left: LuaValue, right: LuaValue): Boolean {
        return luaEquals(left, right)
    }

    internal fun callYieldable(callee: LuaValue, arguments: List<LuaValue>): LuaExecutionResult {
        return callValue(callee, arguments)
    }

    internal fun callWithYieldability(
        callee: LuaValue,
        arguments: List<LuaValue>,
        isYieldable: Boolean,
    ): LuaExecutionResult {
        if (isYieldable) {
            return callYieldable(callee, arguments)
        }
        return thread.runNonYieldableCall {
            when (val result = callYieldable(callee, arguments)) {
                is LuaExecutionResult.Returned -> result
                is LuaExecutionResult.Yielded -> throw LuaVmException("attempt to yield across a C-call boundary")
                LuaExecutionResult.DebugSuspended -> result
            }
        }
    }

    internal fun executeYieldable(prototype: Prototype, arguments: List<LuaValue> = emptyList()): LuaExecutionResult {
        return executeFrame(LuaClosure(prototype), arguments)
    }

    internal fun resumeYieldable(arguments: List<LuaValue> = emptyList()): LuaExecutionResult {
        var frame = thread.currentFrame ?: throw LuaVmException("cannot resume a coroutine that is not suspended")
        if (debugSuspended) {
            debugSuspended = false
        } else {
            applyPendingCallResults(frame, arguments)?.let { return it }
        }
        while (true) {
            when (val result = runFrameAndPopOnCompletion(frame)) {
                is LuaExecutionResult.Yielded -> return result
                LuaExecutionResult.DebugSuspended -> return result
                is LuaExecutionResult.Returned -> {
                    frame = thread.currentFrame ?: return result
                    applyPendingCallResults(frame, result.values)?.let { return it }
                }
            }
        }
    }

    private fun executeReturned(
        prototype: Prototype,
        arguments: List<LuaValue>,
        upvalues: List<LuaUpvalue>,
    ): List<LuaValue> {
        return returnedValues(executeFrame(LuaClosure(prototype, upvalues.toMutableList()), arguments))
    }

    private fun executeReturned(
        closure: LuaClosure,
        arguments: List<LuaValue>,
        callSiteInfo: CallSiteInfo? = null,
    ): List<LuaValue> {
        return returnedValues(executeFrame(closure, arguments, callSiteInfo))
    }

    private fun returnedValues(result: LuaExecutionResult): List<LuaValue> {
        return when (result) {
            is LuaExecutionResult.Returned -> result.values
            is LuaExecutionResult.Yielded -> {
                thread.clearFrames()
                throw LuaVmException("attempt to yield from outside a coroutine")
            }
            LuaExecutionResult.DebugSuspended -> {
                thread.clearFrames()
                throw LuaVmException("debugger suspension requires a coroutine")
            }
        }
    }

    private fun executeFrame(
        function: LuaClosure,
        arguments: List<LuaValue>,
        callSiteInfo: CallSiteInfo? = null,
    ): LuaExecutionResult {
        val frame = thread.pushCall(
            function.prototype,
            arguments,
            function.upvalues,
            environment = function.environment ?: function.globals?.let(::LuaUpvalue) ?: rootEnvironment,
            function = function,
            callSiteInfo = callSiteInfo,
        )
        return runFrameAndPopOnCompletion(frame)
    }

    private fun executeFrameInto(
        function: LuaClosure,
        sourceStack: LuaStack,
        argumentStart: Int,
        argumentCount: Int,
        callSiteInfo: CallSiteInfo?,
        callerFrame: CallFrame,
        resultBase: Int,
        expectedResults: Int,
    ): LuaExecutionResult? {
        val frame = thread.pushCallFromStack(
            sourceStack,
            argumentStart,
            argumentCount,
            environment = function.environment ?: function.globals?.let(::LuaUpvalue) ?: rootEnvironment,
            function = function,
            callSiteInfo = callSiteInfo,
        )
        return runFrameAndPopOnCompletion(frame, callerFrame, resultBase, expectedResults)
    }

    private fun runFrameAndPopOnCompletion(frame: CallFrame): LuaExecutionResult {
        return checkNotNull(runFrameAndPopOnCompletion(frame, null, 0, 0)) {
            "top-level frame returned into a caller"
        }
    }

    private fun runFrameAndPopOnCompletion(
        frame: CallFrame,
        callerFrame: CallFrame?,
        resultBase: Int,
        expectedResults: Int,
    ): LuaExecutionResult? {
        val stack = frame.stack
        var suspended = false
        try {
            dispatchDebugCall(frame)
            while (frame.pc < frame.prototype.code.size) {
                val pc = frame.pc
                if (debugObserver != null && dispatchDebuggerLine(frame, pc)) {
                    suspended = true
                    debugSuspended = true
                    return LuaExecutionResult.DebugSuspended
                }
                val instruction = frame.prototype.code[frame.pc++]
                try {
                    consumeInstructionBudget()
                    dispatchDebugHooks(frame, pc)
                    when (Instruction.opcode(instruction)) {
                        Opcode.LOAD_NIL -> stack.set(register(frame, Instruction.a(instruction)), LuaNil)
                        Opcode.LOAD_BOOL -> {
                            stack.set(register(frame, Instruction.a(instruction)), LuaBoolean(Instruction.b(instruction) != 0))
                        }
                        Opcode.LOAD_INT -> {
                            stack.set(
                                register(frame, Instruction.a(instruction)),
                                LuaInteger(signedByte(Instruction.b(instruction)).toLong()),
                            )
                        }
                        Opcode.LOAD_FLOAT -> {
                            val constant = constant(frame.prototype, Instruction.b(instruction))
                            if (constant !is LuaFloat) {
                                throw LuaVmException("LOAD_FLOAT expected float constant at K${Instruction.b(instruction)}")
                            }
                            stack.set(register(frame, Instruction.a(instruction)), constant)
                        }
                        Opcode.LOAD_K -> stack.set(
                            register(frame, Instruction.a(instruction)),
                            constant(frame.prototype, Instruction.b(instruction)),
                        )
                        Opcode.VARARG -> loadVarargs(stack, frame, instruction)
                        Opcode.NEW_TABLE -> stack.set(
                            register(frame, Instruction.a(instruction)),
                            LuaTable(Instruction.b(instruction)),
                        )
                        Opcode.GET_TABLE -> getTable(stack, frame, instruction)
                        Opcode.SET_TABLE -> setTable(stack, frame, instruction)
                        Opcode.GET_FIELD -> getField(stack, frame, instruction)
                        Opcode.SET_FIELD -> setField(stack, frame, instruction)
                        Opcode.GET_GLOBAL -> getGlobal(stack, frame, instruction)
                        Opcode.SET_GLOBAL -> setGlobal(stack, frame, instruction)
                        Opcode.CHECK_GLOBAL_NIL -> checkGlobalNil(frame, instruction)
                        Opcode.GET_ENV -> getEnvironment(stack, frame, instruction)
                        Opcode.SET_ENV -> setEnvironment(stack, frame, instruction)
                        Opcode.CHECK_FIELD_NIL -> checkFieldNil(stack, frame, instruction)
                        Opcode.CHECK_CLOSE_FALSE -> checkCloseFalse(stack, frame, instruction)
                        Opcode.CLOSURE -> createClosure(stack, frame, instruction)
                        Opcode.GET_UPVALUE -> getUpvalue(stack, frame, instruction)
                        Opcode.SET_UPVALUE -> setUpvalue(stack, frame, instruction)
                        Opcode.CLOSE_UPVALUES -> stack.closeCapturesFrom(register(frame, Instruction.a(instruction)))
                        Opcode.MOVE -> stack.copy(
                            register(frame, Instruction.b(instruction)),
                            register(frame, Instruction.a(instruction)),
                        )
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
                            if (advanceForLoop(stack, frame, instruction)) {
                                frame.pc += signedByte(Instruction.b(instruction))
                            }
                        }
                        Opcode.CALL -> {
                            val result = call(stack, frame, instruction)
                            if (result != null) {
                                suspended = true
                                return result
                            }
                        }
                        Opcode.RETURN -> {
                            val base = register(frame, Instruction.a(instruction))
                            val count = returnCount(frame, base, Instruction.b(instruction))
                            dispatchDebugReturn(frame, base + 1, count)
                            if (callerFrame != null) {
                                transferCallResults(
                                    callerFrame,
                                    resultBase,
                                    expectedResults,
                                    stack,
                                    base,
                                    count,
                                )
                                return null
                            }
                            return LuaExecutionResult.Returned(stack.snapshotResults(base, count))
                        }
                    }
                } catch (suspension: LuaMetamethodSuspension) {
                    val (destination, expectedResults) = when (Instruction.opcode(instruction)) {
                        Opcode.GET_TABLE,
                        Opcode.GET_FIELD,
                        Opcode.GET_GLOBAL,
                        Opcode.ADD,
                        Opcode.SUB,
                        Opcode.MUL,
                        Opcode.DIV,
                        Opcode.IDIV,
                        Opcode.MOD,
                        Opcode.POW,
                        Opcode.BAND,
                        Opcode.BOR,
                        Opcode.BXOR,
                        Opcode.SHL,
                        Opcode.SHR,
                        Opcode.BNOT,
                        Opcode.LEN,
                        Opcode.UNM,
                        Opcode.CONCAT,
                        -> register(frame, Instruction.a(instruction)) to 1
                        Opcode.EQ,
                        Opcode.LT,
                        Opcode.LE,
                        -> register(frame, Instruction.a(instruction)) to TRUTHY_PENDING_RESULT_COUNT
                        Opcode.SET_TABLE,
                        Opcode.SET_FIELD,
                        Opcode.SET_GLOBAL,
                        -> 0 to 0
                        else -> error("unsupported yielding metamethod instruction: ${Instruction.opcode(instruction)}")
                    }
                    val continuation = (suspension.result as? LuaExecutionResult.Yielded)?.continuation
                    frame.setPendingCall(destination, expectedResults, continuation)
                    suspended = true
                    return when (val result = suspension.result) {
                        is LuaExecutionResult.Yielded -> LuaExecutionResult.Yielded(result.values)
                        LuaExecutionResult.DebugSuspended -> result
                        is LuaExecutionResult.Returned -> error("returned metamethod cannot suspend")
                    }
                } catch (error: LuaVmException) {
                    throw error.withFrame(frame, frame.lineForPc(pc))
                }
            }

            throw LuaVmException("prototype completed without RETURN")
        } finally {
            if (!suspended) {
                thread.popFrame(frame)
            }
        }
    }

    private fun consumeInstructionBudget() {
        if (instructionLimit <= 0) {
            return
        }
        if (remainingInstructions <= 0) {
            throw LuaVmException("instruction limit exceeded")
        }
        remainingInstructions--
    }

    private fun register(@Suppress("UNUSED_PARAMETER") frame: CallFrame, offset: Int): Int = offset

    private fun CallFrame.lineForPc(pc: Int): Int = prototype.lineInfo.getOrElse(pc) { 0 }

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

    private fun call(stack: LuaStack, frame: CallFrame, instruction: Int): LuaExecutionResult? {
        val base = register(frame, Instruction.a(instruction))
        val callee = stack.get(base)
        val argumentCount = argumentCount(frame, base, Instruction.b(instruction))
        val expectedResults = Instruction.c(instruction)
        val callSiteInfo = callSiteInfo(frame, frame.pc - 1)
        val results = if (callee is LuaClosure) {
            executeFrameInto(
                callee,
                stack,
                base + 1,
                argumentCount,
                callSiteInfo,
                frame,
                base,
                expectedResults,
            )
        } else {
            callValue(callee, stack.slice(base + 1, argumentCount), callSiteInfo)
        }
        if (results == null) {
            return null
        }
        if (results is LuaExecutionResult.Yielded) {
            frame.setPendingCall(base, expectedResults, results.continuation)
            return LuaExecutionResult.Yielded(results.values)
        }
        if (results === LuaExecutionResult.DebugSuspended) {
            frame.setPendingCall(base, expectedResults, null)
            return LuaExecutionResult.DebugSuspended
        }
        val returnedValues = (results as LuaExecutionResult.Returned).values
        applyCallResults(stack, frame, base, expectedResults, returnedValues)
        return null
    }

    private fun applyPendingCallResults(frame: CallFrame, results: List<LuaValue>): LuaExecutionResult? {
        val pendingCall = frame.takePendingCall()
        if (pendingCall == null) {
            throw LuaVmException("suspended frame has no pending call")
        }
        val base = pendingCall.resultBase
        val expectedResults = pendingCall.expectedResults
        val continuation = pendingCall.continuation
        val returnedValues = if (continuation == null) {
            results
        } else {
            when (val continued = continuation.resume(results)) {
                is LuaExecutionResult.Returned -> continued.values
                is LuaExecutionResult.Yielded -> {
                    frame.setPendingCall(base, expectedResults, continued.continuation)
                    return LuaExecutionResult.Yielded(continued.values)
                }
                LuaExecutionResult.DebugSuspended -> {
                    frame.setPendingCall(base, expectedResults, continuation)
                    return LuaExecutionResult.DebugSuspended
                }
            }
        }
        if (expectedResults == TRUTHY_PENDING_RESULT_COUNT) {
            frame.stack.set(base, LuaBoolean(isTruthy(returnedValues.firstOrNull() ?: LuaNil)))
            return null
        }
        applyCallResults(frame.stack, frame, base, expectedResults, returnedValues)
        return null
    }

    private fun applyCallResults(
        stack: LuaStack,
        frame: CallFrame,
        base: Int,
        expectedResults: Int,
        results: List<LuaValue>,
    ) {
        if (expectedResults == OPEN_RESULT_COUNT) {
            setOpenResults(stack, frame, base, results)
            return
        }

        for (index in 0 until expectedResults) {
            stack.set(base + index, results.getOrElse(index) { LuaNil })
        }
    }

    private fun transferCallResults(
        frame: CallFrame,
        base: Int,
        expectedResults: Int,
        sourceStack: LuaStack,
        sourceBase: Int,
        sourceCount: Int,
    ) {
        if (expectedResults == OPEN_RESULT_COUNT) {
            for (index in 0 until sourceCount) {
                frame.set(base + index, sourceStack.get(sourceBase + index))
            }
            frame.openResultBase = base
            frame.openResultCount = sourceCount
            return
        }

        for (index in 0 until expectedResults) {
            val value = if (index < sourceCount) sourceStack.get(sourceBase + index) else LuaNil
            frame.set(base + index, value)
        }
    }

    private fun callValue(
        callee: LuaValue,
        arguments: List<LuaValue>,
        callSiteInfo: CallSiteInfo? = null,
        callMetamethodDepth: Int = 0,
    ): LuaExecutionResult {
        return when (callee) {
            is LuaClosure -> executeFrame(callee, arguments, callSiteInfo)
            is LuaNativeFunction -> callNativeResult(callee, arguments)
            is LuaTable -> {
                callMetamethod(callee, arguments, callee.metatableRawGet(CALL_KEY), callSiteInfo, callMetamethodDepth)
            }
            is LuaUserData -> {
                val metatable = metatables?.userDataMetatable(callee.value)
                if (metatable != null) {
                    callMetamethod(
                        callee,
                        arguments,
                        metatable.rawGet(CALL_KEY),
                        callSiteInfo,
                        callMetamethodDepth,
                        "a ${userDataObjectTypeName(metatable)} value",
                    )
                } else {
                    callMetamethod(callee, arguments, LuaNil, callSiteInfo, callMetamethodDepth)
                }
            }
            else -> {
                val metatable = rawTypeMetatable(callee)
                callMetamethod(
                    callee,
                    arguments,
                    metatable?.rawGet(CALL_KEY) ?: LuaNil,
                    callSiteInfo,
                    callMetamethodDepth,
                )
            }
        }
    }

    private fun callMetamethod(
        callee: LuaValue,
        arguments: List<LuaValue>,
        call: LuaValue,
        callSiteInfo: CallSiteInfo?,
        callMetamethodDepth: Int,
        callErrorTypeName: String = typeName(callee),
    ): LuaExecutionResult {
        if (callMetamethodDepth >= MAX_CALL_METAMETHOD_DEPTH) {
            throw LuaVmException("'__call' chain too long")
        }
        val metamethodArguments = listOf(callee) + arguments
        return when (call) {
            is LuaClosure -> executeFrame(call, metamethodArguments, callSiteInfo)
            is LuaNativeFunction -> callNativeResult(call, metamethodArguments)
            LuaNil -> throw LuaVmException("attempt to call $callErrorTypeName")
            else -> callValue(call, metamethodArguments, callSiteInfo, callMetamethodDepth + 1)
        }
    }

    private fun callSiteInfo(frame: CallFrame, pc: Int): CallSiteInfo? {
        return frame.prototype.callSiteInfo.firstOrNull { info -> info.pc == pc }
    }

    private fun callNative(function: LuaNativeFunction, arguments: List<LuaValue>): List<LuaValue> {
        return returnedValues(callNativeResult(function, arguments))
    }

    private fun callNativeResult(function: LuaNativeFunction, arguments: List<LuaValue>): LuaExecutionResult {
        return try {
            LuaExecutionResult.Returned(
                thread.runNativeCall {
                    function.call(nativeCallContext(function, arguments))
                },
            )
        } catch (yield: LuaYieldSignal) {
            if (!function.yieldable) {
                throw LuaVmException("attempt to yield across a C-call boundary")
            }
            nativeYieldResult(function, yield)
        }
    }

    private fun nativeYieldResult(function: LuaNativeFunction, yield: LuaYieldSignal): LuaExecutionResult.Yielded {
        return LuaExecutionResult.Yielded(
            yield.values,
            yield.continuation?.let { continuation ->
                LuaYieldContinuation { arguments -> continueNativeYield(function, continuation, arguments) }
            },
        )
    }

    private fun nativeCallContext(function: LuaNativeFunction, arguments: List<LuaValue>): LuaNativeCallContext {
        return VmNativeCallContext(function, arguments)
    }

    private inner class VmNativeCallContext(
        function: LuaNativeFunction,
        arguments: List<LuaValue>,
    ) : LuaNativeCallContext(arguments, thread.isYieldable && function.yieldable) {
        private var cachedFrames: List<CallFrame>? = null

        private fun frames(): List<CallFrame> {
            return cachedFrames ?: thread.stackFrames().also { frames -> cachedFrames = frames }
        }

        override fun loadLuaFrames(): List<LuaNativeStackFrame> {
            return luaStackFrames(frames())
        }

        override fun setLocal(level: Int, index: Int, value: LuaValue): String? {
            return this@LuaVm.setLocal(frames(), level, index, value)
        }

        override fun setDebugHook(index: Int, mask: String, count: Int): Boolean {
            return this@LuaVm.setDebugHook(arguments, index, mask, count)
        }

        override fun getDebugHook(): LuaNativeDebugHook? {
            return debugHook?.toNativeHook()
        }
    }

    private fun luaStackFrames(frames: List<CallFrame>): List<LuaNativeStackFrame> {
        return frames.mapNotNull { frame ->
            val pc = if (frame.debuggerSuspendedPc >= 0) {
                frame.debuggerSuspendedPc
            } else {
                (frame.pc - 1).coerceAtLeast(0)
            }
            val line = frame.lineForPc(pc)
            if (line <= 0) {
                null
            } else {
                LuaNativeStackFrame(
                    sourceName = frame.prototype.sourceName,
                    line = line,
                    lineDefined = frame.prototype.lineDefined,
                    lastLineDefined = frame.prototype.lastLineDefined,
                    upvalueCount = frame.prototype.upvalues.size,
                    parameterCount = frame.prototype.numParams,
                    isVararg = frame.prototype.isVararg,
                    activeLines = frame.prototype.validBreakpointLines.toList(),
                    function = frame.function,
                    varargs = frame.varargs.toList(),
                    locals = activeLocals(frame, pc),
                    upvalues = activeUpvalues(frame),
                    globals = activeGlobals(frame),
                    callSiteName = frame.callSiteName,
                    callSiteNameWhat = frame.callSiteNameWhat,
                    transferStart = frame.hookTransferStart,
                    transferCount = frame.hookTransferCount,
                )
            }
        }
    }

    private fun setLocal(frames: List<CallFrame>, level: Int, index: Int, value: LuaValue): String? {
        if (level < 0) {
            return null
        }
        val frame = frames.drop(level).firstOrNull() ?: return null
        if (index < 0) {
            val varargIndex = -index - 1
            if (!frame.setVararg(varargIndex, value)) {
                return null
            }
            return VARARG_LOCAL_NAME
        }
        if (index == 0) {
            return null
        }
        val pc = (frame.pc - 1).coerceAtLeast(0)
        val local = frame.prototype.localVars
            .filter { info -> info.startPc <= pc && pc < info.endPc }
            .getOrNull(index - 1)
            ?: return null
        frame.stack.set(register(frame, local.slot), value)
        return local.name
    }

    private fun activeLocals(frame: CallFrame, pc: Int): List<LuaNativeLocalVariable> {
        return frame.prototype.localVars
            .filter { local -> local.startPc <= pc && pc < local.endPc }
            .map { local ->
                LuaNativeLocalVariable(
                    local.name,
                    frame.stack.get(register(frame, local.slot)),
                )
            }
    }

    private fun setDebugHook(arguments: List<LuaValue>, index: Int, mask: String, count: Int): Boolean {
        val function = if (index <= 0) LuaNil else arguments.getOrElse(index - 1) { LuaNil }
        return setDebugHook(function, mask, count)
    }

    private fun dispatchDebugCall(frame: CallFrame) {
        val hook = debugHook ?: return
        if (!hook.hasCallHook || runningDebugHook) {
            return
        }
        callDebugHook(
            hook.function,
            "call",
            LuaNil,
            frame,
            transferStart = 1,
            transferCount = frame.prototype.numParams,
        )
    }

    private fun dispatchDebugReturn(frame: CallFrame, transferStart: Int, transferCount: Int) {
        val hook = debugHook ?: return
        if (!hook.hasReturnHook || runningDebugHook) {
            return
        }
        callDebugHook(hook.function, "return", LuaNil, frame, transferStart, transferCount)
    }

    private fun dispatchDebugHooks(frame: CallFrame, pc: Int) {
        val hook = debugHook ?: return
        if (runningDebugHook) {
            return
        }
        val line = frame.lineForPc(pc)
        if (hook.hasLineHook && line > 0 && line != frame.lastDebugHookLine) {
            frame.lastDebugHookLine = line
            callDebugHook(hook.function, "line", LuaInteger(line.toLong()), frame)
        }
        if (hook.count > 0) {
            hook.remainingCount -= 1
            if (hook.remainingCount <= 0) {
                hook.remainingCount = hook.count
                callDebugHook(hook.function, "count", LuaNil, frame)
            }
        }
    }

    private fun callDebugHook(
        function: LuaValue,
        event: String,
        line: LuaValue,
        frame: CallFrame,
        transferStart: Int = 0,
        transferCount: Int = 0,
    ) {
        runningDebugHook = true
        frame.hookTransferStart = transferStart
        frame.hookTransferCount = transferCount
        try {
            when (callValue(function, listOf(LuaString(event), line), CallSiteInfo(0, "?", "hook"))) {
                is LuaExecutionResult.Returned -> Unit
                is LuaExecutionResult.Yielded -> throw LuaVmException("attempt to yield from debug hook")
                LuaExecutionResult.DebugSuspended -> throw LuaVmException("attempt to suspend from debug hook")
            }
        } finally {
            frame.hookTransferStart = 0
            frame.hookTransferCount = 0
            runningDebugHook = false
        }
    }

    private fun continueNativeYield(
        function: LuaNativeFunction,
        continuation: LuaYieldSignalContinuation,
        arguments: List<LuaValue>,
    ): LuaExecutionResult {
        return try {
            LuaExecutionResult.Returned(
                thread.runNativeCall {
                    continuation.resume(arguments)
                },
            )
        } catch (yield: LuaYieldSignal) {
            if (!function.yieldable) {
                throw LuaVmException("attempt to yield across a C-call boundary")
            }
            nativeYieldResult(function, yield)
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
        }.toMutableList()
        stack.set(register(frame, Instruction.a(instruction)), LuaClosure(prototype, upvalues, environment = frame.environment))
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
        val receiver = stack.get(register(frame, Instruction.b(instruction)))
        val key = stack.get(register(frame, Instruction.c(instruction)))
        stack.set(register(frame, Instruction.a(instruction)), indexGet(receiver, key, allowSuspension = true))
    }

    private fun setTable(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val receiver = stack.get(register(frame, Instruction.a(instruction)))
        val key = stack.get(register(frame, Instruction.b(instruction)))
        val value = stack.get(register(frame, Instruction.c(instruction)))
        indexSet(receiver, key, value, allowSuspension = true)
    }

    private fun getField(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val receiver = stack.get(register(frame, Instruction.b(instruction)))
        val key = stringConstant(frame.prototype, Instruction.c(instruction))
        stack.set(register(frame, Instruction.a(instruction)), indexGet(receiver, key, allowSuspension = true))
    }

    private fun setField(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val receiver = stack.get(register(frame, Instruction.a(instruction)))
        val key = stringConstant(frame.prototype, Instruction.b(instruction))
        val value = stack.get(register(frame, Instruction.c(instruction)))
        indexSet(receiver, key, value, allowSuspension = true)
    }

    private fun getGlobal(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val key = stringConstant(frame.prototype, Instruction.b(instruction))
        stack.set(register(frame, Instruction.a(instruction)), indexGet(frame.globals, key, allowSuspension = true))
    }

    private fun activeUpvalues(frame: CallFrame): List<LuaNativeUpvalue> {
        return frame.upvalues.mapIndexed { index, upvalue ->
            LuaNativeUpvalue(
                frame.prototype.upvalueNames.getOrElse(index) { "?" },
                upvalue.value,
            )
        }
    }

    private fun activeGlobals(frame: CallFrame): List<LuaNativeLocalVariable> {
        val table = frame.globals as? LuaTable ?: return emptyList()
        return table.rawEntries()
            .mapNotNull { (key, value) ->
                (key as? LuaString)?.let { name -> LuaNativeLocalVariable(name.value, value) }
            }
            .sortedBy { global -> global.name }
    }

    private fun getEnvironment(stack: LuaStack, frame: CallFrame, instruction: Int) {
        stack.set(register(frame, Instruction.a(instruction)), frame.globals)
    }

    private fun setEnvironment(stack: LuaStack, frame: CallFrame, instruction: Int) {
        frame.environment.value = stack.get(register(frame, Instruction.a(instruction)))
    }

    private fun setGlobal(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val key = stringConstant(frame.prototype, Instruction.a(instruction))
        val value = stack.get(register(frame, Instruction.b(instruction)))
        indexSet(frame.globals, key, value, allowSuspension = true)
    }

    private fun checkGlobalNil(frame: CallFrame, instruction: Int) {
        val key = stringConstant(frame.prototype, Instruction.a(instruction))
        if (indexGet(frame.globals, key) != LuaNil) {
            throw LuaVmException("global '${key.value}' already defined")
        }
    }

    private fun dispatchDebuggerLine(frame: CallFrame, pc: Int): Boolean {
        val observer = debugObserver ?: return false
        if (runningDebugHook || thread.inNativeCall) {
            return false
        }
        if (frame.resumePastDebuggerPc == pc) {
            frame.resumePastDebuggerPc = -1
            frame.debuggerSuspendedPc = -1
            frame.lastDebuggerPc = pc
            return false
        }
        val line = frame.lineForPc(pc)
        if (line <= 0) {
            frame.lastDebuggerPc = pc
            return false
        }
        val previousPc = frame.lastDebuggerPc
        val previousLine = if (previousPc >= 0) frame.lineForPc(previousPc) else 0
        frame.lastDebuggerPc = pc
        if (previousPc >= 0 && pc > previousPc && line == previousLine) {
            return false
        }
        if (!observer.shouldSuspend(frame.prototype.sourceId, line, thread.callDepth)) {
            return false
        }
        frame.resumePastDebuggerPc = pc
        frame.debuggerSuspendedPc = pc
        return true
    }

    private fun checkFieldNil(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val receiver = stack.get(register(frame, Instruction.a(instruction)))
        val key = stringConstant(frame.prototype, Instruction.b(instruction))
        if (indexGet(receiver, key) != LuaNil) {
            throw LuaVmException("global '${key.value}' already defined")
        }
    }

    private fun checkCloseFalse(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val value = stack.get(register(frame, Instruction.a(instruction)))
        if (value == LuaNil || value == LuaBoolean(false)) {
            return
        }
        val name = stringConstant(frame.prototype, Instruction.b(instruction))
        throw LuaVmException("variable '${name.value}' got a non-closable value")
    }

    private fun tableGet(
        table: LuaTable,
        key: LuaValue,
        allowSuspension: Boolean = false,
    ): LuaValue {
        return try {
            val value = table.rawGet(key)
            if (value != LuaNil) {
                value
            } else {
                when (val index = table.metatableRawGet(INDEX_KEY)) {
                    is LuaTable -> tableGet(index, key, mutableSetOf(table), allowSuspension)
                    is LuaClosure -> executeMetamethod(
                        index,
                        table,
                        key,
                        INDEX_KEY,
                        allowSuspension,
                    ).firstOrNull() ?: LuaNil
                    is LuaNativeFunction -> callIndexMetamethod(index, table, key, allowSuspension)
                    LuaNil -> LuaNil
                    else -> indexGet(index, key, allowSuspension)
                }
            }
        } catch (error: LuaTableKeyException) {
            throw LuaVmException(error.message ?: "invalid table key")
        } catch (error: LuaMetatableException) {
            throw LuaVmException(error.message ?: "invalid metatable operation")
        }
    }

    private fun indexGet(
        receiver: LuaValue,
        key: LuaValue,
        allowSuspension: Boolean = false,
    ): LuaValue {
        return when (receiver) {
            is LuaTable -> tableGet(receiver, key, allowSuspension)
            is LuaString -> {
                val metatableConfigured = metatables?.isStringMetatableConfigured() ?: stringMetatableConfigured
                if (metatableConfigured) {
                    val metatable = metatables?.stringMetatable() ?: stringMetatable
                        ?: throw LuaVmException("attempt to index ${typeName(receiver)}")
                    return primitiveIndexGet(receiver, key, metatable, allowSuspension)
                }
                val stringLibrary = globals.rawGet(STRING_LIBRARY_KEY) as? LuaTable
                    ?: throw LuaVmException("attempt to index ${typeName(receiver)}")
                tableGet(stringLibrary, key, allowSuspension)
            }
            is LuaUserData -> {
                val metatable = metatables?.userDataMetatable(receiver.value)
                if (metatable != null) {
                    val index = metatable.rawGet(INDEX_KEY)
                    if (index == LuaNil) {
                        throw LuaVmException("attempt to index a ${userDataObjectTypeName(metatable)} value")
                    }
                    return metamethodIndexGet(receiver, key, index, allowSuspension)
                }
                if (key is LuaString) {
                    val property = receiver.type?.property(key.value)
                    if (property?.getter != null) {
                        callNative(property.getter, listOf(receiver)).firstOrNull() ?: LuaNil
                    } else {
                        receiver.type?.method(key.value) ?: LuaNil
                    }
                } else {
                    LuaNil
                }
            }
            else -> {
                val metatable = metatables?.rawTypeMetatable(typeName(receiver))
                    ?: throw LuaVmException("attempt to index ${typeName(receiver)}")
                primitiveIndexGet(receiver, key, metatable, allowSuspension)
            }
        }
    }

    private fun primitiveIndexGet(
        receiver: LuaValue,
        key: LuaValue,
        metatable: LuaTable,
        allowSuspension: Boolean,
    ): LuaValue {
        return metamethodIndexGet(receiver, key, metatable.rawGet(INDEX_KEY), allowSuspension)
    }

    private fun metamethodIndexGet(
        receiver: LuaValue,
        key: LuaValue,
        index: LuaValue,
        allowSuspension: Boolean,
    ): LuaValue {
        return when (index) {
            is LuaTable -> tableGet(index, key, allowSuspension)
            is LuaClosure -> executeMetamethod(
                index,
                receiver,
                key,
                INDEX_KEY,
                allowSuspension,
            ).firstOrNull() ?: LuaNil
            is LuaNativeFunction -> callIndexMetamethod(index, receiver, key, allowSuspension)
            else -> throw LuaVmException("attempt to index ${typeName(receiver)}")
        }
    }

    private fun callIndexMetamethod(
        function: LuaNativeFunction,
        receiver: LuaValue,
        key: LuaValue,
        allowSuspension: Boolean,
    ): LuaValue {
        val canSuspend = allowSuspension && thread.currentFrame != null && !thread.inNativeCall
        return metamethodValues(
            callNativeResult(function, listOf(receiver, key)),
            canSuspend,
        ).firstOrNull() ?: LuaNil
    }

    private fun rawTypeMetatable(value: LuaValue): LuaTable? {
        return when (value) {
            is LuaString -> {
                val metatableConfigured = metatables?.isStringMetatableConfigured() ?: stringMetatableConfigured
                if (metatableConfigured) metatables?.stringMetatable() ?: stringMetatable else null
            }
            is LuaBoolean,
            is LuaClosure,
            is LuaFloat,
            is LuaInteger,
            is LuaNativeFunction,
            LuaNil,
            -> metatables?.rawTypeMetatable(typeName(value))
            else -> null
        }
    }

    private fun userDataObjectTypeName(metatable: LuaTable): String {
        return (metatable.rawGet(NAME_KEY) as? LuaString)?.value ?: "userdata"
    }

    private fun objectTypeName(value: LuaValue): String {
        return namedObjectTypeName(value) ?: typeName(value)
    }

    private fun operationTypeName(value: LuaValue): String {
        val name = namedObjectTypeName(value) ?: return typeName(value)
        return "a $name value"
    }

    private fun namedObjectTypeName(value: LuaValue): String? {
        return when (value) {
            is LuaTable -> (value.metatableRawGet(NAME_KEY) as? LuaString)?.value
            is LuaUserData -> metatables?.userDataMetatable(value.value)?.let { metatable ->
                (metatable.rawGet(NAME_KEY) as? LuaString)?.value
            }
            else -> null
        }
    }

    private fun tableGet(
        table: LuaTable,
        key: LuaValue,
        visited: MutableSet<LuaTable>,
        allowSuspension: Boolean,
    ): LuaValue {
        val value = table.rawGet(key)
        if (value != LuaNil) {
            return value
        }
        if (!visited.add(table)) {
            throw LuaMetatableException("cycle in __index chain")
        }

        return when (val index = table.metatableRawGet(INDEX_KEY)) {
            is LuaTable -> tableGet(index, key, visited, allowSuspension)
            is LuaClosure -> executeMetamethod(
                index,
                table,
                key,
                INDEX_KEY,
                allowSuspension,
            ).firstOrNull() ?: LuaNil
            is LuaNativeFunction -> callIndexMetamethod(index, table, key, allowSuspension)
            LuaNil -> LuaNil
            else -> indexGet(index, key, allowSuspension)
        }
    }

    private fun indexSet(
        receiver: LuaValue,
        key: LuaValue,
        value: LuaValue,
        allowSuspension: Boolean = false,
    ) {
        when (receiver) {
            is LuaTable -> tableSet(receiver, key, value, allowSuspension)
            is LuaUserData -> {
                val metatable = metatables?.userDataMetatable(receiver.value)
                if (metatable != null) {
                    val newIndex = metatable.rawGet(NEW_INDEX_KEY)
                    if (newIndex == LuaNil) {
                        throw LuaVmException("attempt to index a ${userDataObjectTypeName(metatable)} value")
                    }
                    newIndexSet(receiver, key, value, newIndex, mutableSetOf(), 0, allowSuspension)
                    return
                }
                if (key !is LuaString) {
                    throw LuaVmException("attempt to index userdata with ${typeName(key)}")
                }
                val setter = receiver.type?.property(key.value)?.setter
                    ?: throw LuaVmException("attempt to set userdata field '${key.value}'")
                callNative(setter, listOf(receiver, value))
            }
            else -> {
                try {
                    primitiveSet(receiver, key, value, mutableSetOf(), 0, allowSuspension)
                } catch (error: LuaTableKeyException) {
                    throw LuaVmException(error.message ?: "invalid table key")
                } catch (error: LuaMetatableException) {
                    throw LuaVmException(error.message ?: "invalid metatable operation")
                }
            }
        }
    }

    private fun stringConstant(prototype: Prototype, index: Int): LuaString {
        val constant = constant(prototype, index)
        if (constant !is LuaString) {
            throw LuaVmException("field opcode expected string constant at K$index")
        }
        return constant
    }

    private fun tableSet(
        table: LuaTable,
        key: LuaValue,
        value: LuaValue,
        allowSuspension: Boolean = false,
    ) {
        try {
            if (table.rawGet(key) != LuaNil) {
                table.rawSet(key, value)
                return
            }
            when (val newIndex = table.metatableRawGet(NEW_INDEX_KEY)) {
                LuaNil -> table.rawSet(key, value)
                else -> newIndexSet(table, key, value, newIndex, mutableSetOf(table), 1, allowSuspension)
            }
        } catch (error: LuaTableKeyException) {
            throw LuaVmException(error.message ?: "invalid table key")
        } catch (error: LuaMetatableException) {
            throw LuaVmException(error.message ?: "invalid metatable operation")
        }
    }

    private fun tableSet(
        table: LuaTable,
        key: LuaValue,
        value: LuaValue,
        visited: MutableSet<LuaTable>,
        depth: Int,
        allowSuspension: Boolean,
    ) {
        if (table.rawGet(key) != LuaNil) {
            table.rawSet(key, value)
            return
        }
        if (depth >= MAX_NEWINDEX_CHAIN_DEPTH) {
            throw LuaMetatableException("'__newindex' chain too long; possible loop")
        }
        if (!visited.add(table)) {
            throw LuaMetatableException("'__newindex' chain too long; possible loop")
        }

        when (val newIndex = table.metatableRawGet(NEW_INDEX_KEY)) {
            LuaNil -> table.rawSet(key, value)
            else -> newIndexSet(table, key, value, newIndex, visited, depth + 1, allowSuspension)
        }
    }

    private fun primitiveSet(
        receiver: LuaValue,
        key: LuaValue,
        value: LuaValue,
        visited: MutableSet<LuaTable>,
        depth: Int,
        allowSuspension: Boolean,
    ) {
        if (depth >= MAX_NEWINDEX_CHAIN_DEPTH) {
            throw LuaMetatableException("'__newindex' chain too long; possible loop")
        }
        val metatable = rawTypeMetatable(receiver)
            ?: throw LuaVmException("attempt to index ${typeName(receiver)}")
        val newIndex = metatable.rawGet(NEW_INDEX_KEY)
        if (newIndex == LuaNil) {
            throw LuaVmException("attempt to index ${typeName(receiver)}")
        }
        newIndexSet(receiver, key, value, newIndex, visited, depth + 1, allowSuspension)
    }

    private fun newIndexSet(
        receiver: LuaValue,
        key: LuaValue,
        value: LuaValue,
        newIndex: LuaValue,
        visited: MutableSet<LuaTable>,
        depth: Int,
        allowSuspension: Boolean,
    ) {
        when (newIndex) {
            is LuaTable -> tableSet(newIndex, key, value, visited, depth, allowSuspension)
            is LuaClosure -> executeMetamethod(
                newIndex,
                receiver,
                key,
                value,
                NEW_INDEX_KEY,
                allowSuspension,
            )
            is LuaNativeFunction -> callNewIndexMetamethod(
                newIndex,
                receiver,
                key,
                value,
                allowSuspension,
            )
            else -> primitiveSet(newIndex, key, value, visited, depth, allowSuspension)
        }
    }

    private fun callNewIndexMetamethod(
        function: LuaNativeFunction,
        receiver: LuaValue,
        key: LuaValue,
        value: LuaValue,
        allowSuspension: Boolean,
    ) {
        val canSuspend = allowSuspension && thread.currentFrame != null && !thread.inNativeCall
        metamethodValues(
            callNativeResult(function, listOf(receiver, key, value)),
            canSuspend,
        )
    }

    private fun executeMetamethod(
        closure: LuaClosure,
        firstArgument: LuaValue,
        secondArgument: LuaValue,
        metamethod: LuaString,
        allowSuspension: Boolean = false,
    ): List<LuaValue> {
        return executeFixedMetamethod(
            closure,
            2,
            firstArgument,
            secondArgument,
            LuaNil,
            metamethod,
            allowSuspension,
        )
    }

    private fun executeMetamethod(
        closure: LuaClosure,
        firstArgument: LuaValue,
        secondArgument: LuaValue,
        thirdArgument: LuaValue,
        metamethod: LuaString,
        allowSuspension: Boolean = false,
    ): List<LuaValue> {
        return executeFixedMetamethod(
            closure,
            3,
            firstArgument,
            secondArgument,
            thirdArgument,
            metamethod,
            allowSuspension,
        )
    }

    private fun executeFixedMetamethod(
        closure: LuaClosure,
        argumentCount: Int,
        firstArgument: LuaValue,
        secondArgument: LuaValue,
        thirdArgument: LuaValue,
        metamethod: LuaString,
        allowSuspension: Boolean,
    ): List<LuaValue> {
        val canSuspend = allowSuspension && thread.currentFrame != null && !thread.inNativeCall
        val frame = thread.pushFixedCall(
            function = closure,
            argumentCount = argumentCount,
            firstArgument = firstArgument,
            secondArgument = secondArgument,
            thirdArgument = thirdArgument,
            environment = closure.environment ?: closure.globals?.let(::LuaUpvalue) ?: rootEnvironment,
            callSiteInfo = metamethodCallSiteInfo(metamethod),
        )
        return metamethodValues(runFrameAndPopOnCompletion(frame), canSuspend)
    }

    private fun metamethodValues(
        result: LuaExecutionResult,
        allowSuspension: Boolean,
    ): List<LuaValue> {
        if (allowSuspension && result !is LuaExecutionResult.Returned) {
            throw LuaMetamethodSuspension(result)
        }
        if (result is LuaExecutionResult.Yielded && thread.inNativeCall) {
            thread.clearFrames()
            throw LuaVmException("attempt to yield across a C-call boundary")
        }
        return returnedValues(result)
    }

    private fun metamethodCallSiteInfo(metamethod: LuaString): CallSiteInfo {
        return when {
            metamethod === INDEX_KEY -> INDEX_CALL_SITE_INFO
            metamethod === NEW_INDEX_KEY -> NEW_INDEX_CALL_SITE_INFO
            metamethod === LEN_KEY -> LEN_CALL_SITE_INFO
            metamethod === EQ_KEY -> EQ_CALL_SITE_INFO
            metamethod === LT_KEY -> LT_CALL_SITE_INFO
            metamethod === LE_KEY -> LE_CALL_SITE_INFO
            metamethod === CONCAT_KEY -> CONCAT_CALL_SITE_INFO
            metamethod === UNM_KEY -> UNM_CALL_SITE_INFO
            metamethod === BAND_KEY -> BAND_CALL_SITE_INFO
            metamethod === BOR_KEY -> BOR_CALL_SITE_INFO
            metamethod === BXOR_KEY -> BXOR_CALL_SITE_INFO
            metamethod === SHL_KEY -> SHL_CALL_SITE_INFO
            metamethod === SHR_KEY -> SHR_CALL_SITE_INFO
            metamethod === BNOT_KEY -> BNOT_CALL_SITE_INFO
            metamethod === ADD_KEY -> ADD_CALL_SITE_INFO
            metamethod === SUB_KEY -> SUB_CALL_SITE_INFO
            metamethod === MUL_KEY -> MUL_CALL_SITE_INFO
            metamethod === DIV_KEY -> DIV_CALL_SITE_INFO
            metamethod === IDIV_KEY -> IDIV_CALL_SITE_INFO
            metamethod === MOD_KEY -> MOD_CALL_SITE_INFO
            metamethod === POW_KEY -> POW_CALL_SITE_INFO
            else -> CallSiteInfo(0, metamethod.value.removePrefix("__"), "metamethod")
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
        stack.set(register(frame, Instruction.a(instruction)), arithmetic(left, right, operation))
    }

    private fun arithmetic(left: LuaValue, right: LuaValue, operation: Arithmetic): LuaValue {
        val direct = operation.applyOrNull(left, right)
        if (direct != null) {
            return direct
        }
        val metamethod = binaryMetamethod(left, right, operation.metamethodKey)
        if (metamethod != null) {
            return callOperatorMetamethod(
                metamethod,
                left,
                right,
                operation.metamethodKey,
                allowSuspension = true,
            )
        }
        throw LuaVmException("attempt to perform arithmetic on ${operationTypeName(arithmeticErrorOperand(left, right))}")
    }

    private fun arithmeticErrorOperand(left: LuaValue, right: LuaValue): LuaValue {
        return if (numberValue(left) == null) left else right
    }

    private fun binaryMetamethod(left: LuaValue, right: LuaValue, key: LuaString): LuaValue? {
        val leftMetamethod = rawMetamethod(left, key)
        if (leftMetamethod != LuaNil) {
            return leftMetamethod
        }
        val rightMetamethod = rawMetamethod(right, key)
        return if (rightMetamethod != LuaNil) rightMetamethod else null
    }

    private fun rawMetamethod(value: LuaValue, key: LuaString): LuaValue {
        return when (value) {
            is LuaTable -> value.metatableRawGet(key)
            is LuaUserData -> metatables?.userDataMetatable(value.value)?.rawGet(key) ?: LuaNil
            else -> rawTypeMetatable(value)?.rawGet(key) ?: LuaNil
        }
    }

    private fun callOperatorMetamethod(
        metamethod: LuaValue,
        firstArgument: LuaValue,
        secondArgument: LuaValue,
        key: LuaString,
        allowSuspension: Boolean = false,
    ): LuaValue {
        val canSuspend = allowSuspension && thread.currentFrame != null && !thread.inNativeCall
        return when (metamethod) {
            is LuaClosure -> executeMetamethod(
                metamethod,
                firstArgument,
                secondArgument,
                key,
                allowSuspension,
            ).firstOrNull() ?: LuaNil
            is LuaNativeFunction -> metamethodValues(
                callNativeResult(metamethod, listOf(firstArgument, secondArgument)),
                canSuspend,
            ).firstOrNull() ?: LuaNil
            else -> metamethodValues(
                callValue(metamethod, listOf(firstArgument, secondArgument), metamethodCallSiteInfo(key)),
                canSuspend,
            ).firstOrNull() ?: LuaNil
        }
    }

    private fun unaryMinus(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val value = stack.get(register(frame, Instruction.b(instruction)))
        val result = when (value) {
            is LuaInteger -> LuaInteger(-value.value)
            is LuaFloat -> LuaFloat(-value.value)
            else -> {
                val metamethod = rawMetamethod(value, UNM_KEY)
                if (metamethod != LuaNil) {
                    callOperatorMetamethod(metamethod, value, value, UNM_KEY, allowSuspension = true)
                } else {
                    throw LuaVmException("attempt to perform arithmetic on ${operationTypeName(value)}")
                }
            }
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
            is LuaString -> LuaInteger(value.value.luaRawBytes().size.toLong())
            is LuaTable -> tableLength(value, allowSuspension = true)
            is LuaUserData -> userDataLength(value, allowSuspension = true)
            else -> primitiveLength(value, allowSuspension = true)
        }
        stack.set(register(frame, Instruction.a(instruction)), result)
    }

    private fun tableLength(table: LuaTable, allowSuspension: Boolean): LuaValue {
        return when (val length = table.metatableRawGet(LEN_KEY)) {
            LuaNil -> LuaInteger(table.rawLength())
            else -> callLengthMetamethod(table, length, allowSuspension)
        }
    }

    private fun userDataLength(value: LuaUserData, allowSuspension: Boolean): LuaValue {
        val metatable = metatables?.userDataMetatable(value.value)
            ?: throw LuaVmException("attempt to get length of userdata")
        val length = metatable.rawGet(LEN_KEY)
        if (length == LuaNil) {
            throw LuaVmException("attempt to get length of a ${userDataObjectTypeName(metatable)} value")
        }
        return callLengthMetamethod(value, length, allowSuspension)
    }

    private fun primitiveLength(value: LuaValue, allowSuspension: Boolean): LuaValue {
        val metatable = rawTypeMetatable(value)
            ?: throw LuaVmException("attempt to get length of ${typeName(value)}")
        val length = metatable.rawGet(LEN_KEY)
        if (length == LuaNil) {
            throw LuaVmException("attempt to get length of ${typeName(value)}")
        }
        return callLengthMetamethod(value, length, allowSuspension)
    }

    private fun callLengthMetamethod(
        value: LuaValue,
        length: LuaValue,
        allowSuspension: Boolean,
    ): LuaValue {
        return callOperatorMetamethod(length, value, value, LEN_KEY, allowSuspension)
    }

    private fun compare(stack: LuaStack, frame: CallFrame, instruction: Int, comparison: Comparison) {
        val left = stack.get(register(frame, Instruction.b(instruction)))
        val right = stack.get(register(frame, Instruction.c(instruction)))
        stack.set(
            register(frame, Instruction.a(instruction)),
            LuaBoolean(compare(left, right, comparison, allowSuspension = true)),
        )
    }

    private fun compare(left: LuaValue, right: LuaValue, comparison: Comparison): Boolean {
        return compare(left, right, comparison, allowSuspension = false)
    }

    private fun compare(
        left: LuaValue,
        right: LuaValue,
        comparison: Comparison,
        allowSuspension: Boolean,
    ): Boolean {
        if (comparison == Comparison.EQ) {
            if (comparison.apply(left, right)) {
                return true
            }
            val comparesByReference = (left is LuaTable && right is LuaTable) ||
                (left is LuaUserData && right is LuaUserData)
            if (!comparesByReference) {
                return false
            }
            val metamethod = binaryMetamethod(left, right, EQ_KEY)
            if (metamethod != null) {
                val result = callOperatorMetamethod(
                    metamethod,
                    left,
                    right,
                    EQ_KEY,
                    allowSuspension,
                )
                return isTruthy(result)
            }
            return false
        }
        try {
            return comparison.apply(left, right)
        } catch (error: LuaVmException) {
            val metamethod = binaryMetamethod(left, right, comparison.metamethodKey)
            if (metamethod == null) {
                throw comparisonError(left, right)
            }
            return isTruthy(
                callOperatorMetamethod(
                    metamethod,
                    left,
                    right,
                    comparison.metamethodKey,
                    allowSuspension,
                ),
            )
        }
    }

    private fun comparisonError(left: LuaValue, right: LuaValue): LuaVmException {
        val leftType = objectTypeName(left)
        val rightType = objectTypeName(right)
        return if (leftType == rightType) {
            LuaVmException("attempt to compare two $leftType values")
        } else {
            LuaVmException("attempt to compare $leftType with $rightType")
        }
    }

    private fun concat(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val leftValue = stack.get(register(frame, Instruction.b(instruction)))
        val rightValue = stack.get(register(frame, Instruction.c(instruction)))
        val left = stringCoercion(leftValue)
        val right = stringCoercion(rightValue)
        if (left == null || right == null) {
            val metamethod = binaryMetamethod(leftValue, rightValue, CONCAT_KEY)
            if (metamethod != null) {
                stack.set(
                    register(frame, Instruction.a(instruction)),
                    callConcatMetamethod(metamethod, leftValue, rightValue),
                )
                return
            }
            val failedValue = if (left == null) leftValue else rightValue
            throw LuaVmException("attempt to concatenate ${operationTypeName(failedValue)}")
        }
        val bytes = left.luaRawBytes() + right.luaRawBytes()
        stack.set(register(frame, Instruction.a(instruction)), LuaString(bytes.toLuaByteString()))
    }

    private fun callConcatMetamethod(
        metamethod: LuaValue,
        firstArgument: LuaValue,
        secondArgument: LuaValue,
    ): LuaValue {
        val canSuspend = thread.currentFrame != null && !thread.inNativeCall
        return when (metamethod) {
            is LuaClosure -> executeMetamethod(
                metamethod,
                firstArgument,
                secondArgument,
                CONCAT_KEY,
                allowSuspension = true,
            ).firstOrNull() ?: LuaNil
            is LuaNativeFunction -> metamethodValues(
                callNativeResult(metamethod, listOf(firstArgument, secondArgument)),
                canSuspend,
            ).firstOrNull() ?: LuaNil
            else -> metamethodValues(
                callValue(metamethod, listOf(firstArgument, secondArgument), metamethodCallSiteInfo(CONCAT_KEY)),
                canSuspend,
            ).firstOrNull() ?: LuaNil
        }
    }

    private fun bitwise(stack: LuaStack, frame: CallFrame, instruction: Int, operation: Bitwise) {
        val leftValue = stack.get(register(frame, Instruction.b(instruction)))
        val rightValue = stack.get(register(frame, Instruction.c(instruction)))
        val left = integerValue(leftValue)
        val right = integerValue(rightValue)
        if (left == null || right == null) {
            val metamethod = binaryMetamethod(leftValue, rightValue, operation.metamethodKey)
            if (metamethod != null) {
                stack.set(
                    register(frame, Instruction.a(instruction)),
                    callOperatorMetamethod(
                        metamethod,
                        leftValue,
                        rightValue,
                        operation.metamethodKey,
                        allowSuspension = true,
                    ),
                )
                return
            }
            val failedValue = if (left == null) leftValue else rightValue
            throw LuaVmException("attempt to perform bitwise operation on ${operationTypeName(failedValue)}")
        }
        stack.set(register(frame, Instruction.a(instruction)), LuaInteger(operation.apply(left, right)))
    }

    private fun bitwiseNot(stack: LuaStack, frame: CallFrame, instruction: Int) {
        val value = stack.get(register(frame, Instruction.b(instruction)))
        val integer = integerValue(value)
        if (integer == null) {
            val metamethod = rawMetamethod(value, BNOT_KEY)
            if (metamethod != LuaNil) {
                stack.set(
                    register(frame, Instruction.a(instruction)),
                    callOperatorMetamethod(metamethod, value, value, BNOT_KEY, allowSuspension = true),
                )
                return
            }
            throw LuaVmException("attempt to perform bitwise operation on ${operationTypeName(value)}")
        }
        stack.set(register(frame, Instruction.a(instruction)), LuaInteger(integer.inv()))
    }

    private fun forLoopContinues(stack: LuaStack, frame: CallFrame, instruction: Int): Boolean {
        val base = register(frame, Instruction.a(instruction))
        val indexValue = stack.get(base)
        val limitValue = stack.get(base + 1)
        val stepValue = stack.get(base + 2)
        if (indexValue is LuaInteger && stepValue is LuaInteger) {
            if (stepValue.value == 0L) {
                throw LuaVmException("'for' step is zero")
            }
            when (val limit = forIntegerLimit(limitValue, stepValue.value)) {
                is ForIntegerLimit.Run -> {
                    val limitInteger = limit.value
                    stack.set(base + 1, LuaInteger(limitInteger))
                    return if (stepValue.value > 0L) {
                        indexValue.value <= limitInteger
                    } else {
                        indexValue.value >= limitInteger
                    }
                }
                ForIntegerLimit.Skip -> return false
                ForIntegerLimit.Invalid -> throw LuaVmException("numeric for limit must be a number")
            }
        }
        val index = forNumberValue(indexValue)
            ?: throw LuaVmException("numeric for index must be a number")
        val limit = forNumberValue(limitValue)
            ?: throw LuaVmException("numeric for limit must be a number")
        val step = forNumberValue(stepValue)
            ?: throw LuaVmException("numeric for step must be a number")
        if (step == 0.0) {
            throw LuaVmException("'for' step is zero")
        }
        stack.set(base, LuaFloat(index))
        stack.set(base + 1, LuaFloat(limit))
        stack.set(base + 2, LuaFloat(step))
        return if (step >= 0.0) index <= limit else index >= limit
    }

    private fun advanceForLoop(stack: LuaStack, frame: CallFrame, instruction: Int): Boolean {
        val base = register(frame, Instruction.a(instruction))
        val index = stack.get(base)
        val limit = stack.get(base + 1)
        val step = stack.get(base + 2)
        if (index is LuaInteger && step is LuaInteger) {
            when (val integerLimit = forIntegerLimit(limit, step.value)) {
                is ForIntegerLimit.Run -> {
                    val limitInteger = integerLimit.value
                    if (integerForHasNext(index.value, limitInteger, step.value)) {
                        stack.set(base, LuaInteger(index.value + step.value))
                        return true
                    }
                    return false
                }
                ForIntegerLimit.Skip -> return false
                ForIntegerLimit.Invalid -> throw LuaVmException("numeric for limit must be a number")
            }
        }

        val indexNumber = forNumberValue(index)
            ?: throw LuaVmException("numeric for index must be a number")
        val stepNumber = forNumberValue(step)
            ?: throw LuaVmException("numeric for step must be a number")
        stack.set(base, LuaFloat(indexNumber + stepNumber))
        return forLoopContinues(stack, frame, instruction)
    }

    private fun integerForHasNext(index: Long, limit: Long, step: Long): Boolean {
        if (step == 0L) {
            throw LuaVmException("'for' step is zero")
        }
        return if (step > 0L) {
            java.lang.Long.compareUnsigned(limit - index, step) >= 0
        } else {
            val magnitude = -(step + 1L) + 1L
            java.lang.Long.compareUnsigned(index - limit, magnitude) >= 0
        }
    }

    private enum class Arithmetic {
        ADD,
        SUB,
        MUL,
        DIV,
        IDIV,
        MOD,
        POW;

        val metamethodKey: LuaString
            get() = when (this) {
                ADD -> ADD_KEY
                SUB -> SUB_KEY
                MUL -> MUL_KEY
                DIV -> DIV_KEY
                IDIV -> IDIV_KEY
                MOD -> MOD_KEY
                POW -> POW_KEY
            }

        fun applyOrNull(left: LuaValue, right: LuaValue): LuaValue? {
            if (left is LuaInteger && right is LuaInteger) {
                return integerArithmetic(left.value, right.value)
            }

            val leftNumber = numberValue(left)
            val rightNumber = numberValue(right)
            if (leftNumber == null || rightNumber == null) {
                return null
            }

            return when (this) {
                ADD -> LuaFloat(leftNumber + rightNumber)
                SUB -> LuaFloat(leftNumber - rightNumber)
                MUL -> LuaFloat(leftNumber * rightNumber)
                DIV -> LuaFloat(leftNumber / rightNumber)
                IDIV -> LuaFloat(kotlin.math.floor(leftNumber / rightNumber))
                MOD -> LuaFloat(floatModulo(leftNumber, rightNumber))
                POW -> LuaFloat(Math.pow(leftNumber, rightNumber))
            }
        }

        private fun integerArithmetic(left: Long, right: Long): LuaValue {
            return when (this) {
                ADD -> LuaInteger(left + right)
                SUB -> LuaInteger(left - right)
                MUL -> LuaInteger(left * right)
                DIV -> LuaFloat(left.toDouble() / right.toDouble())
                IDIV -> {
                    if (right == 0L) {
                        throw LuaVmException("attempt to divide by zero")
                    }
                    LuaInteger(Math.floorDiv(left, right))
                }
                MOD -> {
                    if (right == 0L) {
                        throw LuaVmException("attempt to perform 'n%0'")
                    }
                    LuaInteger(Math.floorMod(left, right))
                }
                POW -> LuaFloat(Math.pow(left.toDouble(), right.toDouble()))
            }
        }

        private fun floatModulo(left: Double, right: Double): Double {
            var result = left % right
            if (if (result > 0.0) right < 0.0 else result < 0.0 && right > 0.0) {
                result += right
            }
            return result
        }
    }

    private enum class Comparison {
        EQ,
        LT,
        LE;

        val metamethodKey: LuaString
            get() = when (this) {
                EQ -> EQ_KEY
                LT -> LT_KEY
                LE -> LE_KEY
            }

        fun apply(left: LuaValue, right: LuaValue): Boolean {
            return when (this) {
                EQ -> luaEquals(left, right)
                LT,
                LE,
                -> orderedCompare(left, right, this)
            }
        }

        fun tryApply(left: LuaValue, right: LuaValue): Boolean? {
            if (this == EQ) {
                return luaEquals(left, right)
            }
            return primitiveOrderedCompare(left, right, this)
        }

        private fun luaEquals(left: LuaValue, right: LuaValue): Boolean {
            val numericEquality = numericEquals(left, right)
            if (numericEquality != null) {
                return numericEquality
            }
            if (left is LuaString && right is LuaString) {
                return luaByteCompare(left.value, right.value) == 0
            }
            if (left is LuaUserData && right is LuaUserData) {
                return left.value === right.value
            }
            return left == right
        }

        private fun numericEquals(left: LuaValue, right: LuaValue): Boolean? {
            return when (left) {
                is LuaInteger -> when (right) {
                    is LuaInteger -> left.value == right.value
                    is LuaFloat -> integerValue(right)?.let { left.value == it } ?: false
                    else -> null
                }
                is LuaFloat -> when (right) {
                    is LuaInteger -> integerValue(left)?.let { it == right.value } ?: false
                    is LuaFloat -> left.value == right.value
                    else -> null
                }
                else -> null
            }
        }

        private fun orderedCompare(left: LuaValue, right: LuaValue, comparison: Comparison): Boolean {
            primitiveOrderedCompare(left, right, comparison)?.let { return it }
            if (typeName(left) == typeName(right)) {
                throw LuaVmException("attempt to compare two ${typeName(left)} values")
            }
            throw LuaVmException("attempt to compare ${typeName(left)} with ${typeName(right)}")
        }

        private fun primitiveOrderedCompare(left: LuaValue, right: LuaValue, comparison: Comparison): Boolean? {
            luaNumberComparison(left, right, comparison)?.let { return it }
            if (left is LuaString && right is LuaString) {
                val byteComparison = luaByteCompare(left.value, right.value)
                return when (comparison) {
                    LT -> byteComparison < 0
                    LE -> byteComparison <= 0
                    EQ -> false
                }
            }
            return null
        }

        private fun luaNumberComparison(left: LuaValue, right: LuaValue, comparison: Comparison): Boolean? {
            return when (left) {
                is LuaInteger -> when (right) {
                    is LuaInteger -> when (comparison) {
                        LT -> left.value < right.value
                        LE -> left.value <= right.value
                        EQ -> false
                    }
                    is LuaFloat -> when (comparison) {
                        LT -> luaIntegerLessThanFloat(left.value, right.value)
                        LE -> luaIntegerLessEqualFloat(left.value, right.value)
                        EQ -> false
                    }
                    else -> null
                }
                is LuaFloat -> when (right) {
                    is LuaInteger -> when (comparison) {
                        LT -> luaFloatLessThanInteger(left.value, right.value)
                        LE -> luaFloatLessEqualInteger(left.value, right.value)
                        EQ -> false
                    }
                    is LuaFloat -> when (comparison) {
                        LT -> left.value < right.value
                        LE -> left.value <= right.value
                        EQ -> false
                    }
                    else -> null
                }
                else -> null
            }
        }

        private fun luaIntegerLessThanFloat(left: Long, right: Double): Boolean {
            val rightCeiling = right.luaCeilToInteger()
            return if (rightCeiling != null) left < rightCeiling else right > 0.0
        }

        private fun luaIntegerLessEqualFloat(left: Long, right: Double): Boolean {
            val rightFloor = right.luaFloorToInteger()
            return if (rightFloor != null) left <= rightFloor else right > 0.0
        }

        private fun luaFloatLessThanInteger(left: Double, right: Long): Boolean {
            val leftFloor = left.luaFloorToInteger()
            return if (leftFloor != null) leftFloor < right else left < 0.0
        }

        private fun luaFloatLessEqualInteger(left: Double, right: Long): Boolean {
            val leftCeiling = left.luaCeilToInteger()
            return if (leftCeiling != null) leftCeiling <= right else left < 0.0
        }

        private fun luaByteCompare(left: String, right: String): Int {
            val leftBytes = left.luaRawBytes()
            val rightBytes = right.luaRawBytes()
            val limit = minOf(leftBytes.size, rightBytes.size)
            for (index in 0 until limit) {
                val comparison = (leftBytes[index].toInt() and 0xff) - (rightBytes[index].toInt() and 0xff)
                if (comparison != 0) {
                    return comparison
                }
            }
            return leftBytes.size - rightBytes.size
        }

    }

    private enum class Bitwise {
        AND,
        OR,
        XOR,
        SHIFT_LEFT,
        SHIFT_RIGHT;

        val metamethodKey: LuaString
            get() = when (this) {
                AND -> BAND_KEY
                OR -> BOR_KEY
                XOR -> BXOR_KEY
                SHIFT_LEFT -> SHL_KEY
                SHIFT_RIGHT -> SHR_KEY
            }

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

private fun forNumberValue(value: LuaValue): Double? {
    return when (value) {
        is LuaInteger -> value.value.toDouble()
        is LuaFloat -> value.value
        is LuaString -> luaNumberFromString(value.value)
        else -> null
    }
}

private sealed interface ForIntegerLimit {
    data class Run(val value: Long) : ForIntegerLimit
    data object Skip : ForIntegerLimit
    data object Invalid : ForIntegerLimit
}

private fun forIntegerLimit(value: LuaValue, step: Long): ForIntegerLimit {
    return when (value) {
        is LuaInteger -> ForIntegerLimit.Run(value.value)
        is LuaFloat -> forIntegerLimitFromNumber(value.value, step)
        is LuaString -> {
            val integer = luaIntegerFromString(value.value)
            if (integer != null) {
                ForIntegerLimit.Run(integer)
            } else {
                val number = luaNumberFromString(value.value) ?: return ForIntegerLimit.Invalid
                forIntegerLimitFromNumber(number, step)
            }
        }
        else -> ForIntegerLimit.Invalid
    }
}

private fun forIntegerLimitFromNumber(value: Double, step: Long): ForIntegerLimit {
    val limit = if (step > 0L) value.luaFloorToInteger() else value.luaCeilToInteger()
    if (limit != null) {
        return ForIntegerLimit.Run(limit)
    }
    return if (value > 0.0) {
        if (step < 0L) ForIntegerLimit.Skip else ForIntegerLimit.Run(Long.MAX_VALUE)
    } else {
        if (step > 0L) ForIntegerLimit.Skip else ForIntegerLimit.Run(Long.MIN_VALUE)
    }
}

private fun luaNumberFromString(value: String): Double? {
    val trimmed = value.trimLuaAsciiWhitespace()
    if (trimmed.isEmpty() || trimmed.isNamedFloatingPointLiteral()) {
        return null
    }
    return luaIntegerFromTrimmedString(trimmed)?.toDouble()
        ?: luaFloatFromTrimmedString(trimmed)
}

private fun luaIntegerFromString(value: String): Long? {
    val trimmed = value.trimLuaAsciiWhitespace()
    if (trimmed.isEmpty()) {
        return null
    }
    return luaIntegerFromTrimmedString(trimmed)
}

private fun luaIntegerFromTrimmedString(value: String): Long? {
    val normalized = value.normalizeLuaNumberDecimalPoint()
    return luaHexIntegerFromString(value) ?: normalized.toLongOrNull()
}

private fun luaFloatFromTrimmedString(value: String): Double? {
    val normalized = value.normalizeLuaNumberDecimalPoint()
    val parsed = if (normalized.isHexNumeral()) {
        val parseable = if (normalized.indexOf('p', ignoreCase = true) < 0) "${normalized}p0" else normalized
        parseable.toDoubleOrNull()
    } else {
        normalized.toDoubleOrNull()
    }
    return parsed?.takeIf { number -> java.lang.Double.isFinite(number) }
}

private fun luaHexIntegerFromString(value: String): Long? {
    val sign = when {
        value.startsWith("-") -> -1
        value.startsWith("+") -> 1
        else -> 1
    }
    val digitsStart = if (value.startsWith("-") || value.startsWith("+")) 1 else 0
    if (!value.regionMatches(digitsStart, "0x", 0, 2, ignoreCase = true)) {
        return null
    }
    val digits = value.substring(digitsStart + 2)
    if (digits.isEmpty() || digits.any { digit -> digit.asciiDigitToIntOrNull(16) == null }) {
        return null
    }
    var parsed = BigInteger.ZERO
    val radix = BigInteger.valueOf(16L)
    for (digit in digits) {
        val hexDigit = digit.asciiDigitToIntOrNull(16) ?: return null
        parsed = parsed.multiply(radix).add(BigInteger.valueOf(hexDigit.toLong()))
    }
    if (sign < 0) {
        parsed = parsed.negate()
    }
    return parsed.mod(UINT64_MODULUS).toLong()
}

private fun Char.asciiDigitToIntOrNull(base: Int): Int? {
    val value = when (this) {
        in '0'..'9' -> code - '0'.code
        in 'a'..'z' -> code - 'a'.code + 10
        in 'A'..'Z' -> code - 'A'.code + 10
        else -> return null
    }
    return value.takeIf { it < base }
}

private fun String.trimLuaAsciiWhitespace(): String {
    return trim { char -> char == ' ' || char == '\u000C' || char == '\n' || char == '\r' || char == '\t' || char == '\u000B' }
}

private fun String.isNamedFloatingPointLiteral(): Boolean {
    val unsigned = trimStart('+', '-')
    return unsigned.equals("nan", ignoreCase = true) || unsigned.equals("infinity", ignoreCase = true)
}

private fun String.normalizeLuaNumberDecimalPoint(): String {
    val decimalPoint = luaLocaleDecimalPoint()
    if (decimalPoint == '.' || decimalPoint !in this || '.' in this) {
        return this
    }
    return replace(decimalPoint, '.')
}

private fun luaLocaleDecimalPoint(): Char {
    return DecimalFormatSymbols.getInstance(Locale.getDefault()).decimalSeparator
}

private fun String.isHexNumeral(): Boolean {
    val digitsStart = if (startsWith("-") || startsWith("+")) 1 else 0
    return regionMatches(digitsStart, "0x", 0, 2, ignoreCase = true)
}

private fun Double.luaFloorToInteger(): Long? {
    if (!java.lang.Double.isFinite(this)) {
        return null
    }
    return kotlin.math.floor(this).luaIntegerInRange()
}

private fun Double.luaCeilToInteger(): Long? {
    if (!java.lang.Double.isFinite(this)) {
        return null
    }
    return kotlin.math.ceil(this).luaIntegerInRange()
}

private fun Double.luaIntegerInRange(): Long? {
    if (this < Long.MIN_VALUE.toDouble() || this >= LONG_MAX_EXCLUSIVE) {
        return null
    }
    return toLong()
}

private const val LONG_BITS = 64L
private const val LONG_MAX_EXCLUSIVE = 9223372036854775808.0
private val UINT64_MODULUS: BigInteger = BigInteger.ONE.shiftLeft(Long.SIZE_BITS)

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
        is LuaClosure,
        is LuaNativeFunction,
        -> "function"
        is LuaFloat, is LuaInteger -> "number"
        LuaNil -> "nil"
        is LuaString -> "string"
        is LuaTable -> "table"
        is LuaUserData -> "userdata"
    }
}

private val INDEX_KEY = LuaString("__index")
private val NAME_KEY = LuaString("__name")
private val STRING_LIBRARY_KEY = LuaString("string")
private val NEW_INDEX_KEY = LuaString("__newindex")
private val CALL_KEY = LuaString("__call")
private val LEN_KEY = LuaString("__len")
private val EQ_KEY = LuaString("__eq")
private val LT_KEY = LuaString("__lt")
private val LE_KEY = LuaString("__le")
private val CONCAT_KEY = LuaString("__concat")
private val UNM_KEY = LuaString("__unm")
private val BAND_KEY = LuaString("__band")
private val BOR_KEY = LuaString("__bor")
private val BXOR_KEY = LuaString("__bxor")
private val SHL_KEY = LuaString("__shl")
private val SHR_KEY = LuaString("__shr")
private val BNOT_KEY = LuaString("__bnot")
private val ADD_KEY = LuaString("__add")
private val SUB_KEY = LuaString("__sub")
private val MUL_KEY = LuaString("__mul")
private val DIV_KEY = LuaString("__div")
private val IDIV_KEY = LuaString("__idiv")
private val MOD_KEY = LuaString("__mod")
private val POW_KEY = LuaString("__pow")
private val INDEX_CALL_SITE_INFO = metamethodCallSiteInfo("index")
private val NEW_INDEX_CALL_SITE_INFO = metamethodCallSiteInfo("newindex")
private val LEN_CALL_SITE_INFO = metamethodCallSiteInfo("len")
private val EQ_CALL_SITE_INFO = metamethodCallSiteInfo("eq")
private val LT_CALL_SITE_INFO = metamethodCallSiteInfo("lt")
private val LE_CALL_SITE_INFO = metamethodCallSiteInfo("le")
private val CONCAT_CALL_SITE_INFO = metamethodCallSiteInfo("concat")
private val UNM_CALL_SITE_INFO = metamethodCallSiteInfo("unm")
private val BAND_CALL_SITE_INFO = metamethodCallSiteInfo("band")
private val BOR_CALL_SITE_INFO = metamethodCallSiteInfo("bor")
private val BXOR_CALL_SITE_INFO = metamethodCallSiteInfo("bxor")
private val SHL_CALL_SITE_INFO = metamethodCallSiteInfo("shl")
private val SHR_CALL_SITE_INFO = metamethodCallSiteInfo("shr")
private val BNOT_CALL_SITE_INFO = metamethodCallSiteInfo("bnot")
private val ADD_CALL_SITE_INFO = metamethodCallSiteInfo("add")
private val SUB_CALL_SITE_INFO = metamethodCallSiteInfo("sub")
private val MUL_CALL_SITE_INFO = metamethodCallSiteInfo("mul")
private val DIV_CALL_SITE_INFO = metamethodCallSiteInfo("div")
private val IDIV_CALL_SITE_INFO = metamethodCallSiteInfo("idiv")
private val MOD_CALL_SITE_INFO = metamethodCallSiteInfo("mod")
private val POW_CALL_SITE_INFO = metamethodCallSiteInfo("pow")
private const val MAX_NEWINDEX_CHAIN_DEPTH = 200
private const val MAX_CALL_METAMETHOD_DEPTH = 200
private const val TRUTHY_PENDING_RESULT_COUNT = -2
private const val VARARG_LOCAL_NAME = "(vararg)"

private fun metamethodCallSiteInfo(name: String): CallSiteInfo {
    return CallSiteInfo(0, name, "metamethod")
}

private fun normalizedDebugHookMask(mask: String): String {
    return buildString {
        if ('c' in mask) append('c')
        if ('r' in mask) append('r')
        if ('l' in mask) append('l')
    }
}

private data class DebugHookState(
    val function: LuaValue,
    val mask: String,
    val count: Int,
    var remainingCount: Int = count,
) {
    val hasCallHook: Boolean
        get() = 'c' in mask

    val hasReturnHook: Boolean
        get() = 'r' in mask

    val hasLineHook: Boolean
        get() = 'l' in mask

    fun toNativeHook(): LuaNativeDebugHook = LuaNativeDebugHook(function, mask, count)
}

private class LuaMetamethodSuspension(
    val result: LuaExecutionResult,
) : RuntimeException(null, null, false, false)
