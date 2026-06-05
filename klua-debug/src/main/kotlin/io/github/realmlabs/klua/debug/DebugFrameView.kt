package io.github.realmlabs.klua.debug

import io.github.realmlabs.klua.api.LuaLocalVariable
import io.github.realmlabs.klua.api.LuaStackFrame

public data class DebugFrameView(
    public val level: Int,
    public val sourceName: String,
    public val line: Int,
    public val lineDefined: Int = 0,
    public val lastLineDefined: Int = 0,
    public val locals: List<DebugVariable> = emptyList(),
) {
    public companion object {
        public fun fromLuaFrames(frames: List<LuaStackFrame>): List<DebugFrameView> {
            return frames.mapIndexed { index, frame -> frame.toDebugFrameView(index) }
        }
    }
}

public data class DebugVariable(
    public val name: String,
    public val value: Any?,
    public val typeName: String,
    public val displayValue: String,
)

public fun LuaStackFrame.toDebugFrameView(level: Int): DebugFrameView {
    require(level >= 0) { "level must be non-negative: $level" }
    return DebugFrameView(
        level = level,
        sourceName = sourceName,
        line = line,
        lineDefined = lineDefined,
        lastLineDefined = lastLineDefined,
        locals = locals.map { local -> local.toDebugVariable() },
    )
}

public fun LuaLocalVariable.toDebugVariable(): DebugVariable {
    return DebugVariable(
        name = name,
        value = value,
        typeName = debugTypeName(value),
        displayValue = debugDisplayValue(value),
    )
}

private fun debugTypeName(value: Any?): String {
    return when (value) {
        null -> "nil"
        is Boolean -> "boolean"
        is Byte,
        is Short,
        is Int,
        is Long,
        is Float,
        is Double,
        -> "number"
        is CharSequence,
        is Char,
        -> "string"
        is Map<*, *> -> "table"
        else -> "userdata"
    }
}

private fun debugDisplayValue(value: Any?): String {
    return when (value) {
        null -> "nil"
        is Boolean -> value.toString()
        is Byte,
        is Short,
        is Int,
        is Long,
        is Float,
        is Double,
        -> value.toString()
        is Char -> value.toString()
        is CharSequence -> value.toString()
        is Map<*, *> -> "table(${value.size})"
        else -> value::class.java.name
    }
}
