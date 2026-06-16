package io.github.realmlabs.klua.core.bytecode

import java.util.zip.CRC32

internal const val KLUA_BYTECODE_MAGIC: String = "KLua"
internal const val KLUA_BYTECODE_FORMAT_VERSION: Int = 1
internal const val KLUA_BYTECODE_SOURCE_LANGUAGE: String = "Lua55"
internal const val KLUA_BYTECODE_FLAG_DEBUG_INFO: Int = 1
private const val KLUA_BYTECODE_SUPPORTED_FLAGS: Int = KLUA_BYTECODE_FLAG_DEBUG_INFO
private const val BYTECODE_HEADER_MAGIC_SIZE: Int = 4
private const val BYTECODE_HEADER_INT_SIZE: Int = 4
private const val UINT32_MAX: Long = 0xffff_ffffL

internal data class BytecodePackageHeader(
    val magic: String = KLUA_BYTECODE_MAGIC,
    val formatVersion: Int = KLUA_BYTECODE_FORMAT_VERSION,
    val sourceLanguage: String = KLUA_BYTECODE_SOURCE_LANGUAGE,
    val flags: Int = KLUA_BYTECODE_FLAG_DEBUG_INFO,
    val payloadSize: Int = 0,
    val payloadChecksum: Long = 0,
) {
    companion object {
        fun forPayload(
            payload: ByteArray,
            flags: Int = KLUA_BYTECODE_FLAG_DEBUG_INFO,
        ): BytecodePackageHeader {
            return BytecodePackageHeader(
                flags = flags,
                payloadSize = payload.size,
                payloadChecksum = crc32(payload),
            )
        }
    }
}

internal sealed interface BytecodePackageValidation {
    data object Valid : BytecodePackageValidation

    data class Invalid(val reason: String) : BytecodePackageValidation
}

internal sealed interface BytecodePackageHeaderDecode {
    data class Decoded(
        val header: BytecodePackageHeader,
        val nextOffset: Int,
    ) : BytecodePackageHeaderDecode

    data class Invalid(val reason: String) : BytecodePackageHeaderDecode
}

internal object BytecodePackageValidator {
    fun validate(header: BytecodePackageHeader): BytecodePackageValidation {
        if (header.magic != KLUA_BYTECODE_MAGIC) {
            return BytecodePackageValidation.Invalid("unsupported KLua bytecode magic '${header.magic}'")
        }

        if (header.formatVersion != KLUA_BYTECODE_FORMAT_VERSION) {
            return BytecodePackageValidation.Invalid(
                "unsupported KLua bytecode format version ${header.formatVersion}",
            )
        }

        if (header.sourceLanguage != KLUA_BYTECODE_SOURCE_LANGUAGE) {
            return BytecodePackageValidation.Invalid(
                "unsupported KLua source language marker '${header.sourceLanguage}'",
            )
        }

        val unsupportedFlags = header.flags and KLUA_BYTECODE_SUPPORTED_FLAGS.inv()
        if (unsupportedFlags != 0) {
            return BytecodePackageValidation.Invalid(
                "unsupported KLua bytecode flags 0x${unsupportedFlags.toString(16)}",
            )
        }

        if (header.payloadSize < 0) {
            return BytecodePackageValidation.Invalid("invalid KLua bytecode payload size ${header.payloadSize}")
        }

        if (header.payloadChecksum < 0 || header.payloadChecksum > UINT32_MAX) {
            return BytecodePackageValidation.Invalid(
                "invalid KLua bytecode payload checksum 0x${header.payloadChecksum.toString(16)}",
            )
        }

        return BytecodePackageValidation.Valid
    }
}

internal object BytecodePackagePayloadValidator {
    fun validate(
        header: BytecodePackageHeader,
        bytes: ByteArray,
        offset: Int,
    ): BytecodePackageValidation {
        when (val validation = BytecodePackageValidator.validate(header)) {
            BytecodePackageValidation.Valid -> Unit
            is BytecodePackageValidation.Invalid -> return validation
        }

        if (offset < 0 || offset > bytes.size || header.payloadSize > bytes.size - offset) {
            return BytecodePackageValidation.Invalid("truncated KLua bytecode payload")
        }

        val actualChecksum = crc32(bytes, offset, header.payloadSize)
        if (actualChecksum != header.payloadChecksum) {
            return BytecodePackageValidation.Invalid(
                "KLua bytecode payload checksum mismatch: expected 0x${header.payloadChecksum.toString(16)}, got 0x${actualChecksum.toString(16)}",
            )
        }

        return BytecodePackageValidation.Valid
    }
}

