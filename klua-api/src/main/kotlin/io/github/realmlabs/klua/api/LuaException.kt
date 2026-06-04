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
) : LuaException(message, cause) {
    val traceback: String = formatLuaTraceback(message, luaFrames)
}

data class LuaStackFrame(
    val sourceName: String,
    val line: Int,
)

private fun formatLuaTraceback(message: String, frames: List<LuaStackFrame>): String {
    return buildString {
        append(message)
        append("\nstack traceback:")
        for (frame in frames) {
            append("\n\t")
            append(frame.sourceName)
            append(':')
            append(frame.line)
        }
    }
}
