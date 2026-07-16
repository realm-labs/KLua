package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.bytecode.CallSiteInfo
import io.github.realmlabs.klua.core.bytecode.Prototype
import io.github.realmlabs.klua.core.value.LuaClosure
import io.github.realmlabs.klua.core.value.LuaUpvalue
import io.github.realmlabs.klua.core.value.LuaValue

internal class CallFrame(
    val function: LuaClosure,
    stackSize: Int,
    private val varargValues: MutableList<LuaValue>? = null,
    val environment: LuaUpvalue,
    val callSiteInfo: CallSiteInfo? = null,
    val isTailCall: Boolean = false,
    val extraArgumentCount: Int = 0,
    var pc: Int = 0,
    var openResultBase: Int = 0,
    var openResultCount: Int = 0,
) : LuaStack(stackSize) {
    private var pendingCall: PendingCallState? = null
    private var debugState: DebugFrameState? = null
    private var protectedState: ProtectedFrameState? = null
    private var toBeClosedSlots: MutableList<Int>? = null

    val globals: LuaValue
        get() = environment.value

    val prototype: Prototype
        get() = function.prototype

    val upvalues: List<LuaUpvalue>
        get() = function.upvalues

    val stack: LuaStack
        get() = this

    val varargs: List<LuaValue>
        get() = varargValues ?: emptyList()

    fun setVararg(index: Int, value: LuaValue): Boolean {
        val values = varargValues ?: return false
        if (index !in values.indices) {
            return false
        }
        values[index] = value
        return true
    }

    val callSiteName: String?
        get() = callSiteInfo?.name

    val callSiteNameWhat: String
        get() = callSiteInfo?.nameWhat ?: ""

    val pendingCallResultBase: Int
        get() = pendingCall?.resultBase ?: -1

    val pendingCallExpectedResults: Int
        get() = pendingCall?.expectedResults ?: -1

    val hasToBeClosedValues: Boolean
        get() = !toBeClosedSlots.isNullOrEmpty()

    var lastDebugHookLine: Int
        get() = debugState?.lastDebugHookLine ?: -1
        set(value) {
            debugState().lastDebugHookLine = value
        }

    var lastDebugHookPc: Int
        get() = debugState?.lastDebugHookPc ?: -1
        set(value) {
            debugState().lastDebugHookPc = value
        }

    var completedLuaTailCall: Boolean
        get() = debugState?.completedLuaTailCall ?: false
        set(value) {
            debugState().completedLuaTailCall = value
        }

    var callHookDispatched: Boolean
        get() = debugState?.callHookDispatched ?: false
        set(value) {
            debugState().callHookDispatched = value
        }

    var protectedErrorHandler: LuaValue?
        get() = protectedState?.errorHandler
        set(value) {
            if (value != null || protectedState != null) {
                protectedState().errorHandler = value
            }
        }

    var protectedCallCompletion: LuaProtectedCallCompletion?
        get() = protectedState?.completion
        set(value) {
            if (value != null || protectedState != null) {
                protectedState().completion = value
            }
        }

    var lastDebuggerPc: Int
        get() = debugState?.lastDebuggerPc ?: -1
        set(value) {
            debugState().lastDebuggerPc = value
        }

    var resumePastDebuggerPc: Int
        get() = debugState?.resumePastDebuggerPc ?: -1
        set(value) {
            debugState().resumePastDebuggerPc = value
        }

    var debuggerSuspendedPc: Int
        get() = debugState?.debuggerSuspendedPc ?: -1
        set(value) {
            debugState().debuggerSuspendedPc = value
        }

    var hookTransferStart: Int
        get() = debugState?.hookTransferStart ?: 0
        set(value) {
            debugState().hookTransferStart = value
        }

    var hookTransferCount: Int
        get() = debugState?.hookTransferCount ?: 0
        set(value) {
            debugState().hookTransferCount = value
        }

    fun setPendingCall(
        resultBase: Int,
        expectedResults: Int,
        continuation: LuaYieldContinuation?,
        completesLuaTailCall: Boolean = false,
    ) {
        pendingCall = PendingCallState(resultBase, expectedResults, continuation, completesLuaTailCall)
    }

    fun takePendingCall(): PendingCallState? {
        return pendingCall.also { pendingCall = null }
    }

    fun markToBeClosed(slot: Int) {
        val slots = toBeClosedSlots ?: mutableListOf<Int>().also { created -> toBeClosedSlots = created }
        require(slots.lastOrNull()?.let { previous -> slot > previous } ?: true) {
            "to-be-closed stack slots must be marked in ascending order"
        }
        slots += slot
    }

    fun takeToBeClosedFrom(slot: Int): Int? {
        val slots = toBeClosedSlots ?: return null
        val candidate = slots.lastOrNull()?.takeIf { marked -> marked >= slot } ?: return null
        slots.removeLast()
        if (slots.isEmpty()) {
            toBeClosedSlots = null
        }
        return candidate
    }

    private fun debugState(): DebugFrameState {
        return debugState ?: DebugFrameState().also { created -> debugState = created }
    }

    private fun protectedState(): ProtectedFrameState {
        return protectedState ?: ProtectedFrameState().also { created -> protectedState = created }
    }
}

internal data class PendingCallState(
    val resultBase: Int,
    val expectedResults: Int,
    val continuation: LuaYieldContinuation?,
    val completesLuaTailCall: Boolean,
)

private class DebugFrameState(
    var lastDebugHookLine: Int = -1,
    var lastDebugHookPc: Int = -1,
    var completedLuaTailCall: Boolean = false,
    var callHookDispatched: Boolean = false,
    var lastDebuggerPc: Int = -1,
    var resumePastDebuggerPc: Int = -1,
    var debuggerSuspendedPc: Int = -1,
    var hookTransferStart: Int = 0,
    var hookTransferCount: Int = 0,
)

private class ProtectedFrameState(
    var errorHandler: LuaValue? = null,
    var completion: LuaProtectedCallCompletion? = null,
)
