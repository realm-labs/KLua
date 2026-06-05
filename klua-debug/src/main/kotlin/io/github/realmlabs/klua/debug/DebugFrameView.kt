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
    public fun scopes(): List<DebugScopeView> {
        return listOf(DebugScopeView.locals(locals))
    }

    public companion object {
        public fun fromLuaFrames(frames: List<LuaStackFrame>): List<DebugFrameView> {
            return frames.mapIndexed { index, frame -> frame.toDebugFrameView(index) }
        }
    }
}

public enum class DebugScopeKind {
    LOCALS,
    UPVALUES,
    GLOBALS,
}

public data class DebugScopeView(
    public val name: String,
    public val kind: DebugScopeKind,
    public val variables: List<DebugVariable>,
) {
    public companion object {
        public fun locals(variables: List<DebugVariable>): DebugScopeView {
            return DebugScopeView("Locals", DebugScopeKind.LOCALS, variables)
        }
    }
}

public data class DebugVariable(
    public val name: String,
    public val value: Any?,
    public val typeName: String,
    public val displayValue: String,
) {
    public fun childPage(start: Int = 0, count: Int = 50): DebugVariablePage {
        require(start >= 0) { "start must be non-negative: $start" }
        require(count >= 0) { "count must be non-negative: $count" }
        val entries = (value as? Map<*, *>)?.entries?.toList().orEmpty()
        val variables = entries
            .drop(start)
            .take(count)
            .map { entry ->
                DebugVariable(
                    name = debugEntryName(entry.key),
                    value = entry.value,
                    typeName = debugTypeName(entry.value),
                    displayValue = debugDisplayValue(entry.value),
                )
            }
        return DebugVariablePage(
            start = start,
            total = entries.size,
            variables = variables,
        )
    }
}

public data class DebugVariablePage(
    public val start: Int,
    public val total: Int,
    public val variables: List<DebugVariable>,
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

private fun debugEntryName(key: Any?): String {
    return when (key) {
        is CharSequence -> key.toString()
        is Char -> key.toString()
        else -> "[${debugDisplayValue(key)}]"
    }
}
