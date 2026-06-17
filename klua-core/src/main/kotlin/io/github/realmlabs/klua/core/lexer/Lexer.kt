package io.github.realmlabs.klua.core.lexer

import io.github.realmlabs.klua.core.source.SourcePosition
import io.github.realmlabs.klua.core.source.SourceRange

internal class Lexer(
    private val source: String,
    private val sourceName: String = "chunk",
) {
    private var offset = 0
    private var line = 1
    private var column = 1

    fun tokenize(): List<Token> {
        val tokens = mutableListOf<Token>()
        do {
            val token = nextToken()
            tokens += token
        } while (token.kind != TokenKind.EOF)
        return tokens
    }

    fun nextToken(): Token {
        skipWhitespaceAndComments()
        val start = position()

        if (isAtEnd()) {
            return token(TokenKind.EOF, "", start, null)
        }

        longBracketEquals()?.let { equals ->
            return longString(start, equals)
        }

        if (peek() == '.' && peekNext().isDigit()) {
            return leadingDotNumber(start)
        }

        val char = advance()
        return when {
            char.isIdentifierStart() -> identifier(start)
            char.isDigit() -> number(start)
            char == '"' || char == '\'' -> string(start, char)
            else -> symbol(start, char)
        }
    }

    private fun identifier(start: SourcePosition): Token {
        while (peek().isIdentifierPart()) {
            advance()
        }

        val text = source.substring(start.offset, offset)
        val kind = keywords[text] ?: TokenKind.IDENTIFIER
        return token(kind, text, start, if (kind == TokenKind.IDENTIFIER) text else null)
    }

    private fun number(start: SourcePosition): Token {
        if (source[start.offset] == '0' && (peek() == 'x' || peek() == 'X')) {
            return hexadecimalNumber(start)
        }

        while (peek().isDigit()) {
            advance()
        }

        var isFloat = false
        if (peek() == '.' && peekNext() != '.') {
            isFloat = true
            advance()
            while (peek().isDigit()) {
                advance()
            }
        }

        if (peek() == 'e' || peek() == 'E') {
            isFloat = true
            advance()
            if (peek() == '+' || peek() == '-') {
                advance()
            }
            if (!peek().isDigit()) {
                throw errorAt(position(), "expected exponent digits")
            }
            while (peek().isDigit()) {
                advance()
            }
        }

        val text = source.substring(start.offset, offset)
        return if (isFloat) {
            token(TokenKind.FLOAT, text, start, text.toDouble())
        } else {
            val integer = text.toLongOrNull()
            if (integer != null) {
                token(TokenKind.INTEGER, text, start, integer)
            } else {
                token(TokenKind.FLOAT, text, start, text.toDouble())
            }
        }
    }

    private fun leadingDotNumber(start: SourcePosition): Token {
        advance()
        while (peek().isDigit()) {
            advance()
        }

        if (peek() == 'e' || peek() == 'E') {
            advance()
            if (peek() == '+' || peek() == '-') {
                advance()
            }
            if (!peek().isDigit()) {
                throw errorAt(position(), "expected exponent digits")
            }
            while (peek().isDigit()) {
                advance()
            }
        }

        val text = source.substring(start.offset, offset)
        return token(TokenKind.FLOAT, text, start, text.toDouble())
    }

    private fun hexadecimalNumber(start: SourcePosition): Token {
        advance()
        var digitCount = 0
        while (peek().isHexDigit()) {
            advance()
            digitCount++
        }

        var isFloat = false
        if (peek() == '.' && peekNext() != '.') {
            isFloat = true
            advance()
            while (peek().isHexDigit()) {
                advance()
                digitCount++
            }
        }

        if (digitCount == 0) {
            throw errorAt(start, "malformed hexadecimal numeral")
        }

        if (peek() == 'p' || peek() == 'P') {
            isFloat = true
            advance()
            if (peek() == '+' || peek() == '-') {
                advance()
            }
            if (!peek().isDigit()) {
                throw errorAt(position(), "expected exponent digits")
            }
            while (peek().isDigit()) {
                advance()
            }
        }

        val text = source.substring(start.offset, offset)
        return if (isFloat) {
            token(TokenKind.FLOAT, text, start, parseHexFloat(text))
        } else {
            token(TokenKind.INTEGER, text, start, parseHexInteger(text))
        }
    }

    private fun parseHexFloat(text: String): Double {
        val parseable = if (text.indexOf('p', ignoreCase = true) < 0) {
            "${text}p0"
        } else {
            text
        }
        return java.lang.Double.parseDouble(parseable)
    }

    private fun parseHexInteger(text: String): Long {
        var value = 0UL
        for (index in 2 until text.length) {
            value = value * 16UL + text[index].hexValue().toULong()
        }
        return value.toLong()
    }

    private fun string(start: SourcePosition, quote: Char): Token {
        val builder = StringBuilder()
        while (!isAtEnd() && peek() != quote) {
            val char = advance()
            if (char == '\n') {
                throw errorAt(start, "unterminated string literal")
            }

            if (char == '\\') {
                builder.append(readEscape(start))
            } else {
                builder.append(char)
            }
        }

        if (isAtEnd()) {
            throw errorAt(start, "unterminated string literal")
        }

        advance()
        return token(TokenKind.STRING, source.substring(start.offset, offset), start, builder.toString())
    }

    private fun longString(start: SourcePosition, equals: Int): Token {
        consumeLongBracketOpening(equals)
        val literal = readLongBracketContent(equals, start, "unterminated long string")
        return token(TokenKind.STRING, source.substring(start.offset, offset), start, literal)
    }

    private fun readEscape(start: SourcePosition): String {
        if (isAtEnd()) {
            throw errorAt(start, "unterminated escape sequence")
        }

        return when (val escaped = advance()) {
            'a' -> "\u0007"
            'b' -> "\b"
            'f' -> "\u000C"
            'n' -> "\n"
            'r' -> "\r"
            't' -> "\t"
            'v' -> "\u000B"
            'x' -> readHexEscape(start)
            'u' -> readUnicodeEscape(start)
            'z' -> {
                while (peek().isWhitespace()) {
                    advance()
                }
                ""
            }
            '\\' -> "\\"
            '"' -> "\""
            '\'' -> "'"
            '\n' -> "\n"
            in '0'..'9' -> readDecimalEscape(start, escaped)
            else -> throw errorAt(start, "invalid escape sequence")
        }
    }

    private fun readDecimalEscape(start: SourcePosition, firstDigit: Char): String {
        var value = firstDigit.digitToInt()
        repeat(2) {
            if (!isAtEnd() && peek() in '0'..'9') {
                value = value * 10 + advance().digitToInt()
            }
        }
        if (value > 255) {
            throw errorAt(start, "escape sequence out of range")
        }
        return value.toChar().toString()
    }

    private fun readHexEscape(start: SourcePosition): String {
        val high = readRequiredHexDigit(start)
        val low = readRequiredHexDigit(start)
        val value = high * 16 + low
        return value.toChar().toString()
    }

    private fun readRequiredHexDigit(start: SourcePosition): Int {
        if (isAtEnd() || !peek().isHexDigit()) {
            throw errorAt(start, "expected two hexadecimal digits in escape sequence")
        }
        return advance().digitToInt(16)
    }

    private fun readUnicodeEscape(start: SourcePosition): String {
        if (!match('{')) {
            throw errorAt(start, "expected '{' after unicode escape")
        }
        if (peek() == '}') {
            throw errorAt(start, "expected hexadecimal digits in unicode escape")
        }

        var value = 0L
        while (!isAtEnd() && peek() != '}') {
            if (!peek().isHexDigit()) {
                throw errorAt(start, "expected hexadecimal digit in unicode escape")
            }
            value = value * 16 + advance().digitToInt(16)
            if (value > Character.MAX_CODE_POINT) {
                throw errorAt(start, "unicode escape out of range")
            }
        }

        if (!match('}')) {
            throw errorAt(start, "unterminated unicode escape")
        }

        val codePoint = value.toInt()
        if (!Character.isValidCodePoint(codePoint) || codePoint in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) {
            throw errorAt(start, "unicode escape out of range")
        }
        return String(Character.toChars(codePoint))
    }

    private fun symbol(start: SourcePosition, char: Char): Token {
        val kind = when (char) {
            '+' -> TokenKind.PLUS
            '-' -> TokenKind.MINUS
            '*' -> TokenKind.STAR
            '/' -> if (match('/')) TokenKind.DOUBLE_SLASH else TokenKind.SLASH
            '%' -> TokenKind.PERCENT
            '^' -> TokenKind.CARET
            '#' -> TokenKind.HASH
            '&' -> TokenKind.AMPERSAND
            '|' -> TokenKind.PIPE
            '~' -> when {
                match('=') -> TokenKind.NOT_EQUAL
                match('~') -> TokenKind.DOUBLE_TILDE
                else -> TokenKind.TILDE
            }
            '=' -> if (match('=')) TokenKind.EQUAL_EQUAL else TokenKind.ASSIGN
            '<' -> when {
                match('<') -> TokenKind.LEFT_SHIFT
                match('=') -> TokenKind.LESS_EQUAL
                else -> TokenKind.LESS
            }
            '>' -> when {
                match('>') -> TokenKind.RIGHT_SHIFT
                match('=') -> TokenKind.GREATER_EQUAL
                else -> TokenKind.GREATER
            }
            '.' -> when {
                match('.') && match('.') -> TokenKind.DOT_DOT_DOT
                previousWasDoubleDot() -> TokenKind.DOT_DOT
                else -> TokenKind.DOT
            }
            ',' -> TokenKind.COMMA
            ';' -> TokenKind.SEMICOLON
            ':' -> if (match(':')) TokenKind.DOUBLE_COLON else TokenKind.COLON
            '(' -> TokenKind.LEFT_PAREN
            ')' -> TokenKind.RIGHT_PAREN
            '{' -> TokenKind.LEFT_BRACE
            '}' -> TokenKind.RIGHT_BRACE
            '[' -> TokenKind.LEFT_BRACKET
            ']' -> TokenKind.RIGHT_BRACKET
            else -> throw errorAt(start, "unexpected character '$char'")
        }

        return token(kind, source.substring(start.offset, offset), start, null)
    }

    private fun previousWasDoubleDot(): Boolean {
        if (offset < 2) {
            return false
        }
        return source[offset - 1] == '.' && source[offset - 2] == '.'
    }

    private fun skipWhitespaceAndComments() {
        var keepGoing = true
        while (keepGoing) {
            keepGoing = false

            while (peek().isWhitespace()) {
                advance()
            }

            if (peek() == '-' && peekNext() == '-') {
                val commentStart = position()
                advance()
                advance()
                val equals = longBracketEquals()
                if (equals != null) {
                    consumeLongBracketOpening(equals)
                    readLongBracketContent(equals, commentStart, "unterminated long comment")
                } else {
                    while (!isAtEnd() && peek() != '\n') {
                        advance()
                    }
                }
                keepGoing = true
            }
        }
    }

    private fun readLongBracketContent(equals: Int, start: SourcePosition, errorMessage: String): String {
        if (peek() == '\r' || peek() == '\n') {
            advance()
        }

        val builder = StringBuilder()
        while (!isAtEnd()) {
            if (matchesLongBracketClose(equals)) {
                consumeLongBracketClose(equals)
                return builder.toString()
            }
            builder.append(advance())
        }
        throw errorAt(start, errorMessage)
    }

    private fun longBracketEquals(): Int? {
        if (peek() != '[') {
            return null
        }

        var index = offset + 1
        var equals = 0
        while (index < source.length && source[index] == '=') {
            index++
            equals++
        }

        return if (index < source.length && source[index] == '[') equals else null
    }

    private fun consumeLongBracketOpening(equals: Int) {
        advance()
        repeat(equals) {
            advance()
        }
        advance()
    }

    private fun matchesLongBracketClose(equals: Int): Boolean {
        if (peek() != ']') {
            return false
        }

        val closeIndex = offset + equals + 1
        if (closeIndex >= source.length || source[closeIndex] != ']') {
            return false
        }

        for (index in 1..equals) {
            if (source[offset + index] != '=') {
                return false
            }
        }
        return true
    }

    private fun consumeLongBracketClose(equals: Int) {
        advance()
        repeat(equals) {
            advance()
        }
        advance()
    }

    private fun token(
        kind: TokenKind,
        lexeme: String,
        start: SourcePosition,
        literal: Any?,
    ): Token = Token(kind, lexeme, SourceRange(start, position()), literal)

    private fun match(expected: Char): Boolean {
        if (peek() != expected) {
            return false
        }
        advance()
        return true
    }

    private fun advance(): Char {
        val char = source[offset++]
        if (char == '\r') {
            if (peek() == '\n') {
                offset++
            }
            line++
            column = 1
            return '\n'
        }
        if (char == '\n') {
            line++
            column = 1
        } else {
            column++
        }
        return char
    }

    private fun peek(): Char = if (isAtEnd()) '\u0000' else source[offset]

    private fun peekNext(): Char = if (offset + 1 >= source.length) '\u0000' else source[offset + 1]

    private fun isAtEnd(): Boolean = offset >= source.length

    private fun position(): SourcePosition = SourcePosition(sourceName, offset, line, column)

    private fun errorAt(position: SourcePosition, message: String): LexerException = LexerException(position, message)

    private fun Char.isIdentifierStart(): Boolean = this == '_' || this in 'A'..'Z' || this in 'a'..'z'

    private fun Char.isIdentifierPart(): Boolean = isIdentifierStart() || isDigit()

    private fun Char.isDigit(): Boolean = this in '0'..'9'

    private fun Char.isHexDigit(): Boolean = isDigit() || this in 'A'..'F' || this in 'a'..'f'

    private fun Char.hexValue(): Int {
        return when (this) {
            in '0'..'9' -> this - '0'
            in 'A'..'F' -> this - 'A' + 10
            else -> this - 'a' + 10
        }
    }

    private companion object {
        val keywords = mapOf(
            "and" to TokenKind.AND,
            "break" to TokenKind.BREAK,
            "do" to TokenKind.DO,
            "else" to TokenKind.ELSE,
            "elseif" to TokenKind.ELSEIF,
            "end" to TokenKind.END,
            "false" to TokenKind.FALSE,
            "for" to TokenKind.FOR,
            "function" to TokenKind.FUNCTION,
            "goto" to TokenKind.GOTO,
            "if" to TokenKind.IF,
            "in" to TokenKind.IN,
            "local" to TokenKind.LOCAL,
            "nil" to TokenKind.NIL,
            "not" to TokenKind.NOT,
            "or" to TokenKind.OR,
            "repeat" to TokenKind.REPEAT,
            "return" to TokenKind.RETURN,
            "then" to TokenKind.THEN,
            "true" to TokenKind.TRUE,
            "until" to TokenKind.UNTIL,
            "while" to TokenKind.WHILE,
        )
    }
}
