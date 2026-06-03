package io.github.realmlabs.klua.core.parser

import io.github.realmlabs.klua.core.source.SourcePosition

internal class ParserException(
    val position: SourcePosition,
    message: String,
) : RuntimeException("${position.sourceName}:${position.line}:${position.column}: $message")
