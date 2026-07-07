package io.github.realmlabs.klua.core.compiler

import io.github.realmlabs.klua.core.source.SourcePosition
import io.github.realmlabs.klua.core.source.luaSourceNameForError

internal class CompilerException(
    val position: SourcePosition,
    message: String,
) : RuntimeException("${luaSourceNameForError(position.sourceName)}:${position.line}:${position.column}: $message")
