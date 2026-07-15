package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.bytecode.CallSiteInfo
import io.github.realmlabs.klua.core.bytecode.Prototype
import io.github.realmlabs.klua.core.value.LuaUpvalue
import io.github.realmlabs.klua.core.value.LuaValue

internal class CallFrame(
    val prototype: Prototype,
    val function: LuaValue,
    stackSize: Int,
    private val varargValues: MutableList<LuaValue>? = null,
    val upvalues: List<LuaUpvalue> = emptyList(),
    val environment: LuaUpvalue,
    val callSiteInfo: CallSiteInfo? = null,
    var pc: Int = 0,
    var openResultBase: Int = 0,
    var openResultCount: Int = 0,
) : LuaStack(stackSize) {
    private var pendingCall: PendingCallState? = null
    private var debugState: DebugFrameState? = null

    val globals: LuaValue
        get() = environment.value

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

    var lastDebugHookLine: Int
        get() = debugState?.lastDebugHookLine ?: -1
        set(value) {
            debugState().lastDebugHookLine = value
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

    fun setPendingCall(resultBase: Int, expectedResults: Int, continuation: LuaYieldContinuation?) {
        pendingCall = PendingCallState(resultBase, expectedResults, continuation)
    }

    fun takePendingCall(): PendingCallState? {
        return pendingCall.also { pendingCall = null }
    }

    private fun debugState(): DebugFrameState {
        return debugState ?: DebugFrameState().also { created -> debugState = created }
    }
}

internal data class PendingCallState(
    val resultBase: Int,
    val expectedResults: Int,
    val continuation: LuaYieldContinuation?,
)

private class DebugFrameState(
    var lastDebugHookLine: Int = -1,
    var lastDebuggerPc: Int = -1,
    var resumePastDebuggerPc: Int = -1,
    var debuggerSuspendedPc: Int = -1,
    var hookTransferStart: Int = 0,
    var hookTransferCount: Int = 0,
)
