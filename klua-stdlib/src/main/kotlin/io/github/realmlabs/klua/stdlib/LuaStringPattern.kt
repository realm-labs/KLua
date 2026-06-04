package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaRuntimeException

internal data class LuaPatternMatch(
    val startIndex: Int,
    val endIndex: Int,
)

internal class LuaStringPattern private constructor(
    private val pattern: String,
    private val plain: Boolean,
) {
    fun find(text: String, startIndex: Int): LuaPatternMatch? {
        return if (plain) {
            val foundIndex = text.indexOf(pattern, startIndex)
            if (foundIndex < 0) {
                null
            } else {
                LuaPatternMatch(foundIndex, foundIndex + pattern.length)
            }
        } else {
            findWildcard(text, startIndex)
        }
    }

    private fun findWildcard(text: String, startIndex: Int): LuaPatternMatch? {
        val lastStart = text.length - pattern.length
        var index = startIndex
        while (index <= lastStart) {
            if (matchesAt(text, index)) {
                return LuaPatternMatch(index, index + pattern.length)
            }
            index++
        }
        return null
    }

    private fun matchesAt(text: String, startIndex: Int): Boolean {
        for (offset in pattern.indices) {
            val patternChar = pattern[offset]
            if (patternChar != '.' && patternChar != text[startIndex + offset]) {
                return false
            }
        }
        return true
    }

    internal companion object {
        fun literal(pattern: String): LuaStringPattern {
            return LuaStringPattern(pattern, plain = true)
        }

        fun compile(pattern: String): LuaStringPattern {
            if (pattern.hasUnsupportedPatternMagic()) {
                throw LuaRuntimeException("string patterns are not supported")
            }
            return LuaStringPattern(pattern, plain = !pattern.contains('.'))
        }
    }
}

private fun String.hasUnsupportedPatternMagic(): Boolean {
    return any { char -> char in "^$()%[]*+-?" }
}
