package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.value.LuaValue

internal class LuaVmException(
    message: String,
    val sourceName: String? = null,
    val line: Int? = null,
    val luaFrames: List<LuaVmStackFrame> = emptyList(),
    val errorObject: LuaValue? = null,
    val errorObjectFinalized: Boolean = false,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    fun withFrame(frame: CallFrame, line: Int): LuaVmException {
        if (line <= 0) {
            return this
        }
        val stackFrame = LuaVmStackFrame(
            frame.prototype.sourceName,
            line,
            frame.prototype.lineDefined,
            frame.prototype.lastLineDefined,
            frame.prototype.upvalues.size,
            frame.prototype.numParams,
            frame.prototype.isVararg,
            frame.prototype.validBreakpointLines.toList(),
            callSiteName = frame.callSiteName,
            callSiteNameWhat = frame.callSiteNameWhat,
        )
        return LuaVmException(
            message ?: "runtime error",
            this.sourceName ?: stackFrame.sourceName,
            this.line ?: line,
            luaFrames + stackFrame,
            errorObject,
            errorObjectFinalized,
            this,
        )
    }

    fun withFinalErrorObject(errorObject: LuaValue, message: String): LuaVmException {
        return LuaVmException(
            message,
            sourceName,
            line,
            luaFrames,
            errorObject,
            errorObjectFinalized = true,
            cause = this,
        )
    }
}

internal data class LuaVmStackFrame(
    val sourceName: String,
    val line: Int = 0,
    val lineDefined: Int = 0,
    val lastLineDefined: Int = 0,
    val upvalueCount: Int = 0,
    val parameterCount: Int = 0,
    val isVararg: Boolean = false,
    val activeLines: List<Int> = emptyList(),
    val callSiteName: String? = null,
    val callSiteNameWhat: String = "",
    val transferStart: Int = 0,
    val transferCount: Int = 0,
)
