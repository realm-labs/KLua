package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaRuntimeException

internal data class LuaPatternMatch(
    val startIndex: Int,
    val endIndex: Int,
)

internal class LuaStringPattern private constructor(
    private val pattern: String,
    private val tokens: List<Token>?,
) {
    fun find(text: String, startIndex: Int): LuaPatternMatch? {
        val patternTokens = tokens
        return if (patternTokens == null) {
            val foundIndex = text.indexOf(pattern, startIndex)
            if (foundIndex < 0) {
                null
            } else {
                LuaPatternMatch(foundIndex, foundIndex + pattern.length)
            }
        } else {
            findPattern(text, startIndex, patternTokens)
        }
    }

    private fun findPattern(text: String, startIndex: Int, patternTokens: List<Token>): LuaPatternMatch? {
        val lastStart = text.length - patternTokens.size
        var index = startIndex
        while (index <= lastStart) {
            if (matchesAt(text, index, patternTokens)) {
                return LuaPatternMatch(index, index + patternTokens.size)
            }
            index++
        }
        return null
    }

    private fun matchesAt(text: String, startIndex: Int, patternTokens: List<Token>): Boolean {
        for (offset in patternTokens.indices) {
            if (!patternTokens[offset].matches(text[startIndex + offset])) {
                return false
            }
        }
        return true
    }

    internal companion object {
        fun literal(pattern: String): LuaStringPattern {
            return LuaStringPattern(pattern, tokens = null)
        }

        fun compile(pattern: String): LuaStringPattern {
            val tokens = tokenize(pattern)
            return LuaStringPattern(pattern, tokens = tokens)
        }

        private fun tokenize(pattern: String): List<Token>? {
            val tokens = mutableListOf<Token>()
            var index = 0
            var hasPatternToken = false
            while (index < pattern.length) {
                when (val char = pattern[index]) {
                    '.' -> {
                        tokens += Token.AnyChar
                        hasPatternToken = true
                        index++
                    }
                    '%' -> {
                        if (index + 1 >= pattern.length) {
                            throw LuaRuntimeException("string patterns are not supported")
                        }
                        val next = pattern[index + 1]
                        val token = percentToken(next)
                        if (token == null) {
                            throw LuaRuntimeException("string patterns are not supported")
                        }
                        tokens += token
                        hasPatternToken = true
                        index += 2
                    }
                    in UNSUPPORTED_MAGIC -> throw LuaRuntimeException("string patterns are not supported")
                    else -> {
                        tokens += Token.Literal(char)
                        index++
                    }
                }
            }
            return if (hasPatternToken) tokens else null
        }

        private fun percentToken(char: Char): Token? {
            return when (char) {
                'a' -> Token.CharClass { value -> value.isLetter() }
                'A' -> Token.CharClass { value -> !value.isLetter() }
                'd' -> Token.CharClass { value -> value.isDigit() }
                'D' -> Token.CharClass { value -> !value.isDigit() }
                's' -> Token.CharClass { value -> value.isWhitespace() }
                'S' -> Token.CharClass { value -> !value.isWhitespace() }
                'w' -> Token.CharClass { value -> value.isLetterOrDigit() }
                'W' -> Token.CharClass { value -> !value.isLetterOrDigit() }
                in ESCAPABLE_LITERAL -> Token.Literal(char)
                else -> null
            }
        }
    }
}

private sealed interface Token {
    fun matches(char: Char): Boolean

    data object AnyChar : Token {
        override fun matches(char: Char): Boolean = true
    }

    data class Literal(
        val char: Char,
    ) : Token {
        override fun matches(char: Char): Boolean = this.char == char
    }

    data class CharClass(
        val predicate: (Char) -> Boolean,
    ) : Token {
        override fun matches(char: Char): Boolean = predicate(char)
    }
}

private const val UNSUPPORTED_MAGIC = "^$()[]*+-?"
private const val ESCAPABLE_LITERAL = "^$()%.[]*+-?"
