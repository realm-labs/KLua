package io.github.realmlabs.klua.core.lexer

import io.github.realmlabs.klua.core.value.toLuaByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
        val tokens = Lexer("global local true_value = true and false").tokenize()

        assertEquals(
            listOf(
                TokenKind.GLOBAL,
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
        assertEquals("true_value", tokens[2].literal)
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
    fun `tokenizes out of range decimal integers as floats`() {
        val tokens = Lexer("9223372036854775808").tokenize()

        assertEquals(listOf(TokenKind.FLOAT, TokenKind.EOF), tokens.map { it.kind })
        assertEquals(9223372036854775808.0, tokens[0].literal)
    }

    @Test
    fun `tokenizes leading dot float numbers`() {
        val tokens = Lexer(".5 .25e2").tokenize()

        assertEquals(
            listOf(
                TokenKind.FLOAT,
                TokenKind.FLOAT,
                TokenKind.EOF,
            ),
            tokens.map { it.kind },
        )
        assertEquals(0.5, tokens[0].literal)
        assertEquals(25.0, tokens[1].literal)
    }

    @Test
    fun `reports malformed numerals touching identifiers`() {
        val decimalError = assertFailsWith<LexerException> {
            Lexer("123abc", "decimal.lua").tokenize()
        }
        val hexError = assertFailsWith<LexerException> {
            Lexer("0x10_bad", "hex.lua").tokenize()
        }
        val leadingDotError = assertFailsWith<LexerException> {
            Lexer(".5abc", "float.lua").tokenize()
        }

        assertEquals("decimal.lua:1:1: malformed number", decimalError.message)
        assertEquals("hex.lua:1:1: malformed number", hexError.message)
        assertEquals("float.lua:1:1: malformed number", leadingDotError.message)
    }

    @Test
    fun `reports malformed lua numeral candidates`() {
        val cases = listOf(
            "1..2" to "concat-without-space.lua",
            "1.2.3" to "decimal-dots.lua",
            "1e+" to "decimal-exponent.lua",
            "0x" to "hex-empty.lua",
            "0x1p" to "hex-exponent.lua",
            ".5.6" to "leading-dot-dots.lua",
        )

        for ((source, name) in cases) {
            val error = assertFailsWith<LexerException>(source) {
                Lexer(source, name).tokenize()
            }
            assertEquals("$name:1:1: malformed number", error.message)
        }
    }

    @Test
    fun `tokenizes spaced number concatenation`() {
        val tokens = Lexer("1 .. 2").tokenize()

        assertEquals(
            listOf(
                TokenKind.INTEGER,
                TokenKind.DOT_DOT,
                TokenKind.INTEGER,
                TokenKind.EOF,
            ),
            tokens.map { it.kind },
        )
    }

    @Test
    fun `tokenizes hexadecimal integer and float numbers`() {
        val tokens = Lexer("0xff 0X10 0xffffffffffffffff 0x10000000000000000 0x1.8p1 0x0.1E").tokenize()

        assertEquals(
            listOf(
                TokenKind.INTEGER,
                TokenKind.INTEGER,
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
        assertEquals(-1L, tokens[2].literal)
        assertEquals(0L, tokens[3].literal)
        assertEquals(3.0, tokens[4].literal)
        assertEquals(0.1171875, tokens[5].literal)
    }

    @Test
    fun `tokenizes quoted strings and simple escapes`() {
        val tokens = Lexer(""" "line\none" 'tab\tvalue' "\000\007\255" """).tokenize()

        assertEquals(listOf(TokenKind.STRING, TokenKind.STRING, TokenKind.STRING, TokenKind.EOF), tokens.map { it.kind })
        assertEquals("line\none", tokens[0].literal)
        assertEquals("tab\tvalue", tokens[1].literal)
        assertEquals(byteArrayOf(0, 7, 255.toByte()).toLuaByteString(), tokens[2].literal)
    }

    @Test
    fun `tokenizes byte string escapes as lua bytes`() {
        val tokens = Lexer(""" "\255\x80\195\169é" """).tokenize()

        assertEquals(listOf(TokenKind.STRING, TokenKind.EOF), tokens.map { it.kind })
        assertEquals(byteArrayOf(255.toByte(), 128.toByte(), 195.toByte(), 169.toByte(), 195.toByte(), 169.toByte()).toLuaByteString(), tokens[0].literal)
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
    fun `tokenizes escaped lua newline sequences as line feeds`() {
        val tokens = Lexer("\"a\\\r\nb\" \"a\\\n\rb\"").tokenize()

        assertEquals(listOf(TokenKind.STRING, TokenKind.STRING, TokenKind.EOF), tokens.map { it.kind })
        assertEquals("a\nb", tokens[0].literal)
        assertEquals("a\nb", tokens[1].literal)
    }

    @Test
    fun `counts lua newline sequences once after whitespace string escape`() {
        val tokens = Lexer("\"a\\z \n\r b\" return", "zap.lua").tokenize()

        assertEquals(listOf(TokenKind.STRING, TokenKind.RETURN, TokenKind.EOF), tokens.map { it.kind })
        assertEquals("ab", tokens[0].literal)
        assertEquals(2, tokens[1].range.start.line)
    }

    @Test
    fun `stops whitespace string escape before non lua whitespace`() {
        val tokens = Lexer("\"a\\z \u2003b\"").tokenize()

        assertEquals(listOf(TokenKind.STRING, TokenKind.EOF), tokens.map { it.kind })
        assertEquals("a\u2003b", tokens[0].literal)
    }

    @Test
    fun `tokenizes unicode string escapes`() {
        val tokens = Lexer(
            "\"\\u{41}\\u{1F642}\"",
        ).tokenize()

        assertEquals(listOf(TokenKind.STRING, TokenKind.EOF), tokens.map { it.kind })
        assertEquals("A" + String(Character.toChars(0x1F642)), tokens[0].literal)
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
    fun `normalizes lua newline sequences in long bracket strings`() {
        val tokens = Lexer("[[\r\nbody]] [[\n\rbody]] [[first\r\nsecond\rthird\nfourth\n\rend]]").tokenize()

        assertEquals(listOf(TokenKind.STRING, TokenKind.STRING, TokenKind.STRING, TokenKind.EOF), tokens.map { it.kind })
        assertEquals("body", tokens[0].literal)
        assertEquals("body", tokens[1].literal)
        assertEquals("first\nsecond\nthird\nfourth\nend", tokens[2].literal)
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
    fun `reports malformed unicode escape errors`() {
        val error = assertFailsWith<LexerException> {
            Lexer("return \"\\u{}\"", "unicode-escape.lua").tokenize()
        }

        assertEquals("unicode-escape.lua:1:8: expected hexadecimal digits in unicode escape", error.message)
    }

    @Test
    fun `reports surrogate unicode escape errors`() {
        val error = assertFailsWith<LexerException> {
            Lexer("return \"\\u{D800}\"", "surrogate-escape.lua").tokenize()
        }

        assertEquals("surrogate-escape.lua:1:8: unicode escape out of range", error.message)
    }

    @Test
    fun `reports invalid string escape errors`() {
        val error = assertFailsWith<LexerException> {
            Lexer("return \"\\q\"", "invalid-escape.lua").tokenize()
        }

        assertEquals("invalid-escape.lua:1:8: invalid escape sequence", error.message)
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
    fun `ends line comments at lua newline sequences`() {
        val tokens = Lexer("-- carriage return\rreturn 1\n\rreturn 2", "comments.lua").tokenize()

        assertEquals(
            listOf(
                TokenKind.RETURN,
                TokenKind.INTEGER,
                TokenKind.RETURN,
                TokenKind.INTEGER,
                TokenKind.EOF,
            ),
            tokens.map { it.kind },
        )
        assertEquals(2, tokens[0].range.start.line)
        assertEquals(3, tokens[2].range.start.line)
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
    fun `rejects non lua whitespace between tokens`() {
        val error = assertFailsWith<LexerException> {
            Lexer("return\u20031", "unicode-space.lua").tokenize()
        }

        assertEquals("unicode-space.lua", error.position.sourceName)
        assertEquals(1, error.position.line)
        assertEquals(7, error.position.column)
        assertTrue(error.message.orEmpty().contains("unexpected character"))
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
