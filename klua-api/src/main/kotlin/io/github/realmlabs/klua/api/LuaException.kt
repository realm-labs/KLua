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
    val errorObjectFinalized: Boolean = false,
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
    val varargs: List<Any?> = emptyList(),
    val locals: List<LuaLocalVariable> = emptyList(),
    val upvalues: List<LuaUpvalueVariable> = emptyList(),
    val globals: List<LuaLocalVariable> = emptyList(),
    val callSiteName: String? = null,
    val callSiteNameWhat: String = "",
    val transferStart: Int = 0,
    val transferCount: Int = 0,
)

data class LuaLocalVariable(
    val name: String,
    val value: Any?,
)

data class LuaUpvalueVariable(
    val name: String,
    val value: Any?,
)

internal fun formatLuaTraceback(message: String, frames: List<LuaStackFrame>): String {
    return buildString {
        append(message)
        append("\nstack traceback:")
        for (frame in frames) {
            append("\n\t")
            append(luaShortSourceName(frame.sourceName))
            if (frame.line > 0) {
                append(':')
                append(frame.line)
            }
        }
    }
}

private fun luaShortSourceName(source: String): String {
    return when {
        source.startsWith("[Kotlin]") || source == "[C]" -> source
        source.startsWith("=") -> source.drop(1).take(LUA_IDSIZE - 1)
        source.startsWith("@") -> {
            val file = source.drop(1)
            if (source.length <= LUA_IDSIZE) {
                file
            } else {
                LUA_SOURCE_RETS + file.takeLast(LUA_IDSIZE - LUA_SOURCE_RETS.length)
            }
        }
        else -> {
            val firstLine = source.substringBefore('\n')
            val reserved = LUA_SOURCE_PREFIX.length + LUA_SOURCE_RETS.length + LUA_SOURCE_SUFFIX.length + 1
            val available = LUA_IDSIZE - reserved
            val body = if (source.length < available && '\n' !in source) {
                source
            } else {
                firstLine.take(available) + LUA_SOURCE_RETS
            }
            LUA_SOURCE_PREFIX + body + LUA_SOURCE_SUFFIX
        }
    }
}

private const val LUA_IDSIZE = 60
private const val LUA_SOURCE_RETS = "..."
private const val LUA_SOURCE_PREFIX = "[string \""
private const val LUA_SOURCE_SUFFIX = "\"]"
