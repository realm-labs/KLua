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
            val result = matchEnd(
                text,
                index,
                patternTokens,
                tokenIndex = 0,
                captureStarts = emptyMap(),
                captures = emptyMap(),
                remainingDepth = MAX_MATCH_DEPTH,
            )
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
        remainingDepth: Int,
    ): PatternResult? {
        if (remainingDepth == 0) {
            throw LuaRuntimeException("pattern too complex")
        }
        if (tokenIndex >= patternTokens.size) {
            if (endAnchored && textIndex != text.length) {
                return null
            }
            if (captureStarts.isNotEmpty()) {
                throw LuaRuntimeException("unfinished capture")
            }
            return PatternResult(textIndex, captures)
        }

        return when (val token = patternTokens[tokenIndex]) {
            is Token.CaptureStart -> matchEnd(
                text,
                textIndex,
                patternTokens,
                tokenIndex + 1,
                captureStarts + (checkCaptureLimit(token.index) to textIndex),
                captures,
                remainingDepth - 1,
            )
            is Token.CaptureEnd -> {
                val captureIndex = token.index
                    ?: throw LuaRuntimeException("invalid pattern capture")
                val startIndex = captureStarts[captureIndex]
                    ?: throw LuaRuntimeException("invalid pattern capture")
                matchEnd(
                    text,
                    textIndex,
                    patternTokens,
                    tokenIndex + 1,
                    captureStarts - captureIndex,
                    captures + (captureIndex to text.substring(startIndex, textIndex)),
                    remainingDepth - 1,
                )
            }
            is Token.PositionCapture -> matchEnd(
                text,
                textIndex,
                patternTokens,
                tokenIndex + 1,
                captureStarts,
                captures + (checkCaptureLimit(token.index) to (textIndex + 1L)),
                remainingDepth - 1,
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
                    matchEnd(text, textIndex + capture.length, patternTokens, tokenIndex + 1, captureStarts, captures, remainingDepth)
                }
            }
            is Token.Balanced -> {
                val endIndex = matchBalanced(text, textIndex, token.open, token.close) ?: return null
                matchEnd(text, endIndex, patternTokens, tokenIndex + 1, captureStarts, captures, remainingDepth)
            }
            is Token.Frontier -> {
                val previous = if (textIndex > 0) text[textIndex - 1] else '\u0000'
                val current = if (textIndex < text.length) text[textIndex] else '\u0000'
                if (!token.set.matches(previous) && token.set.matches(current)) {
                    matchEnd(text, textIndex, patternTokens, tokenIndex + 1, captureStarts, captures, remainingDepth)
                } else {
                    null
                }
            }
            is Token.PatternError -> throw LuaRuntimeException(token.message)
            is Token.Repetition -> matchRepetition(
                text,
                textIndex,
                patternTokens,
                tokenIndex,
                token,
                captureStarts,
                captures,
                remainingDepth,
            )
            else -> {
                if (textIndex >= text.length || !token.matches(text[textIndex])) {
                    null
                } else {
                    matchEnd(text, textIndex + 1, patternTokens, tokenIndex + 1, captureStarts, captures, remainingDepth)
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
        remainingDepth: Int,
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
            val matchEnd = matchEnd(
                text,
                candidateEnd,
                patternTokens,
                tokenIndex + 1,
                captureStarts,
                captures,
                remainingDepth - 1,
            )
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
        private const val MAX_MATCH_DEPTH = 200

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
                        val captureIndex = if (captureStack.isEmpty()) {
                            null
                        } else {
                            captureStack.removeAt(captureStack.lastIndex)
                        }
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
                            tokens += Token.PatternError("malformed pattern (ends with '%')")
                            hasPatternToken = true
                            index = pattern.length
                            continue
                        }
                        val next = pattern[index + 1]
                        if (next in '0'..'9') {
                            tokens += Token.BackReference(next.digitToInt() - 1, next.digitToInt())
                            hasPatternToken = true
                            index += 2
                        } else if (next == 'b') {
                            if (index + 3 >= pattern.length) {
                                tokens += Token.PatternError("malformed pattern (missing arguments to '%b')")
                                hasPatternToken = true
                                index = pattern.length
                                continue
                            }
                            tokens += Token.Balanced(pattern[index + 2], pattern[index + 3])
                            hasPatternToken = true
                            index += 4
                        } else if (next == 'f') {
                            if (index + 2 >= pattern.length || pattern[index + 2] != '[') {
                                tokens += Token.PatternError("missing '[' after '%f' in pattern")
                                hasPatternToken = true
                                index = pattern.length
                                continue
                            }
                            val (token, nextIndex) = bracketClassToken(pattern, index + 2)
                            tokens += Token.Frontier(token)
                            hasPatternToken = true
                            index = nextIndex
                        } else {
                            val token = percentToken(next)
                            index = addToken(tokens, token, pattern, index + 2)
                            hasPatternToken = true
                        }
                    }
                    '[' -> {
                        val (token, nextIndex) = bracketClassToken(pattern, index)
                        index = addToken(tokens, token, pattern, nextIndex)
                        hasPatternToken = true
                    }
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
            var sawItem = false
            while (index < pattern.length) {
                val start = pattern[index]
                if (start == ']') {
                    if (!sawItem) {
                        ranges += start..start
                        sawItem = true
                        index++
                        continue
                    }
                    return Token.CharSet(ranges, tokens, negated) to index + 1
                }
                if (start == '%') {
                    if (index + 1 >= pattern.length) {
                        return Token.PatternError("malformed pattern (missing ']')") to pattern.length
                    }
                    val token = percentToken(pattern[index + 1])
                    tokens += token
                    sawItem = true
                    index += 2
                    continue
                }

                index++
                if (index + 1 < pattern.length && pattern[index] == '-' && pattern[index + 1] != ']') {
                    val end = pattern[index + 1]
                    if (start <= end) {
                        ranges += start..end
                    }
                    val nextIndex = index + 2
                    if (end == '%' && nextIndex < pattern.length && pattern[nextIndex] == ']') {
                        if (pattern.indexOf(']', startIndex = nextIndex + 1) < 0) {
                            throw LuaRuntimeException("malformed pattern (missing ']')")
                        }
                        ranges += ']'..']'
                        index = nextIndex + 1
                    } else {
                        index = nextIndex
                    }
                } else {
                    ranges += start..start
                }
                sawItem = true
            }

            return Token.PatternError("malformed pattern (missing ']')") to pattern.length
        }

        private fun percentToken(char: Char): Token {
            return Token.CharClass(char)
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
        val className: Char,
    ) : Token {
        override fun matches(char: Char): Boolean = LuaPatternByteClass.matches(char.code, className)
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

    data class PatternError(
        val message: String,
    ) : Token {
        override fun matches(char: Char): Boolean = throw LuaRuntimeException(message)
    }

    data class CaptureStart(
        val index: Int,
    ) : Token {
        override fun matches(char: Char): Boolean = false
    }

    data class CaptureEnd(
        val index: Int?,
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

private object LuaPatternByteClass {
    fun matches(value: Int, className: Char): Boolean {
        val normalizedClass = when (className) {
            in 'A'..'Z' -> (className.code + ('a' - 'A')).toChar()
            else -> className
        }
        val matches = when (normalizedClass) {
            'a' -> value in 'A'.code..'Z'.code || value in 'a'.code..'z'.code
            'c' -> value in 0..31 || value == 127
            'd' -> value in '0'.code..'9'.code
            'g' -> value in '!'.code..'~'.code
            'l' -> value in 'a'.code..'z'.code
            'p' -> value in '!'.code..'/'.code ||
                value in ':'.code..'@'.code ||
                value in '['.code..'`'.code ||
                value in '{'.code..'~'.code
            's' -> value == ' '.code || value in '\t'.code..'\r'.code
            'u' -> value in 'A'.code..'Z'.code
            'w' -> value in '0'.code..'9'.code ||
                value in 'A'.code..'Z'.code ||
                value in 'a'.code..'z'.code
            'x' -> value in '0'.code..'9'.code ||
                value in 'A'.code..'F'.code ||
                value in 'a'.code..'f'.code
            'z' -> value == 0
            else -> return value == className.code
        }
        return if (className in 'a'..'z') matches else !matches
    }
}
