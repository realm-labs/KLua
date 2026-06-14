package io.github.realmlabs.klua.api

open class LuaException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class LuaSyntaxException(
    message: String,
    cause: Throwable? = null,
) : LuaException(message, cause)

class LuaRuntimeException(
    message: String,
    cause: Throwable? = null,
    val sourceName: String? = null,
    val line: Int? = null,
    val luaFrames: List<LuaStackFrame> = emptyList(),
    val errorObject: Any? = null,
    val hasErrorObject: Boolean = false,
) : LuaException(message, cause) {
    val traceback: String = formatLuaTraceback(message, luaFrames)
}

data class LuaStackFrame(
    val sourceName: String,
    val line: Int,
    val lineDefined: Int = 0,
    val lastLineDefined: Int = 0,
    val upvalueCount: Int = 0,
    val parameterCount: Int = 0,
    val isVararg: Boolean = false,
    val activeLines: List<Int> = emptyList(),
    val function: Any? = null,
    val locals: List<LuaLocalVariable> = emptyList(),
)

data class LuaLocalVariable(
    val name: String,
    val value: Any?,
)

internal fun formatLuaTraceback(message: String, frames: List<LuaStackFrame>): String {
    return buildString {
        append(message)
        append("\nstack traceback:")
        for (frame in frames) {
            append("\n\t")
            append(frame.sourceName)
            if (frame.line > 0) {
                append(':')
                append(frame.line)
            }
        }
    }
}
