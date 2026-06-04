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
        var index = if (startAnchored) 0 else startIndex
        while (index <= text.length) {
            val endIndex = matchEnd(text, index, patternTokens, tokenIndex = 0)
            if (index >= startIndex && endIndex != null) {
                return LuaPatternMatch(index, endIndex)
            }
            if (startAnchored) {
                return null
            }
            index++
        }
        return null
    }

    private fun matchEnd(
        text: String,
        textIndex: Int,
        patternTokens: List<Token>,
        tokenIndex: Int,
    ): Int? {
        if (tokenIndex >= patternTokens.size) {
            return if (!endAnchored || textIndex == text.length) textIndex else null
        }

        return when (val token = patternTokens[tokenIndex]) {
            is Token.Repetition -> matchRepetition(text, textIndex, patternTokens, tokenIndex, token)
            else -> {
                if (textIndex >= text.length || !token.matches(text[textIndex])) {
                    null
                } else {
                    matchEnd(text, textIndex + 1, patternTokens, tokenIndex + 1)
                }
            }
        }
    }

    private fun matchRepetition(
        text: String,
        textIndex: Int,
        patternTokens: List<Token>,
        tokenIndex: Int,
        repetition: Token.Repetition,
    ): Int? {
        var endIndex = textIndex
        while (
            endIndex < text.length &&
            (repetition.maximum == null || endIndex - textIndex < repetition.maximum) &&
            repetition.token.matches(text[endIndex])
        ) {
            endIndex++
        }

        val minimumEnd = textIndex + repetition.minimum
        if (endIndex < minimumEnd) {
            return null
        }

        val candidateEnds = if (repetition.greedy) {
            endIndex downTo minimumEnd
        } else {
            minimumEnd..endIndex
        }
        for (candidateEnd in candidateEnds) {
            val matchEnd = matchEnd(text, candidateEnd, patternTokens, tokenIndex + 1)
            if (matchEnd != null) {
                return matchEnd
            }
        }
        return null
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
                        index = addToken(tokens, Token.AnyChar, pattern, index + 1)
                        hasPatternToken = true
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
                        index = addToken(tokens, token, pattern, index + 2)
                        hasPatternToken = true
                    }
                    '[' -> {
                        val (token, nextIndex) = bracketClassToken(pattern, index)
                        index = addToken(tokens, token, pattern, nextIndex)
                        hasPatternToken = true
                    }
                    '^',
                    '$',
                    in UNSUPPORTED_MAGIC,
                    -> throw LuaRuntimeException("string patterns are not supported")
                    else -> {
                        if (index + 1 < pattern.length && pattern[index + 1] in "?*+-") {
                            hasPatternToken = true
                        }
                        index = addToken(tokens, Token.Literal(char), pattern, index + 1)
                    }
                }
            }
            return if (hasPatternToken) tokens else null
        }

        private fun addToken(tokens: MutableList<Token>, token: Token, pattern: String, nextIndex: Int): Int {
            if (nextIndex < pattern.length) {
                when (pattern[nextIndex]) {
                    '?' -> {
                        tokens += Token.Repetition(token, minimum = 0, maximum = 1, greedy = true)
                        return nextIndex + 1
                    }
                    '*' -> {
                        tokens += Token.Repetition(token, minimum = 0, maximum = null, greedy = true)
                        return nextIndex + 1
                    }
                    '+' -> {
                        tokens += Token.Repetition(token, minimum = 1, maximum = null, greedy = true)
                        return nextIndex + 1
                    }
                    '-' -> {
                        tokens += Token.Repetition(token, minimum = 0, maximum = null, greedy = false)
                        return nextIndex + 1
                    }
                }
            }
            tokens += token
            return nextIndex
        }

        private fun bracketClassToken(pattern: String, startIndex: Int): Pair<Token, Int> {
            var index = startIndex + 1
            var negated = false
            if (index < pattern.length && pattern[index] == '^') {
                negated = true
                index++
            }

            val ranges = mutableListOf<CharRange>()
            val tokens = mutableListOf<Token>()
            while (index < pattern.length) {
                val start = pattern[index]
                if (start == ']') {
                    if (ranges.isEmpty() && tokens.isEmpty()) {
                        throw LuaRuntimeException("string patterns are not supported")
                    }
                    return Token.CharSet(ranges, tokens, negated) to index + 1
                }
                if (start == '%') {
                    if (index + 1 >= pattern.length) {
                        throw LuaRuntimeException("string patterns are not supported")
                    }
                    val token = percentToken(pattern[index + 1])
                    if (token == null) {
                        throw LuaRuntimeException("string patterns are not supported")
                    }
                    tokens += token
                    index += 2
                    continue
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
        val tokens: List<Token>,
        val negated: Boolean,
    ) : Token {
        override fun matches(char: Char): Boolean {
            val contains = ranges.any { range -> char in range } || tokens.any { token -> token.matches(char) }
            return if (negated) !contains else contains
        }
    }

    data class Repetition(
        val token: Token,
        val minimum: Int,
        val maximum: Int?,
        val greedy: Boolean,
    ) : Token {
        override fun matches(char: Char): Boolean = token.matches(char)
    }
}

private const val UNSUPPORTED_MAGIC = "^$()]*+-?"
private const val ESCAPABLE_LITERAL = "^$()%.[]*+-?"

private fun Char.isAsciiPunctuation(): Boolean {
    return this in '!'..'/' || this in ':'..'@' || this in '['..'`' || this in '{'..'~'
}
