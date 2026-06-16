package io.github.realmlabs.klua.core.bytecode

import kotlin.test.Test
import kotlin.test.assertEquals

class BytecodePackageTest {
    @Test
    fun `accepts current bytecode package header`() {
        assertEquals(
            BytecodePackageValidation.Valid,
            BytecodePackageValidator.validate(BytecodePackageHeader()),
        )
    }

    @Test
    fun `accepts bytecode package header without debug info flag`() {
        assertEquals(
            BytecodePackageValidation.Valid,
            BytecodePackageValidator.validate(BytecodePackageHeader(flags = 0)),
        )
    }

    @Test
    fun `rejects unsupported bytecode magic`() {
        assertEquals(
            BytecodePackageValidation.Invalid("unsupported KLua bytecode magic 'Lua'"),
            BytecodePackageValidator.validate(BytecodePackageHeader(magic = "Lua")),
        )
    }

    @Test
    fun `rejects unsupported bytecode format versions`() {
        assertEquals(
            BytecodePackageValidation.Invalid("unsupported KLua bytecode format version 2"),
            BytecodePackageValidator.validate(BytecodePackageHeader(formatVersion = 2)),
        )
    }

    @Test
    fun `rejects unsupported source language markers`() {
        assertEquals(
            BytecodePackageValidation.Invalid("unsupported KLua source language marker 'Lua54'"),
            BytecodePackageValidator.validate(BytecodePackageHeader(sourceLanguage = "Lua54")),
        )
    }

    @Test
    fun `rejects unsupported bytecode flags`() {
        assertEquals(
            BytecodePackageValidation.Invalid("unsupported KLua bytecode flags 0x2"),
            BytecodePackageValidator.validate(BytecodePackageHeader(flags = 2)),
        )
    }
}
