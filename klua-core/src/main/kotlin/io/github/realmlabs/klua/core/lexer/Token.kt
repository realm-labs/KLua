package io.github.realmlabs.klua.core.lexer

import io.github.realmlabs.klua.core.source.SourceRange

internal data class Token(
    val kind: TokenKind,
    val lexeme: String,
    val range: SourceRange,
    val literal: Any? = null,
)