internal object BytecodePackageHeaderCodec {
    fun encode(header: BytecodePackageHeader = BytecodePackageHeader()): ByteArray {
        when (val validation = BytecodePackageValidator.validate(header)) {
            BytecodePackageValidation.Valid -> Unit
            is BytecodePackageValidation.Invalid -> throw IllegalArgumentException(validation.reason)
        }

        val magicBytes = header.magic.encodeToByteArray()
        val sourceLanguageBytes = header.sourceLanguage.encodeToByteArray()
        val output = ByteArray(
            BYTECODE_HEADER_MAGIC_SIZE +
                BYTECODE_HEADER_INT_SIZE +
                BYTECODE_HEADER_INT_SIZE +
                sourceLanguageBytes.size +
                BYTECODE_HEADER_INT_SIZE +
                BYTECODE_HEADER_INT_SIZE +
                BYTECODE_HEADER_INT_SIZE,
        )
        var position = 0
        magicBytes.copyInto(output, destinationOffset = position)
        position += BYTECODE_HEADER_MAGIC_SIZE
        position = writeInt(output, position, header.formatVersion)
        position = writeInt(output, position, sourceLanguageBytes.size)
        sourceLanguageBytes.copyInto(output, destinationOffset = position)
        position += sourceLanguageBytes.size
        position = writeInt(output, position, header.flags)
        position = writeInt(output, position, header.payloadSize)
        writeInt(output, position, header.payloadChecksum.toInt())

        return output
    }

    fun decode(bytes: ByteArray, offset: Int = 0): BytecodePackageHeaderDecode {
        if (offset < 0 || offset > bytes.size) {
            return BytecodePackageHeaderDecode.Invalid("invalid KLua bytecode header offset $offset")
        }

        var position = offset
        if (!hasBytes(bytes, position, BYTECODE_HEADER_MAGIC_SIZE)) {
            return BytecodePackageHeaderDecode.Invalid("truncated KLua bytecode header")
        }
        val magic = bytes.decodeToString(position, position + BYTECODE_HEADER_MAGIC_SIZE)
        position += BYTECODE_HEADER_MAGIC_SIZE

        if (!hasBytes(bytes, position, BYTECODE_HEADER_INT_SIZE)) {
            return BytecodePackageHeaderDecode.Invalid("truncated KLua bytecode header")
        }
        val formatVersion = readInt(bytes, position)
        position += BYTECODE_HEADER_INT_SIZE

        if (!hasBytes(bytes, position, BYTECODE_HEADER_INT_SIZE)) {
            return BytecodePackageHeaderDecode.Invalid("truncated KLua bytecode header")
        }
        val sourceLanguageLength = readInt(bytes, position)
        position += BYTECODE_HEADER_INT_SIZE
        if (sourceLanguageLength < 0) {
            return BytecodePackageHeaderDecode.Invalid(
                "invalid KLua source language marker length $sourceLanguageLength",
            )
        }

        if (!hasBytes(bytes, position, sourceLanguageLength)) {
            return BytecodePackageHeaderDecode.Invalid("truncated KLua bytecode header")
        }
        val sourceLanguage = bytes.decodeToString(position, position + sourceLanguageLength)
        position += sourceLanguageLength

        if (!hasBytes(bytes, position, BYTECODE_HEADER_INT_SIZE)) {
            return BytecodePackageHeaderDecode.Invalid("truncated KLua bytecode header")
        }
        val flags = readInt(bytes, position)
        position += BYTECODE_HEADER_INT_SIZE

        if (!hasBytes(bytes, position, BYTECODE_HEADER_INT_SIZE)) {
            return BytecodePackageHeaderDecode.Invalid("truncated KLua bytecode header")
        }
        val payloadSize = readInt(bytes, position)
        position += BYTECODE_HEADER_INT_SIZE

        if (!hasBytes(bytes, position, BYTECODE_HEADER_INT_SIZE)) {
            return BytecodePackageHeaderDecode.Invalid("truncated KLua bytecode header")
        }
        val payloadChecksum = readUInt(bytes, position)
        position += BYTECODE_HEADER_INT_SIZE

        val header = BytecodePackageHeader(
            magic = magic,
            formatVersion = formatVersion,
            sourceLanguage = sourceLanguage,
            flags = flags,
            payloadSize = payloadSize,
            payloadChecksum = payloadChecksum,
        )
        return when (val validation = BytecodePackageValidator.validate(header)) {
            BytecodePackageValidation.Valid -> BytecodePackageHeaderDecode.Decoded(header, position)
            is BytecodePackageValidation.Invalid -> BytecodePackageHeaderDecode.Invalid(validation.reason)
        }
    }

    private fun hasBytes(bytes: ByteArray, offset: Int, byteCount: Int): Boolean {
        return byteCount >= 0 && offset <= bytes.size && byteCount <= bytes.size - offset
    }

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int): Int {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
        return offset + BYTECODE_HEADER_INT_SIZE
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
    }

    private fun readUInt(bytes: ByteArray, offset: Int): Long {
        return readInt(bytes, offset).toLong() and UINT32_MAX
    }
}

private fun crc32(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Long {
    val checksum = CRC32()
    checksum.update(bytes, offset, length)
    return checksum.value
}
