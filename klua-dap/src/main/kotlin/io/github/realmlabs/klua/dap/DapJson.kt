package io.github.realmlabs.klua.dap

public sealed interface DapJsonValue

public data object DapJsonNull : DapJsonValue

public data class DapJsonBoolean(
    public val value: Boolean,
) : DapJsonValue

public data class DapJsonNumber(
    public val value: Double,
) : DapJsonValue {
    init {
        require(value.isFinite()) { "JSON numbers must be finite" }
    }
}

public data class DapJsonString(
    public val value: String,
) : DapJsonValue

public data class DapJsonArray(
    public val values: List<DapJsonValue>,
) : DapJsonValue

public data class DapJsonObject(
    public val properties: Map<String, DapJsonValue>,
) : DapJsonValue

public object DapJson {
    public fun parse(text: String): DapJsonValue {
        return DapJsonParser(text).parse()
    }

    public fun stringify(value: DapJsonValue): String {
        val builder = StringBuilder()
        builder.appendJson(value)
        return builder.toString()
    }
}

private class DapJsonParser(
    private val text: String,
) {
    private var index = 0

    fun parse(): DapJsonValue {
        skipWhitespace()
        val value = parseValue()
        skipWhitespace()
        require(index == text.length) { "unexpected trailing JSON input at offset $index" }
        return value
    }

    private fun parseValue(): DapJsonValue {
        require(index < text.length) { "expected JSON value at end of input" }
        return when (val char = text[index]) {
            'n' -> parseLiteral("null", DapJsonNull)
            't' -> parseLiteral("true", DapJsonBoolean(true))
            'f' -> parseLiteral("false", DapJsonBoolean(false))
            '"' -> DapJsonString(parseString())
            '[' -> parseArray()
            '{' -> parseObject()
            '-', in '0'..'9' -> parseNumber()
            else -> throw IllegalArgumentException("unexpected JSON value '$char' at offset $index")
        }
    }

    private fun parseLiteral(literal: String, value: DapJsonValue): DapJsonValue {
        require(text.startsWith(literal, index)) { "expected JSON literal $literal at offset $index" }
        index += literal.length
        return value
    }

    private fun parseArray(): DapJsonArray {
        expect('[')
        skipWhitespace()
        if (consume(']')) return DapJsonArray(emptyList())

        val values = mutableListOf<DapJsonValue>()
        while (true) {
            skipWhitespace()
            values += parseValue()
            skipWhitespace()
            if (consume(']')) break
            expect(',')
        }
        return DapJsonArray(values)
    }

    private fun parseObject(): DapJsonObject {
        expect('{')
        skipWhitespace()
        if (consume('}')) return DapJsonObject(emptyMap())

        val properties = linkedMapOf<String, DapJsonValue>()
        while (true) {
            skipWhitespace()
            require(peek() == '"') { "expected JSON object key at offset $index" }
            val key = parseString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            properties[key] = parseValue()
            skipWhitespace()
            if (consume('}')) break
            expect(',')
        }
        return DapJsonObject(properties)
    }

    private fun parseString(): String {
        expect('"')
        val builder = StringBuilder()
        while (index < text.length) {
            when (val char = text[index++]) {
                '"' -> return builder.toString()
                '\\' -> builder.append(parseEscape())
                else -> {
                    require(char >= ' ') { "unescaped control character in JSON string at offset ${index - 1}" }
                    builder.append(char)
                }
            }
        }
        throw IllegalArgumentException("unterminated JSON string")
    }

    private fun parseEscape(): Char {
        require(index < text.length) { "unterminated JSON escape" }
        return when (val escaped = text[index++]) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> parseUnicodeEscape()
            else -> throw IllegalArgumentException("invalid JSON escape '$escaped' at offset ${index - 1}")
        }
    }

    private fun parseUnicodeEscape(): Char {
        require(index + 4 <= text.length) { "unterminated JSON unicode escape" }
        val value = text.substring(index, index + 4).toIntOrNull(16)
            ?: throw IllegalArgumentException("invalid JSON unicode escape at offset $index")
        index += 4
        return value.toChar()
    }

    private fun parseNumber(): DapJsonNumber {
        val start = index
        consume('-')
        parseIntegerPart()
        if (consume('.')) {
            require(peekIsDigit()) { "expected JSON fraction digit at offset $index" }
            while (peekIsDigit()) index++
        }
        if (peek() == 'e' || peek() == 'E') {
            index++
            if (peek() == '+' || peek() == '-') index++
            require(peekIsDigit()) { "expected JSON exponent digit at offset $index" }
            while (peekIsDigit()) index++
        }

        val number = text.substring(start, index).toDoubleOrNull()
            ?: throw IllegalArgumentException("invalid JSON number at offset $start")
        return DapJsonNumber(number)
    }

    private fun parseIntegerPart() {
        val char = peek()
        when {
            char == '0' -> index++
            char != null && char in '1'..'9' -> {
                index++
                while (peekIsDigit()) index++
            }
            else -> throw IllegalArgumentException("expected JSON digit at offset $index")
        }
    }

    private fun skipWhitespace() {
        while (peek() == ' ' || peek() == '\n' || peek() == '\r' || peek() == '\t') index++
    }

    private fun expect(char: Char) {
        require(consume(char)) { "expected '$char' at offset $index" }
    }

    private fun consume(char: Char): Boolean {
        if (peek() != char) return false
        index++
        return true
    }

    private fun peek(): Char? {
        return text.getOrNull(index)
    }

    private fun peekIsDigit(): Boolean {
        return peek()?.let { char -> char in '0'..'9' } == true
    }
}

private fun StringBuilder.appendJson(value: DapJsonValue) {
    when (value) {
        DapJsonNull -> append("null")
        is DapJsonBoolean -> append(value.value)
        is DapJsonNumber -> appendJsonNumber(value.value)
        is DapJsonString -> appendJsonString(value.value)
        is DapJsonArray -> {
            append('[')
            value.values.forEachIndexed { index, element ->
                if (index > 0) append(',')
                appendJson(element)
            }
            append(']')
        }
        is DapJsonObject -> {
            append('{')
            value.properties.entries.forEachIndexed { index, entry ->
                if (index > 0) append(',')
                appendJsonString(entry.key)
                append(':')
                appendJson(entry.value)
            }
            append('}')
        }
    }
}

private fun StringBuilder.appendJsonNumber(value: Double) {
    require(value.isFinite()) { "JSON numbers must be finite" }
    if (value % 1.0 == 0.0 && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE) {
        append(value.toLong())
    } else {
        append(value)
    }
}

private fun StringBuilder.appendJsonString(value: String) {
    append('"')
    value.forEach { char ->
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            in '\u0000'..'\u001F' -> append("\\u").append(char.code.toString(16).padStart(4, '0'))
            else -> append(char)
        }
    }
    append('"')
}
