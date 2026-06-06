package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.value.LuaValue

internal class LuaVmException(
    message: String,
    val sourceName: String? = null,
    val line: Int? = null,
    val luaFrames: List<LuaVmStackFrame> = emptyList(),
    val errorObject: LuaValue? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    fun withLocation(sourceName: String, line: Int): LuaVmException {
        if (line <= 0) {
            return this
        }
        val frame = LuaVmStackFrame(sourceName, line)
        return LuaVmException(
            message ?: "runtime error",
            this.sourceName ?: sourceName,
            this.line ?: line,
            luaFrames + frame,
            errorObject,
            this,
        )
    }
}

internal data class LuaVmStackFrame(
    val sourceName: String,
    val line: Int = 0,
)
