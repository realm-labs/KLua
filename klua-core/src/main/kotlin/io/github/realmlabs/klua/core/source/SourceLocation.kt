package io.github.realmlabs.klua.core.source

internal data class SourcePosition(
    val sourceName: String,
    val offset: Int,
    val line: Int,
    val column: Int,
)

internal data class SourceRange(
    val start: SourcePosition,
    val end: SourcePosition,
)
