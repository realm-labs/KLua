package io.github.realmlabs.klua.core.parser

import io.github.realmlabs.klua.core.source.SourcePosition
import io.github.realmlabs.klua.core.source.luaSourceNameForError

internal class ParserException(
    val position: SourcePosition,
    message: String,
) : RuntimeException("${luaSourceNameForError(position.sourceName)}:${position.line}:${position.column}: $message")
