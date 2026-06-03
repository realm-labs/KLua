package io.github.realmlabs.klua.core.compiler

import io.github.realmlabs.klua.core.bytecode.Disassembler
import io.github.realmlabs.klua.core.runtime.LuaSourceVersion
import io.github.realmlabs.klua.core.value.LuaFloat
import io.github.realmlabs.klua.core.value.LuaInteger
import io.github.realmlabs.klua.core.value.LuaString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CompilerTest {
    @Test
    fun `compiles integer return with inline load`() {
        val prototype = Compiler.compile("return 42", "return-int.lua")

        assertEquals("return-int.lua", prototype.sourceName)
        assertEquals(LuaSourceVersion.LUA_54, prototype.version)
        assertEquals(1, prototype.maxStackSize)
        assertEquals(0, prototype.constants.size)
        assertContentEquals(intArrayOf(1, 1), prototype.lineInfo)
        assertEquals(
            """
            0000  [1]  LOAD_INT R0 42
            0001  [1]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles multiple literal return values`() {
        val prototype = Compiler.compile("""return nil, true, false, "ok", 2.5""")

        assertEquals(2, prototype.constants.size)
        assertEquals("ok", assertIs<LuaString>(prototype.constants[0]).value)
        assertEquals(2.5, assertIs<LuaFloat>(prototype.constants[1]).value)
        assertEquals(5, prototype.maxStackSize)
        assertEquals(
            """
            0000  [1]  LOAD_NIL R0
            0001  [1]  LOAD_BOOL R1 true
            0002  [1]  LOAD_BOOL R2 false
            0003  [1]  LOAD_K R3 K0 ; "ok"
            0004  [1]  LOAD_FLOAT R4 2.5
            0005  [1]  RETURN R0 5
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `stores large integers in constant pool`() {
        val prototype = Compiler.compile("return 1024")

        assertEquals(1, prototype.constants.size)
        assertEquals(1024L, assertIs<LuaInteger>(prototype.constants.single()).value)
        assertEquals(
            """
            0000  [1]  LOAD_K R0 K0 ; 1024
            0001  [1]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles negative numeric literals`() {
        val prototype = Compiler.compile("return -5, -1.5")

        assertEquals(1, prototype.constants.size)
        assertEquals(-1.5, assertIs<LuaFloat>(prototype.constants.single()).value)
        assertEquals(
            """
            0000  [1]  LOAD_INT R0 -5
            0001  [1]  LOAD_FLOAT R1 -1.5
            0002  [1]  RETURN R0 2
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `deduplicates equal constants`() {
        val prototype = Compiler.compile("""return "same", "same", 1000, 1000""")

        assertEquals(2, prototype.constants.size)
        assertEquals(
            """
            0000  [1]  LOAD_K R0 K0 ; "same"
            0001  [1]  LOAD_K R1 K0 ; "same"
            0002  [1]  LOAD_K R2 K1 ; 1000
            0003  [1]  LOAD_K R3 K1 ; 1000
            0004  [1]  RETURN R0 4
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles arithmetic expression tree`() {
        val prototype = Compiler.compile("return 1 + 2 * 3 ^ 2")

        assertEquals(4, prototype.maxStackSize)
        assertEquals(
            """
            0000  [1]  LOAD_INT R0 1
            0001  [1]  LOAD_INT R1 2
            0002  [1]  LOAD_INT R2 3
            0003  [1]  LOAD_INT R3 2
            0004  [1]  POW R2 R2 R3
            0005  [1]  MUL R1 R1 R2
            0006  [1]  ADD R0 R0 R1
            0007  [1]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles comparison and not expressions`() {
        val prototype = Compiler.compile("""return 1 == 1.0, 2 ~= 3, "b" > "a", not nil""")

        assertEquals(4, prototype.maxStackSize)
        assertEquals(
            """
            0000  [1]  LOAD_INT R0 1
            0001  [1]  LOAD_FLOAT R1 1.0
            0002  [1]  EQ R0 R0 R1
            0003  [1]  LOAD_INT R1 2
            0004  [1]  LOAD_INT R2 3
            0005  [1]  EQ R1 R1 R2
            0006  [1]  NOT R1 R1
            0007  [1]  LOAD_K R2 K1 ; "b"
            0008  [1]  LOAD_K R3 K2 ; "a"
            0009  [1]  LT R2 R3 R2
            0010  [1]  LOAD_NIL R3
            0011  [1]  NOT R3 R3
            0012  [1]  RETURN R0 4
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles local declaration and local return`() {
        val prototype = Compiler.compile(
            """
            local x = 1 + 2
            return x
            """.trimIndent(),
        )

        assertEquals(2, prototype.maxStackSize)
        assertEquals(
            """
            0000  [1]  LOAD_INT R0 1
            0001  [1]  LOAD_INT R1 2
            0002  [1]  ADD R0 R0 R1
            0003  [2]  MOVE R1 R0
            0004  [2]  MOVE R0 R1
            0005  [2]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `stages local return values before moving into return registers`() {
        val prototype = Compiler.compile(
            """
            local x, y = 1, 2
            return y, x
            """.trimIndent(),
        )

        assertEquals(4, prototype.maxStackSize)
        assertEquals(
            """
            0000  [1]  LOAD_INT R0 1
            0001  [1]  LOAD_INT R1 2
            0002  [2]  MOVE R2 R1
            0003  [2]  MOVE R3 R0
            0004  [2]  MOVE R0 R2
            0005  [2]  MOVE R1 R3
            0006  [2]  RETURN R0 2
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `initializes missing local values to nil`() {
        val prototype = Compiler.compile(
            """
            local x, y = 1
            return y
            """.trimIndent(),
        )

        assertEquals(
            """
            0000  [1]  LOAD_INT R0 1
            0001  [1]  LOAD_NIL R1
            0002  [2]  MOVE R2 R1
            0003  [2]  MOVE R0 R2
            0004  [2]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles local reassignment`() {
        val prototype = Compiler.compile(
            """
            local x = 1
            x = x + 2
            return x
            """.trimIndent(),
        )

        assertEquals(
            """
            0000  [1]  LOAD_INT R0 1
            0001  [2]  MOVE R1 R0
            0002  [2]  LOAD_INT R2 2
            0003  [2]  ADD R1 R1 R2
            0004  [2]  MOVE R0 R1
            0005  [3]  MOVE R1 R0
            0006  [3]  MOVE R0 R1
            0007  [3]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `stages multi assignment before writing targets`() {
        val prototype = Compiler.compile(
            """
            local x, y = 1, 2
            x, y = y, x
            return x, y
            """.trimIndent(),
        )

        assertEquals(
            """
            0000  [1]  LOAD_INT R0 1
            0001  [1]  LOAD_INT R1 2
            0002  [2]  MOVE R2 R1
            0003  [2]  MOVE R3 R0
            0004  [2]  MOVE R0 R2
            0005  [2]  MOVE R1 R3
            0006  [3]  MOVE R2 R0
            0007  [3]  MOVE R3 R1
            0008  [3]  MOVE R0 R2
            0009  [3]  MOVE R1 R3
            0010  [3]  RETURN R0 2
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles assignment missing values as nil`() {
        val prototype = Compiler.compile(
            """
            local x, y = 1, 2
            x, y = 3
            return y
            """.trimIndent(),
        )

        assertEquals(
            """
            0000  [1]  LOAD_INT R0 1
            0001  [1]  LOAD_INT R1 2
            0002  [2]  LOAD_INT R2 3
            0003  [2]  LOAD_NIL R3
            0004  [2]  MOVE R0 R2
            0005  [2]  MOVE R1 R3
            0006  [3]  MOVE R2 R1
            0007  [3]  MOVE R0 R2
            0008  [3]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles if else assignment`() {
        val prototype = Compiler.compile(
            """
            local x = 0
            if true then
                x = 1
            else
                x = 2
            end
            return x
            """.trimIndent(),
        )

        assertEquals(
            """
            0000  [1]  LOAD_INT R0 0
            0001  [2]  LOAD_BOOL R1 true
            0002  [2]  TEST R1 3
            0003  [3]  LOAD_INT R1 1
            0004  [3]  MOVE R0 R1
            0005  [2]  JMP 2
            0006  [5]  LOAD_INT R1 2
            0007  [5]  MOVE R0 R1
            0008  [7]  MOVE R1 R0
            0009  [7]  MOVE R0 R1
            0010  [7]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles elseif chain`() {
        val prototype = Compiler.compile(
            """
            local x = 0
            if false then
                x = 1
            elseif true then
                x = 2
            else
                x = 3
            end
            return x
            """.trimIndent(),
        )

        assertEquals(
            """
            0000  [1]  LOAD_INT R0 0
            0001  [2]  LOAD_BOOL R1 false
            0002  [2]  TEST R1 3
            0003  [3]  LOAD_INT R1 1
            0004  [3]  MOVE R0 R1
            0005  [2]  JMP 7
            0006  [4]  LOAD_BOOL R1 true
            0007  [4]  TEST R1 3
            0008  [5]  LOAD_INT R1 2
            0009  [5]  MOVE R0 R1
            0010  [4]  JMP 2
            0011  [7]  LOAD_INT R1 3
            0012  [7]  MOVE R0 R1
            0013  [9]  MOVE R1 R0
            0014  [9]  MOVE R0 R1
            0015  [9]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles while loop`() {
        val prototype = Compiler.compile(
            """
            local x = 0
            while x < 3 do
                x = x + 1
            end
            return x
            """.trimIndent(),
        )

        assertEquals(
            """
            0000  [1]  LOAD_INT R0 0
            0001  [2]  MOVE R1 R0
            0002  [2]  LOAD_INT R2 3
            0003  [2]  LT R1 R1 R2
            0004  [2]  TEST R1 5
            0005  [3]  MOVE R1 R0
            0006  [3]  LOAD_INT R2 1
            0007  [3]  ADD R1 R1 R2
            0008  [3]  MOVE R0 R1
            0009  [2]  JMP -9
            0010  [5]  MOVE R1 R0
            0011  [5]  MOVE R0 R1
            0012  [5]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles repeat until loop`() {
        val prototype = Compiler.compile(
            """
            local x = 0
            repeat
                x = x + 1
            until x >= 3
            return x
            """.trimIndent(),
        )

        assertEquals(
            """
            0000  [1]  LOAD_INT R0 0
            0001  [3]  MOVE R1 R0
            0002  [3]  LOAD_INT R2 1
            0003  [3]  ADD R1 R1 R2
            0004  [3]  MOVE R0 R1
            0005  [4]  MOVE R1 R0
            0006  [4]  LOAD_INT R2 3
            0007  [4]  LE R1 R2 R1
            0008  [4]  TEST R1 -8
            0009  [5]  MOVE R1 R0
            0010  [5]  MOVE R0 R1
            0011  [5]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles break to nearest loop exit`() {
        val prototype = Compiler.compile(
            """
            local x = 0
            while true do
                x = x + 1
                if x == 2 then
                    break
                end
            end
            return x
            """.trimIndent(),
        )

        assertEquals(
            """
            0000  [1]  LOAD_INT R0 0
            0001  [2]  LOAD_BOOL R1 true
            0002  [2]  TEST R1 11
            0003  [3]  MOVE R1 R0
            0004  [3]  LOAD_INT R2 1
            0005  [3]  ADD R1 R1 R2
            0006  [3]  MOVE R0 R1
            0007  [4]  MOVE R1 R0
            0008  [4]  LOAD_INT R2 2
            0009  [4]  EQ R1 R1 R2
            0010  [4]  TEST R1 2
            0011  [5]  JMP 2
            0012  [4]  JMP 0
            0013  [2]  JMP -13
            0014  [8]  MOVE R1 R0
            0015  [8]  MOVE R0 R1
            0016  [8]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `emits empty chunk as zero value return`() {
        val prototype = Compiler.compile("")

        assertEquals(
            "0000  [1]  RETURN R0 0",
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `rejects unknown local variables`() {
        val error = assertFailsWith<CompilerException> {
            Compiler.compile("return name", "unsupported.lua")
        }

        assertEquals("unsupported.lua", error.position.sourceName)
        assertEquals(1, error.position.line)
        assertEquals(8, error.position.column)
        assertEquals("unsupported.lua:1:8: unknown local 'name'", error.message)
    }

    @Test
    fun `rejects assignment to unknown local variables`() {
        val error = assertFailsWith<CompilerException> {
            Compiler.compile("x = 1", "bad-assign.lua")
        }

        assertEquals("bad-assign.lua:1:1: unknown local 'x'", error.message)
    }

    @Test
    fun `rejects break outside loops`() {
        val error = assertFailsWith<CompilerException> {
            Compiler.compile("break", "bad-break.lua")
        }

        assertEquals("bad-break.lua:1:1: 'break' outside loop", error.message)
    }
}
