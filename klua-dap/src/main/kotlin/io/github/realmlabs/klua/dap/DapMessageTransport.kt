package io.github.realmlabs.klua.dap

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

public object DapMessageTransport {
    public fun frame(json: String): ByteArray {
        val body = json.toByteArray(StandardCharsets.UTF_8)
        val header = "Content-Length: ${body.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
        return header + body
    }
}

public class DapMessageStream {
    private val buffer = ByteArrayOutputStream()

    public fun feed(bytes: ByteArray): List<String> {
        if (bytes.isEmpty()) return emptyList()
        buffer.write(bytes)

        val messages = mutableListOf<String>()
        while (true) {
            val current = buffer.toByteArray()
            val headerEnd = current.headerEndIndex()
            if (headerEnd < 0) break

            val contentLength = current.contentLength(headerEnd)
            val bodyStart = headerEnd + HeaderDelimiter.size
            if (current.size - bodyStart < contentLength) break

            messages += String(current, bodyStart, contentLength, StandardCharsets.UTF_8)
            buffer.reset()
            buffer.write(current, bodyStart + contentLength, current.size - bodyStart - contentLength)
        }
        return messages
    }
}

private val HeaderDelimiter = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())

private fun ByteArray.headerEndIndex(): Int {
    for (index in 0..size - HeaderDelimiter.size) {
        if (
            this[index] == HeaderDelimiter[0] &&
            this[index + 1] == HeaderDelimiter[1] &&
            this[index + 2] == HeaderDelimiter[2] &&
            this[index + 3] == HeaderDelimiter[3]
        ) {
            return index
        }
    }
    return -1
}

private fun ByteArray.contentLength(headerEnd: Int): Int {
    val headers = String(this, 0, headerEnd, StandardCharsets.US_ASCII)
    val value = headers.lineSequence()
        .firstOrNull { line -> line.substringBefore(':').trim().equals("Content-Length", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()
        ?.toIntOrNull()

    require(value != null && value >= 0) { "missing or invalid Content-Length header" }
    return value
}
