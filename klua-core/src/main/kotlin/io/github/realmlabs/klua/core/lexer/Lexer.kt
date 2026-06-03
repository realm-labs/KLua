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
            token(TokenKind.INTEGER, text, start, text.toLong())
        }
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

    private fun readEscape(start: SourcePosition): Char {
        if (isAtEnd()) {
            throw errorAt(start, "unterminated escape sequence")
        }

        return when (val escaped = advance()) {
            'a' -> '\u0007'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'v' -> '\u000B'
            '\\' -> '\\'
            '"' -> '"'
            '\'' -> '\''
            '\n' -> '\n'
            else -> escaped
        }
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
            '<' -> if (match('=')) TokenKind.LESS_EQUAL else TokenKind.LESS
            '>' -> if (match('=')) TokenKind.GREATER_EQUAL else TokenKind.GREATER
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
                advance()
                advance()
                if (peek() == '[' && peekNext() == '[') {
                    advance()
                    advance()
                    skipLongComment()
                } else {
                    while (!isAtEnd() && peek() != '\n') {
                        advance()
                    }
                }
                keepGoing = true
            }
        }
    }

    private fun skipLongComment() {
        while (!isAtEnd()) {
            if (peek() == ']' && peekNext() == ']') {
                advance()
                advance()
                return
            }
            advance()
        }
        throw errorAt(position(), "unterminated long comment")
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
