package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaRuntimeException

internal data class LuaPatternMatch(
    val startIndex: Int,
    val endIndex: Int,
)

internal class LuaStringPattern private constructor(
    private val pattern: String,
    private val tokens: List<Token>?,
    private val startAnchored: Boolean = false,
    private val endAnchored: Boolean = false,
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
        var index = if (startAnchored) 0 else startIndex
        while (index <= lastStart) {
            if (index >= startIndex && matchesAt(text, index, patternTokens)) {
                return LuaPatternMatch(index, index + patternTokens.size)
            }
            if (startAnchored) {
                return null
            }
            index++
        }
        return null
    }

    private fun matchesAt(text: String, startIndex: Int, patternTokens: List<Token>): Boolean {
        if (endAnchored && startIndex + patternTokens.size != text.length) {
            return false
        }
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
            val startAnchored = pattern.startsWith('^')
            val endAnchored = pattern.endsWith('$') && pattern.length > if (startAnchored) 1 else 0
            val bodyStart = if (startAnchored) 1 else 0
            val bodyEnd = if (endAnchored) pattern.length - 1 else pattern.length
            val body = pattern.substring(bodyStart, bodyEnd)
            val tokens = tokenize(body)
            return LuaStringPattern(pattern, tokens = tokens ?: body.map { Token.Literal(it) }, startAnchored, endAnchored)
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
                    '[' -> {
                        val (token, nextIndex) = bracketClassToken(pattern, index)
                        tokens += token
                        hasPatternToken = true
                        index = nextIndex
                    }
                    '^',
                    '$',
                    in UNSUPPORTED_MAGIC,
                    -> throw LuaRuntimeException("string patterns are not supported")
                    else -> {
                        tokens += Token.Literal(char)
                        index++
                    }
                }
            }
            return if (hasPatternToken) tokens else null
        }

        private fun bracketClassToken(pattern: String, startIndex: Int): Pair<Token, Int> {
            var index = startIndex + 1
            var negated = false
            if (index < pattern.length && pattern[index] == '^') {
                negated = true
                index++
            }

            val ranges = mutableListOf<CharRange>()
            while (index < pattern.length) {
                val start = pattern[index]
                if (start == ']') {
                    if (ranges.isEmpty()) {
                        throw LuaRuntimeException("string patterns are not supported")
                    }
                    return Token.CharSet(ranges, negated) to index + 1
                }
                if (start == '%') {
                    throw LuaRuntimeException("string patterns are not supported")
                }

                index++
                if (index + 1 < pattern.length && pattern[index] == '-' && pattern[index + 1] != ']') {
                    val end = pattern[index + 1]
                    if (end == '%' || start > end) {
                        throw LuaRuntimeException("string patterns are not supported")
                    }
                    ranges += start..end
                    index += 2
                } else {
                    ranges += start..start
                }
            }

            throw LuaRuntimeException("string patterns are not supported")
        }

        private fun percentToken(char: Char): Token? {
            return when (char) {
                'a' -> Token.CharClass { value -> value.isLetter() }
                'A' -> Token.CharClass { value -> !value.isLetter() }
                'c' -> Token.CharClass { value -> value.code in 0..31 || value.code == 127 }
                'C' -> Token.CharClass { value -> value.code !in 0..31 && value.code != 127 }
                'd' -> Token.CharClass { value -> value.isDigit() }
                'D' -> Token.CharClass { value -> !value.isDigit() }
                'g' -> Token.CharClass { value -> !value.isWhitespace() && value.code !in 0..31 && value.code != 127 }
                'G' -> Token.CharClass { value -> value.isWhitespace() || value.code in 0..31 || value.code == 127 }
                'l' -> Token.CharClass { value -> value.isLowerCase() }
                'L' -> Token.CharClass { value -> !value.isLowerCase() }
                'p' -> Token.CharClass { value -> value.isAsciiPunctuation() }
                'P' -> Token.CharClass { value -> !value.isAsciiPunctuation() }
                's' -> Token.CharClass { value -> value.isWhitespace() }
                'S' -> Token.CharClass { value -> !value.isWhitespace() }
                'u' -> Token.CharClass { value -> value.isUpperCase() }
                'U' -> Token.CharClass { value -> !value.isUpperCase() }
                'w' -> Token.CharClass { value -> value.isLetterOrDigit() }
                'W' -> Token.CharClass { value -> !value.isLetterOrDigit() }
                'x' -> Token.CharClass { value -> value.isDigit() || value in 'a'..'f' || value in 'A'..'F' }
                'X' -> Token.CharClass { value -> !(value.isDigit() || value in 'a'..'f' || value in 'A'..'F') }
                'z' -> Token.CharClass { value -> value == '\u0000' }
                'Z' -> Token.CharClass { value -> value != '\u0000' }
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

    data class CharSet(
        val ranges: List<CharRange>,
        val negated: Boolean,
    ) : Token {
        override fun matches(char: Char): Boolean {
            val contains = ranges.any { range -> char in range }
            return if (negated) !contains else contains
        }
    }
}

private const val UNSUPPORTED_MAGIC = "^$()]*+-?"
private const val ESCAPABLE_LITERAL = "^$()%.[]*+-?"

private fun Char.isAsciiPunctuation(): Boolean {
    return this in '!'..'/' || this in ':'..'@' || this in '['..'`' || this in '{'..'~'
}
