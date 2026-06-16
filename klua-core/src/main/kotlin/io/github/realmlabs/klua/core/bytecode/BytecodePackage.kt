package io.github.realmlabs.klua.core.bytecode

internal const val KLUA_BYTECODE_MAGIC: String = "KLua"
internal const val KLUA_BYTECODE_FORMAT_VERSION: Int = 1
internal const val KLUA_BYTECODE_SOURCE_LANGUAGE: String = "Lua55"
internal const val KLUA_BYTECODE_FLAG_DEBUG_INFO: Int = 1
private const val KLUA_BYTECODE_SUPPORTED_FLAGS: Int = KLUA_BYTECODE_FLAG_DEBUG_INFO

internal data class BytecodePackageHeader(
    val magic: String = KLUA_BYTECODE_MAGIC,
    val formatVersion: Int = KLUA_BYTECODE_FORMAT_VERSION,
    val sourceLanguage: String = KLUA_BYTECODE_SOURCE_LANGUAGE,
    val flags: Int = KLUA_BYTECODE_FLAG_DEBUG_INFO,
)

internal sealed interface BytecodePackageValidation {
    data object Valid : BytecodePackageValidation

    data class Invalid(val reason: String) : BytecodePackageValidation
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

        return BytecodePackageValidation.Valid
    }
}
