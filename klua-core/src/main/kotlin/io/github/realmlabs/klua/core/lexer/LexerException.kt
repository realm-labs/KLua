package io.github.realmlabs.klua.core.lexer

import io.github.realmlabs.klua.core.source.SourcePosition

internal class LexerException(
    val position: SourcePosition,
    message: String,
) : RuntimeException("${position.sourceName}:${position.line}:${position.column}: $message")
