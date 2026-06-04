package io.github.realmlabs.klua.core.lexer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LexerTest {
    @Test
    fun `tokenizes simple return expression`() {
        val tokens = Lexer("return 1 + 2 * value", "simple.lua").tokenize()

        assertEquals(
            listOf(
                TokenKind.RETURN,
                TokenKind.INTEGER,
                TokenKind.PLUS,
                TokenKind.INTEGER,
                TokenKind.STAR,
                TokenKind.IDENTIFIER,
                TokenKind.EOF,
            ),
            tokens.map { it.kind },
        )
        assertEquals(1L, tokens[1].literal)
        assertEquals("value", tokens[5].literal)
    }

    @Test
    fun `recognizes lua keywords separately from identifiers`() {
        val tokens = Lexer("local true_value = true and false").tokenize()

        assertEquals(
            listOf(
                TokenKind.LOCAL,
                TokenKind.IDENTIFIER,
                TokenKind.ASSIGN,
                TokenKind.TRUE,
                TokenKind.AND,
                TokenKind.FALSE,
                TokenKind.EOF,
            ),
            tokens.map { it.kind },
        )
        assertEquals("true_value", tokens[1].literal)
    }

    @Test
    fun `tokenizes integer float and exponent numbers`() {
        val tokens = Lexer("1 2.5 3e4 4.5e-6").tokenize()

        assertEquals(
            listOf(
                TokenKind.INTEGER,
                TokenKind.FLOAT,
                TokenKind.FLOAT,
                TokenKind.FLOAT,
                TokenKind.EOF,
            ),
            tokens.map { it.kind },
        )
        assertEquals(1L, tokens[0].literal)
        assertEquals(2.5, tokens[1].literal)
        assertEquals(30000.0, tokens[2].literal)
        assertEquals(4.5e-6, tokens[3].literal)
    }

    @Test
    fun `tokenizes hexadecimal integer and float numbers`() {
        val tokens = Lexer("0xff 0X10 0x1.8p1 0x0.1E").tokenize()

        assertEquals(
            listOf(
                TokenKind.INTEGER,
                TokenKind.INTEGER,
                TokenKind.FLOAT,
                TokenKind.FLOAT,
                TokenKind.EOF,
            ),
            tokens.map { it.kind },
        )
        assertEquals(255L, tokens[0].literal)
        assertEquals(16L, tokens[1].literal)
        assertEquals(3.0, tokens[2].literal)
        assertEquals(0.1171875, tokens[3].literal)
    }

    @Test
    fun `tokenizes quoted strings and simple escapes`() {
        val tokens = Lexer(""" "line\none" 'tab\tvalue' "\000\007\255" """).tokenize()

        assertEquals(listOf(TokenKind.STRING, TokenKind.STRING, TokenKind.STRING, TokenKind.EOF), tokens.map { it.kind })
        assertEquals("line\none", tokens[0].literal)
        assertEquals("tab\tvalue", tokens[1].literal)
        assertEquals("\u0000\u0007\u00FF", tokens[2].literal)
    }

    @Test
    fun `tokenizes hexadecimal and whitespace string escapes`() {
        val tokens = Lexer(
            "\"\\x41\\z \n\t B\"",
        ).tokenize()

        assertEquals(listOf(TokenKind.STRING, TokenKind.EOF), tokens.map { it.kind })
        assertEquals("AB", tokens[0].literal)
    }

    @Test
    fun `tokenizes long bracket strings`() {
        val tokens = Lexer(
            """
            [[
            first line]]
            [=[keeps [brackets] and ]] text]=]
            """.trimIndent(),
        ).tokenize()

        assertEquals(listOf(TokenKind.STRING, TokenKind.STRING, TokenKind.EOF), tokens.map { it.kind })
        assertEquals("first line", tokens[0].literal)
        assertEquals("keeps [brackets] and ]] text", tokens[1].literal)
    }

    @Test
    fun `reports decimal escape range errors`() {
        val error = assertFailsWith<LexerException> {
            Lexer("return \"\\256\"", "escape.lua").tokenize()
        }

        assertEquals("escape.lua:1:8: escape sequence out of range", error.message)
    }

    @Test
    fun `reports malformed hexadecimal escape errors`() {
        val error = assertFailsWith<LexerException> {
            Lexer("return \"\\x4\"", "hex-escape.lua").tokenize()
        }

        assertEquals("hex-escape.lua:1:8: expected two hexadecimal digits in escape sequence", error.message)
    }

    @Test
    fun `skips line and long comments`() {
        val tokens = Lexer(
            """
            -- line comment
            local x = 1 -- trailing
            --[[ long
            comment ]]
            --[=[ long equal
            comment ]=]
            return x
            """.trimIndent(),
        ).tokenize()

        assertEquals(
            listOf(
                TokenKind.LOCAL,
                TokenKind.IDENTIFIER,
                TokenKind.ASSIGN,
                TokenKind.INTEGER,
                TokenKind.RETURN,
                TokenKind.IDENTIFIER,
                TokenKind.EOF,
            ),
            tokens.map { it.kind },
        )
    }

    @Test
    fun `tokenizes lua operators and punctuation`() {
        val tokens = Lexer("+ - * / // % ^ # & << >> | ~ ~= == < <= > >= . .. ... , ; : :: ( ) { } [ ]").tokenize()

        assertEquals(
            listOf(
                TokenKind.PLUS,
                TokenKind.MINUS,
                TokenKind.STAR,
                TokenKind.SLASH,
                TokenKind.DOUBLE_SLASH,
                TokenKind.PERCENT,
                TokenKind.CARET,
                TokenKind.HASH,
                TokenKind.AMPERSAND,
                TokenKind.LEFT_SHIFT,
                TokenKind.RIGHT_SHIFT,
                TokenKind.PIPE,
                TokenKind.TILDE,
                TokenKind.NOT_EQUAL,
                TokenKind.EQUAL_EQUAL,
                TokenKind.LESS,
                TokenKind.LESS_EQUAL,
                TokenKind.GREATER,
                TokenKind.GREATER_EQUAL,
                TokenKind.DOT,
                TokenKind.DOT_DOT,
                TokenKind.DOT_DOT_DOT,
                TokenKind.COMMA,
                TokenKind.SEMICOLON,
                TokenKind.COLON,
                TokenKind.DOUBLE_COLON,
                TokenKind.LEFT_PAREN,
                TokenKind.RIGHT_PAREN,
                TokenKind.LEFT_BRACE,
                TokenKind.RIGHT_BRACE,
                TokenKind.LEFT_BRACKET,
                TokenKind.RIGHT_BRACKET,
                TokenKind.EOF,
            ),
            tokens.map { it.kind },
        )
    }

    @Test
    fun `tracks source line and column`() {
        val tokens = Lexer("local x\nreturn x", "positions.lua").tokenize()
        val returnToken = tokens.first { it.kind == TokenKind.RETURN }

        assertEquals("positions.lua", returnToken.range.start.sourceName)
        assertEquals(2, returnToken.range.start.line)
        assertEquals(1, returnToken.range.start.column)
    }

    @Test
    fun `reports unterminated string with source position`() {
        val error = assertFailsWith<LexerException> {
            Lexer("return 'oops", "broken.lua").tokenize()
        }

        assertEquals("broken.lua", error.position.sourceName)
        assertEquals(1, error.position.line)
        assertEquals(8, error.position.column)
    }
}
