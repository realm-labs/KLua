package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaRuntimeException

internal data class LuaPatternMatch(
    val startIndex: Int,
    val endIndex: Int,
    val captures: List<Any?> = emptyList(),
)

private data class PatternResult(
    val endIndex: Int,
    val captures: Map<Int, Any?>,
)

internal class LuaStringPattern private constructor(
    private val pattern: String,
    private val tokens: List<Token>?,
    internal val startAnchored: Boolean = false,
    private val endAnchored: Boolean = false,
) {
    fun find(text: String, startIndex: Int): LuaPatternMatch? {
        val patternTokens = tokens
        return if (patternTokens == null) {
            if (startIndex > text.length) {
                return null
            }
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
        var index = startIndex
        while (index <= text.length) {
            val result = matchEnd(text, index, patternTokens, tokenIndex = 0, captureStarts = emptyMap(), captures = emptyMap())
            if (index >= startIndex && result != null) {
                val captures = result.captures.toSortedMap().values.toList()
                return LuaPatternMatch(index, result.endIndex, captures)
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
        captureStarts: Map<Int, Int>,
        captures: Map<Int, Any?>,
    ): PatternResult? {
        if (tokenIndex >= patternTokens.size) {
            return if (!endAnchored || textIndex == text.length) PatternResult(textIndex, captures) else null
        }

        return when (val token = patternTokens[tokenIndex]) {
            is Token.CaptureStart -> matchEnd(
                text,
                textIndex,
                patternTokens,
                tokenIndex + 1,
                captureStarts + (checkCaptureLimit(token.index) to textIndex),
                captures,
            )
            is Token.CaptureEnd -> {
                val startIndex = captureStarts[token.index]
                    ?: throw LuaRuntimeException("string patterns are not supported")
                matchEnd(
                    text,
                    textIndex,
                    patternTokens,
                    tokenIndex + 1,
                    captureStarts,
                    captures + (token.index to text.substring(startIndex, textIndex)),
                )
            }
            is Token.PositionCapture -> matchEnd(
                text,
                textIndex,
                patternTokens,
                tokenIndex + 1,
                captureStarts,
                captures + (checkCaptureLimit(token.index) to (textIndex + 1L)),
            )
            is Token.BackReference -> {
                if (token.index < 0) {
                    throw LuaRuntimeException("invalid capture index %${token.displayIndex}")
                }
                val captured = captures[token.index]
                if (captured == null && token.index !in captures) {
                    throw LuaRuntimeException("invalid capture index %${token.displayIndex}")
                }
                val capture = captured as? String ?: return null
                if (!text.startsWith(capture, textIndex)) {
                    null
                } else {
                    matchEnd(text, textIndex + capture.length, patternTokens, tokenIndex + 1, captureStarts, captures)
                }
            }
            is Token.Balanced -> {
                val endIndex = matchBalanced(text, textIndex, token.open, token.close) ?: return null
                matchEnd(text, endIndex, patternTokens, tokenIndex + 1, captureStarts, captures)
            }
            is Token.Frontier -> {
                val previous = if (textIndex > 0) text[textIndex - 1] else '\u0000'
                val current = if (textIndex < text.length) text[textIndex] else '\u0000'
                if (!token.set.matches(previous) && token.set.matches(current)) {
                    matchEnd(text, textIndex, patternTokens, tokenIndex + 1, captureStarts, captures)
                } else {
                    null
                }
            }
            is Token.Repetition -> matchRepetition(text, textIndex, patternTokens, tokenIndex, token, captureStarts, captures)
            else -> {
                if (textIndex >= text.length || !token.matches(text[textIndex])) {
                    null
                } else {
                    matchEnd(text, textIndex + 1, patternTokens, tokenIndex + 1, captureStarts, captures)
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
        captureStarts: Map<Int, Int>,
        captures: Map<Int, Any?>,
    ): PatternResult? {
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
            val matchEnd = matchEnd(text, candidateEnd, patternTokens, tokenIndex + 1, captureStarts, captures)
            if (matchEnd != null) {
                return matchEnd
            }
        }
        return null
    }

    private fun matchBalanced(text: String, textIndex: Int, open: Char, close: Char): Int? {
        if (textIndex >= text.length || text[textIndex] != open) {
            return null
        }

        var depth = 1
        var index = textIndex + 1
        while (index < text.length) {
            val char = text[index]
            if (char == close) {
                depth--
                if (depth == 0) {
                    return index + 1
                }
            } else if (char == open) {
                depth++
            }
            index++
        }
        return null
    }

    private fun checkCaptureLimit(index: Int): Int {
        if (index >= MAX_CAPTURES) {
            throw LuaRuntimeException("too many captures")
        }
        return index
    }

    internal companion object {
        private const val MAX_CAPTURES = 32

        fun literal(pattern: String): LuaStringPattern {
            return LuaStringPattern(pattern, tokens = null)
        }

        fun compile(pattern: String): LuaStringPattern {
            return compile(pattern, allowStartAnchor = true)
        }

        fun compileGmatch(pattern: String): LuaStringPattern {
            return compile(pattern, allowStartAnchor = false)
        }

        private fun compile(pattern: String, allowStartAnchor: Boolean): LuaStringPattern {
            val startAnchored = allowStartAnchor && pattern.startsWith('^')
            val endAnchored = hasEndAnchor(pattern, startAnchored)
            val bodyStart = if (startAnchored) 1 else 0
            val bodyEnd = if (endAnchored) pattern.length - 1 else pattern.length
            val body = pattern.substring(bodyStart, bodyEnd)
            val tokens = tokenize(body)
            val patternTokens = if (tokens != null || startAnchored || endAnchored) {
                tokens ?: body.map { Token.Literal(it) }
            } else {
                null
            }
            return LuaStringPattern(pattern, tokens = patternTokens, startAnchored, endAnchored)
        }

        private fun hasEndAnchor(pattern: String, startAnchored: Boolean): Boolean {
            if (!pattern.endsWith('$') || pattern.length <= if (startAnchored) 1 else 0) {
                return false
            }
            var percentCount = 0
            var index = pattern.length - 2
            while (index >= 0 && pattern[index] == '%') {
                percentCount++
                index--
            }
            return percentCount % 2 == 0
        }

        private fun tokenize(pattern: String): List<Token>? {
            val tokens = mutableListOf<Token>()
            val captureStack = mutableListOf<Int>()
            var captureCount = 0
            var index = 0
            var hasPatternToken = false
            while (index < pattern.length) {
                when (val char = pattern[index]) {
                    '(' -> {
                        if (index + 1 < pattern.length && pattern[index + 1] == ')') {
                            tokens += Token.PositionCapture(captureCount)
                            index += 2
                        } else {
                            tokens += Token.CaptureStart(captureCount)
                            captureStack += captureCount
                            index++
                        }
                        captureCount++
                        hasPatternToken = true
                    }
                    ')' -> {
                        if (captureStack.isEmpty()) {
                            throw LuaRuntimeException("string patterns are not supported")
                        }
                        val captureIndex = captureStack.removeAt(captureStack.lastIndex)
                        tokens += Token.CaptureEnd(captureIndex)
                        hasPatternToken = true
                        index++
                    }
                    '.' -> {
                        index = addToken(tokens, Token.AnyChar, pattern, index + 1)
                        hasPatternToken = true
                    }
                    '%' -> {
                        if (index + 1 >= pattern.length) {
                            throw LuaRuntimeException("malformed pattern (ends with '%')")
                        }
                        val next = pattern[index + 1]
                        if (next in '0'..'9') {
                            tokens += Token.BackReference(next.digitToInt() - 1, next.digitToInt())
                            hasPatternToken = true
                            index += 2
                        } else if (next == 'b') {
                            if (index + 3 >= pattern.length) {
                                throw LuaRuntimeException("malformed pattern (missing arguments to '%b')")
                            }
                            tokens += Token.Balanced(pattern[index + 2], pattern[index + 3])
                            hasPatternToken = true
                            index += 4
                        } else if (next == 'f') {
                            if (index + 2 >= pattern.length || pattern[index + 2] != '[') {
                                throw LuaRuntimeException("missing '[' after '%f' in pattern")
                            }
                            val (token, nextIndex) = bracketClassToken(pattern, index + 2)
                            tokens += Token.Frontier(token)
                            hasPatternToken = true
                            index = nextIndex
                        } else {
                            val token = percentToken(next)
                            if (token == null) {
                                throw LuaRuntimeException("string patterns are not supported")
                            }
                            index = addToken(tokens, token, pattern, index + 2)
                            hasPatternToken = true
                        }
                    }
                    '[' -> {
                        val (token, nextIndex) = bracketClassToken(pattern, index)
                        index = addToken(tokens, token, pattern, nextIndex)
                        hasPatternToken = true
                    }
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
            if (captureStack.isNotEmpty()) {
                throw LuaRuntimeException("string patterns are not supported")
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
                        ranges += start..start
                        index++
                        continue
                    }
                    return Token.CharSet(ranges, tokens, negated) to index + 1
                }
                if (start == '%') {
                    if (index + 1 >= pattern.length) {
                        throw LuaRuntimeException("malformed pattern (ends with '%')")
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

            throw LuaRuntimeException("malformed pattern (missing ']')")
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
                else -> if (!char.isLetterOrDigit()) Token.Literal(char) else null
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

    data class CaptureStart(
        val index: Int,
    ) : Token {
        override fun matches(char: Char): Boolean = false
    }

    data class CaptureEnd(
        val index: Int,
    ) : Token {
        override fun matches(char: Char): Boolean = false
    }

    data class PositionCapture(
        val index: Int,
    ) : Token {
        override fun matches(char: Char): Boolean = false
    }

    data class BackReference(
        val index: Int,
        val displayIndex: Int,
    ) : Token {
        override fun matches(char: Char): Boolean = false
    }

    data class Balanced(
        val open: Char,
        val close: Char,
    ) : Token {
        override fun matches(char: Char): Boolean = false
    }

    data class Frontier(
        val set: Token,
    ) : Token {
        override fun matches(char: Char): Boolean = false
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

private const val UNSUPPORTED_MAGIC = "]*+-?"

private fun Char.isAsciiPunctuation(): Boolean {
    return this in '!'..'/' || this in ':'..'@' || this in '['..'`' || this in '{'..'~'
}
