package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.bytecode.Instruction
import io.github.realmlabs.klua.core.bytecode.Opcode
import io.github.realmlabs.klua.core.bytecode.Prototype
import io.github.realmlabs.klua.core.compiler.Compiler
import io.github.realmlabs.klua.core.runtime.LuaSourceVersion
import io.github.realmlabs.klua.core.value.LuaBoolean
import io.github.realmlabs.klua.core.value.LuaFloat
import io.github.realmlabs.klua.core.value.LuaInteger
import io.github.realmlabs.klua.core.value.LuaNil
import io.github.realmlabs.klua.core.value.LuaString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LuaVmTest {
    @Test
    fun `executes empty chunk`() {
        val result = LuaVm().execute(Compiler.compile(""))

        assertEquals(emptyList(), result)
    }

    @Test
    fun `executes literal return chunk`() {
        val result = LuaVm().execute(Compiler.compile("""return nil, true, false, 42, -5, 1024, "ok", 2.5"""))

        assertEquals(
            listOf(
                LuaNil,
                LuaBoolean(true),
                LuaBoolean(false),
                LuaInteger(42),
                LuaInteger(-5),
                LuaInteger(1024),
                LuaString("ok"),
                LuaFloat(2.5),
            ),
            result,
        )
    }

    @Test
    fun `executes move opcode`() {
        val prototype = Prototype(
            sourceName = "move.lua",
            version = LuaSourceVersion.LUA_54,
            code = intArrayOf(
                Instruction.abc(Opcode.LOAD_K, 0, 0),
                Instruction.abc(Opcode.MOVE, 1, 0),
                Instruction.abc(Opcode.RETURN, 1, 1),
            ),
            constants = arrayOf(LuaString("copied")),
            lineInfo = intArrayOf(1, 1, 1),
            maxStackSize = 2,
        )

        assertEquals(listOf(LuaString("copied")), LuaVm().execute(prototype))
    }

    @Test
    fun `rejects missing return`() {
        val prototype = Prototype(
            sourceName = "broken.lua",
            version = LuaSourceVersion.LUA_54,
            code = intArrayOf(Instruction.abc(Opcode.LOAD_NIL, 0)),
            constants = emptyArray(),
            lineInfo = intArrayOf(1),
            maxStackSize = 1,
        )

        val error = assertFailsWith<LuaVmException> {
            LuaVm().execute(prototype)
        }

        assertEquals("prototype completed without RETURN", error.message)
    }

    @Test
    fun `rejects invalid constant index`() {
        val prototype = Prototype(
            sourceName = "bad-constant.lua",
            version = LuaSourceVersion.LUA_54,
            code = intArrayOf(Instruction.abc(Opcode.LOAD_K, 0, 0)),
            constants = emptyArray(),
            lineInfo = intArrayOf(1),
            maxStackSize = 1,
        )

        val error = assertFailsWith<LuaVmException> {
            LuaVm().execute(prototype)
        }

        assertEquals("constant index out of range: K0", error.message)
    }
}
