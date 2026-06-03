package io.github.realmlabs.klua.core.compiler

import io.github.realmlabs.klua.core.source.SourcePosition

internal class CompilerException(
    val position: SourcePosition,
    message: String,
) : RuntimeException("${position.sourceName}:${position.line}:${position.column}: $message")
