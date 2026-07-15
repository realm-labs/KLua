package io.github.realmlabs.klua.core.compiler

import io.github.realmlabs.klua.core.bytecode.Disassembler
import io.github.realmlabs.klua.core.bytecode.LocalVarInfo
import io.github.realmlabs.klua.core.value.LuaFloat
import io.github.realmlabs.klua.core.value.LuaInteger
import io.github.realmlabs.klua.core.value.LuaString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CompilerTest {
    @Test
    fun `compiles integer return with inline load`() {
        val prototype = Compiler.compile("return 42", "return-int.lua")

        assertEquals("return-int.lua", prototype.sourceName)
        assertEquals("return-int.lua", prototype.sourceId)
        assertEquals("return-int.lua", prototype.debugInfo.sourceName)
        assertEquals("return-int.lua", prototype.debugInfo.sourceId)
        assertContentEquals(intArrayOf(1, 1), prototype.debugInfo.lineByPc)
        assertEquals(null, prototype.debugInfo.columnByPc)
        assertEquals(0, prototype.debugInfo.lineDefined)
        assertEquals(0, prototype.debugInfo.lastLineDefined)
        assertEquals(1, prototype.maxStackSize)
        assertEquals(0, prototype.constants.size)
        assertContentEquals(intArrayOf(1, 1), prototype.lineInfo)
        assertContentEquals(intArrayOf(1), prototype.validBreakpointLines)
        assertContentEquals(intArrayOf(1), prototype.debugInfo.validBreakpointLines)
        assertEquals(
            """
            0000  [1]  LOAD_INT R0 42
            0001  [1]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles valid breakpoint line metadata`() {
        val prototype = Compiler.compile(
            """
            local x = 1
            local y = 2
            return x + y
            """.trimIndent(),
            "breakpoints.lua",
        )

        assertEquals("breakpoints.lua", prototype.sourceId)
        assertContentEquals(intArrayOf(1, 2, 3), prototype.validBreakpointLines)
    }

    @Test
    fun `compiles local variable debug metadata`() {
        val prototype = Compiler.compile(
            """
            local x = 1
            do
                local y = 2
                x = y
            end
            return x
            """.trimIndent(),
            "locals.lua",
        )

        assertContentEquals(
            arrayOf(
                LocalVarInfo("x", slot = 0, startPc = 1, endPc = prototype.code.size),
                LocalVarInfo("y", slot = 1, startPc = 2, endPc = 4),
            ),
            prototype.localVars,
        )
        assertContentEquals(prototype.localVars, prototype.debugInfo.localVars)
    }

    @Test
    fun `snapshots debug metadata for serialization`() {
        val prototype = Compiler.compile(
            """
            local x = 1
            do
                local y = 2
                x = y
            end
            return x
            """.trimIndent(),
            "debug-snapshot.lua",
        )

        val snapshot = prototype.debugInfo.toSnapshot()

        assertEquals("debug-snapshot.lua", snapshot.sourceName)
        assertEquals("debug-snapshot.lua", snapshot.sourceId)
        assertEquals(prototype.lineInfo.toList(), snapshot.lineByPc)
        assertEquals(null, snapshot.columnByPc)
        assertEquals(listOf(1, 3, 4, 6), snapshot.validBreakpointLines)
        assertEquals(
            listOf(
                "x" to 0,
                "y" to 1,
            ),
            snapshot.localVars.map { local -> local.name to local.slot },
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
    fun `compiles implicit return after non-empty chunk`() {
        val prototype = Compiler.compile("local x = 1", "implicit-return.lua")

        assertEquals(
            """
            0000  [1]  LOAD_INT R0 1
            0001  [1]  RETURN R0 0
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
    fun `compiles logical expressions with short circuit jumps`() {
        val prototype = Compiler.compile("""return false and "right", true or "right", nil or "fallback"""")

        assertEquals(4, prototype.maxStackSize)
        assertEquals(
            """
            0000  [1]  LOAD_BOOL R0 false
            0001  [1]  TEST R0 1
            0002  [1]  LOAD_K R0 K0 ; "right"
            0003  [1]  LOAD_BOOL R1 true
            0004  [1]  NOT R2 R1
            0005  [1]  TEST R2 1
            0006  [1]  LOAD_K R1 K0 ; "right"
            0007  [1]  LOAD_NIL R2
            0008  [1]  NOT R3 R2
            0009  [1]  TEST R3 1
            0010  [1]  LOAD_K R2 K1 ; "fallback"
            0011  [1]  RETURN R0 3
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles right associative concatenation`() {
        val prototype = Compiler.compile("""return "a" .. 1 .. "b"""")

        assertEquals(3, prototype.maxStackSize)
        assertEquals(
            """
            0000  [1]  LOAD_K R0 K0 ; "a"
            0001  [1]  LOAD_INT R1 1
            0002  [1]  LOAD_K R2 K1 ; "b"
            0003  [1]  CONCAT R1 R1 R2
            0004  [1]  CONCAT R0 R0 R1
            0005  [1]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles bitwise expressions`() {
        val prototype = Compiler.compile("return ~1, 6 & 3, 4 | 1, 5 ~ 3")

        assertEquals(5, prototype.maxStackSize)
        assertEquals(
            """
            0000  [1]  LOAD_INT R0 1
            0001  [1]  BNOT R0 R0
            0002  [1]  LOAD_INT R1 6
            0003  [1]  LOAD_INT R2 3
            0004  [1]  BAND R1 R1 R2
            0005  [1]  LOAD_INT R2 4
            0006  [1]  LOAD_INT R3 1
            0007  [1]  BOR R2 R2 R3
            0008  [1]  LOAD_INT R3 5
            0009  [1]  LOAD_INT R4 3
            0010  [1]  BXOR R3 R3 R4
            0011  [1]  RETURN R0 4
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles shift expressions`() {
        val prototype = Compiler.compile("return 1 << 3, 8 >> 1")

        assertEquals(3, prototype.maxStackSize)
        assertEquals(
            """
            0000  [1]  LOAD_INT R0 1
            0001  [1]  LOAD_INT R1 3
            0002  [1]  SHL R0 R0 R1
            0003  [1]  LOAD_INT R1 8
            0004  [1]  LOAD_INT R2 1
            0005  [1]  SHR R1 R1 R2
            0006  [1]  RETURN R0 2
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles string length expressions`() {
        val prototype = Compiler.compile("""return #"abc", #("a" .. "b")""")

        assertEquals(3, prototype.maxStackSize)
        assertEquals(
            """
            0000  [1]  LOAD_K R0 K0 ; "abc"
            0001  [1]  LEN R0 R0
            0002  [1]  LOAD_K R1 K1 ; "a"
            0003  [1]  LOAD_K R2 K2 ; "b"
            0004  [1]  CONCAT R1 R1 R2
            0005  [1]  LEN R1 R1
            0006  [1]  RETURN R0 2
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
    fun `rejects reassignment to const local`() {
        val error = assertFailsWith<CompilerException> {
            Compiler.compile(
                """
                local x <const> = 1
                x = 2
                """.trimIndent(),
                "const-local.lua",
            )
        }

        assertEquals("const-local.lua:2:1: attempt to assign to const variable 'x'", error.message)
    }

    @Test
    fun `rejects reassignment to prefixed const local defaults`() {
        val error = assertFailsWith<CompilerException> {
            Compiler.compile(
                """
                local <const> x, y = 1, 2
                y = 3
                """.trimIndent(),
                "prefixed-const-local.lua",
            )
        }

        assertEquals("prefixed-const-local.lua:2:1: attempt to assign to const variable 'y'", error.message)
    }

    @Test
    fun `rejects reassignment to captured const local`() {
        val error = assertFailsWith<CompilerException> {
            Compiler.compile(
                """
                local x <const> = 1
                local function set()
                    x = 2
                end
                """.trimIndent(),
                "captured-const-local.lua",
            )
        }

        assertEquals("captured-const-local.lua:3:5: attempt to assign to const variable 'x'", error.message)
    }

    @Test
    fun `compiles false to be closed locals as no-op close values`() {
        val prototype = Compiler.compile(
            """
            local absent <close>
            local none <close> = nil
            local disabled <close> = false
            return absent, none, disabled
            """.trimIndent(),
            "close-false-local.lua",
        )

        assertEquals("close-false-local.lua", prototype.sourceName)
    }

    @Test
    fun `rejects non false to be closed locals until close method semantics exist`() {
        val error = assertFailsWith<CompilerException> {
            Compiler.compile("""local resource <close> = {}""", "close-local.lua")
        }

        assertEquals("close-local.lua:1:1: to-be-closed local variables are not supported", error.message)
    }

    @Test
    fun `checks dynamic to be closed locals initialized from open results`() {
        val direct = Compiler.compile(
            """
            local function maybeClose()
                return nil
            end
            local resource <close> = maybeClose()
            """.trimIndent(),
            "close-call-local.lua",
        )
        assertTrue(Disassembler.disassemble(direct).contains("""CHECK_CLOSE_FALSE R1 K0 ; "resource""""))

        val adjusted = Compiler.compile(
            """
            local function values()
                return false, nil
            end
            local disabled, resource <close> = values()
            """.trimIndent(),
            "close-adjusted-call-local.lua",
        )
        assertTrue(Disassembler.disassemble(adjusted).contains("""CHECK_CLOSE_FALSE R2 K0 ; "resource""""))
    }

    @Test
    fun `rejects prefixed non false to be closed locals until close method semantics exist`() {
        val error = assertFailsWith<CompilerException> {
            Compiler.compile("""local <close> resource = {}""", "prefixed-close-local.lua")
        }

        assertEquals("prefixed-close-local.lua:1:1: to-be-closed local variables are not supported", error.message)
    }

    @Test
    fun `const global declarations reject direct assignment`() {
        val error = assertFailsWith<CompilerException> {
            Compiler.compile(
                """
                global <const> answer = 42
                answer = 43
                """.trimIndent(),
                "global-const-declaration.lua",
            )
        }

        assertEquals("global-const-declaration.lua:2:1: attempt to assign to const variable 'answer'", error.message)
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
    fun `closes captured locals before break exits loop scope`() {
        val prototype = Compiler.compile(
            """
            local function outer()
                local get
                while true do
                    local captured = "live"
                    get = function()
                        return captured
                    end
                    break
                end
                local captured = "shadow"
                return get()
            end
            return outer()
            """.trimIndent(),
            "break-close.lua",
        )

        val outer = prototype.nested.single()
        val disassembly = Disassembler.disassemble(outer)
        assertTrue(disassembly.contains("CLOSE_UPVALUES R1"), disassembly)
    }

    @Test
    fun `compiles numeric for loop`() {
        val prototype = Compiler.compile(
            """
            local sum = 0
            for i = 1, 3 do
                sum = sum + i
            end
            return sum
            """.trimIndent(),
        )

        assertEquals(
            """
            0000  [1]  LOAD_INT R0 0
            0001  [2]  LOAD_INT R1 1
            0002  [2]  LOAD_INT R2 3
            0003  [2]  LOAD_INT R3 1
            0004  [2]  FOR_TEST R1 5
            0005  [3]  MOVE R4 R0
            0006  [3]  MOVE R5 R1
            0007  [3]  ADD R4 R4 R5
            0008  [3]  MOVE R0 R4
            0009  [2]  FOR_LOOP R1 -5
            0010  [5]  MOVE R1 R0
            0011  [5]  MOVE R0 R1
            0012  [5]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `rejects reassignment to numeric for control variable`() {
        val error = assertFailsWith<CompilerException> {
            Compiler.compile(
                """
                for i = 1, 3 do
                    i = 4
                end
                """.trimIndent(),
                "numeric-for-control.lua",
            )
        }

        assertEquals("numeric-for-control.lua:2:5: attempt to assign to const variable 'i'", error.message)
    }

    @Test
    fun `rejects captured reassignment to numeric for control variable`() {
        val error = assertFailsWith<CompilerException> {
            Compiler.compile(
                """
                for i = 1, 3 do
                    local function set()
                        i = 4
                    end
                end
                """.trimIndent(),
                "numeric-for-captured-control.lua",
            )
        }

        assertEquals("numeric-for-captured-control.lua:3:9: attempt to assign to const variable 'i'", error.message)
    }

    @Test
    fun `rejects reassignment to first generic for control variable`() {
        val error = assertFailsWith<CompilerException> {
            Compiler.compile(
                """
                for key, value in iterator() do
                    key = "next"
                end
                """.trimIndent(),
                "generic-for-control.lua",
            )
        }

        assertEquals("generic-for-control.lua:2:5: attempt to assign to const variable 'key'", error.message)
    }

    @Test
    fun `rejects captured reassignment to first generic for control variable`() {
        val error = assertFailsWith<CompilerException> {
            Compiler.compile(
                """
                for key, value in iterator() do
                    local function set()
                        key = "next"
                    end
                end
                """.trimIndent(),
                "generic-for-captured-control.lua",
            )
        }

        assertEquals("generic-for-captured-control.lua:3:9: attempt to assign to const variable 'key'", error.message)
    }

    @Test
    fun `allows reassignment to later generic for variables`() {
        Compiler.compile(
            """
            for key, value in iterator() do
                value = "next"
            end
            """.trimIndent(),
            "generic-for-later-variable.lua",
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
    fun `compiles global variable reads`() {
        val prototype = Compiler.compile("return answer", "globals.lua")

        assertEquals(
            """
            0000  [1]  GET_GLOBAL R0 K0 ; "answer"
            0001  [1]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles default environment reads`() {
        val prototype = Compiler.compile("return _ENV.answer", "environment.lua")

        assertEquals(
            """
            0000  [1]  GET_ENV R0
            0001  [1]  GET_FIELD R0 R0 K0 ; "answer"
            0002  [1]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles implicit environment assignment`() {
        val prototype = Compiler.compile(
            """
            _ENV = { answer = 42 }
            return answer
            """.trimIndent(),
            "environment-assign.lua",
        )

        assertEquals(
            """
            0000  [1]  NEW_TABLE R0 entries=1
            0001  [1]  LOAD_INT R2 42
            0002  [1]  SET_FIELD R0 K0 R2 ; "answer"
            0003  [1]  SET_ENV R0
            0004  [2]  GET_GLOBAL R0 K0 ; "answer"
            0005  [2]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles global reads through local environments`() {
        val prototype = Compiler.compile(
            """
            local _ENV = { answer = 42 }
            return answer
            """.trimIndent(),
            "local-environment-read.lua",
        )

        assertEquals(
            """
            0000  [1]  NEW_TABLE R0 entries=1
            0001  [1]  LOAD_INT R2 42
            0002  [1]  SET_FIELD R0 K0 R2 ; "answer"
            0003  [2]  MOVE R1 R0
            0004  [2]  GET_FIELD R1 R1 K0 ; "answer"
            0005  [2]  MOVE R0 R1
            0006  [2]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles global assignments`() {
        val prototype = Compiler.compile(
            """
            answer = 41
            return answer
            """.trimIndent(),
            "global-assign.lua",
        )

        assertEquals(
            """
            0000  [1]  LOAD_INT R0 41
            0001  [1]  SET_GLOBAL K0 R0 ; "answer"
            0002  [2]  GET_GLOBAL R0 K0 ; "answer"
            0003  [2]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles initialized global declarations with redeclaration checks`() {
        val prototype = Compiler.compile(
            """
            global first, second = 1, 2
            return first, second
            """.trimIndent(),
            "global-init.lua",
        )

        assertEquals(
            """
            0000  [1]  LOAD_INT R0 1
            0001  [1]  LOAD_INT R1 2
            0002  [1]  CHECK_GLOBAL_NIL K0 ; "second"
            0003  [1]  SET_GLOBAL K0 R1 ; "second"
            0004  [1]  CHECK_GLOBAL_NIL K1 ; "first"
            0005  [1]  SET_GLOBAL K1 R0 ; "first"
            0006  [2]  GET_GLOBAL R0 K1 ; "first"
            0007  [2]  GET_GLOBAL R1 K0 ; "second"
            0008  [2]  RETURN R0 2
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles initialized global declarations through local environments`() {
        val prototype = Compiler.compile(
            """
            local _ENV = {}
            global answer = 42
            return _ENV.answer
            """.trimIndent(),
            "global-init-local-env.lua",
        )

        assertEquals(
            """
            0000  [1]  NEW_TABLE R0
            0001  [2]  LOAD_INT R1 42
            0002  [2]  MOVE R2 R0
            0003  [2]  CHECK_FIELD_NIL R2 K0 ; "answer"
            0004  [2]  SET_FIELD R2 K0 R1 ; "answer"
            0005  [3]  MOVE R1 R0
            0006  [3]  GET_FIELD R1 R1 K0 ; "answer"
            0007  [3]  MOVE R0 R1
            0008  [3]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `declaration only globals allow later global reads and writes`() {
        val prototype = Compiler.compile(
            """
            global answer
            answer = 42
            return answer
            """.trimIndent(),
            "global-declaration.lua",
        )

        assertEquals(
            """
            0000  [2]  LOAD_INT R0 42
            0001  [2]  SET_GLOBAL K0 R0 ; "answer"
            0002  [3]  GET_GLOBAL R0 K0 ; "answer"
            0003  [3]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `named global declarations reject undeclared globals in scope`() {
        val error = assertFailsWith<CompilerException> {
            Compiler.compile(
                """
                global answer
                other = 1
                """.trimIndent(),
                "strict-global-declaration.lua",
            )
        }

        assertEquals("strict-global-declaration.lua:2:1: variable 'other' not declared", error.message)
    }

    @Test
    fun `wildcard global declarations allow undeclared globals in scope`() {
        val prototype = Compiler.compile(
            """
            global answer
            global *
            other = 1
            return other
            """.trimIndent(),
            "wildcard-global-declaration.lua",
        )

        assertEquals(
            """
            0000  [3]  LOAD_INT R0 1
            0001  [3]  SET_GLOBAL K0 R0 ; "other"
            0002  [4]  GET_GLOBAL R0 K0 ; "other"
            0003  [4]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `const wildcard global declarations reject undeclared global assignment`() {
        val error = assertFailsWith<CompilerException> {
            Compiler.compile(
                """
                global <const> *
                other = 1
                """.trimIndent(),
                "const-wildcard-global-declaration.lua",
            )
        }

        assertEquals(
            "const-wildcard-global-declaration.lua:2:1: attempt to assign to const variable 'other'",
            error.message,
        )
    }

    @Test
    fun `global declaration scopes end with blocks`() {
        val prototype = Compiler.compile(
            """
            do
                global answer
            end
            return other
            """.trimIndent(),
            "global-block-scope.lua",
        )

        assertEquals(
            """
            0000  [4]  GET_GLOBAL R0 K0 ; "other"
            0001  [4]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `later named global declarations shadow earlier locals`() {
        val prototype = Compiler.compile(
            """
            local answer = 1
            global answer
            answer = 2
            return answer
            """.trimIndent(),
            "global-shadows-local.lua",
        )

        assertEquals(
            """
            0000  [1]  LOAD_INT R0 1
            0001  [3]  LOAD_INT R1 2
            0002  [3]  SET_GLOBAL K0 R1 ; "answer"
            0003  [4]  GET_GLOBAL R1 K0 ; "answer"
            0004  [4]  MOVE R0 R1
            0005  [4]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `later wildcard global declarations do not shadow earlier locals`() {
        val prototype = Compiler.compile(
            """
            local answer = 1
            global *
            answer = 2
            return answer
            """.trimIndent(),
            "wildcard-global-keeps-local.lua",
        )

        assertEquals(
            """
            0000  [1]  LOAD_INT R0 1
            0001  [3]  LOAD_INT R1 2
            0002  [3]  MOVE R0 R1
            0003  [4]  MOVE R1 R0
            0004  [4]  MOVE R0 R1
            0005  [4]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `rejects vararg expressions outside vararg functions`() {
        val error = assertFailsWith<CompilerException> {
            Compiler.compile("return ...", "bad-vararg.lua")
        }

        assertEquals("bad-vararg.lua:1:8: cannot use '...' outside a vararg function", error.message)
    }

    @Test
    fun `compiles opt-in top-level vararg chunks`() {
        val prototype = Compiler.compile("return ...", "vararg-chunk.lua", isVarargChunk = true)

        assertEquals(true, prototype.isVararg)
        assertEquals(
            """
            0000  [1]  VARARG R0 *
            0001  [1]  RETURN R0 *
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `rejects break outside loops`() {
        val error = assertFailsWith<CompilerException> {
            Compiler.compile("break", "bad-break.lua")
        }

        assertEquals("bad-break.lua:1:1: break outside loop", error.message)
    }

    @Test
    fun `rejects unresolved goto labels`() {
        val error = assertFailsWith<CompilerException> {
            Compiler.compile("goto missing", "bad-goto.lua")
        }

        assertEquals("bad-goto.lua:1:1: no visible label 'missing' for <goto> at line 1", error.message)
    }

    @Test
    fun `rejects duplicate labels`() {
        val error = assertFailsWith<CompilerException> {
            Compiler.compile(
                """
                ::again::
                ::again::
                """.trimIndent(),
                "duplicate-label.lua",
            )
        }

        assertEquals("duplicate-label.lua:1:1: label 'again' already defined on line 2", error.message)
    }

    @Test
    fun `allows duplicate labels in disjoint blocks`() {
        val prototype = Compiler.compile(
            """
            do
                ::again::
            end
            do
                ::again::
            end
            return 1
            """.trimIndent(),
            "disjoint-labels.lua",
        )

        assertEquals("disjoint-labels.lua", prototype.sourceName)
    }

    @Test
    fun `does not resolve gotos to labels outside visible scope`() {
        val error = assertFailsWith<CompilerException> {
            Compiler.compile(
                """
                do
                    ::inside::
                end
                goto inside
                """.trimIndent(),
                "out-of-scope-label.lua",
            )
        }

        assertEquals("out-of-scope-label.lua:4:1: no visible label 'inside' for <goto> at line 4", error.message)
    }

    @Test
    fun `allows goto to label at end of block after local declarations`() {
        val prototype = Compiler.compile(
            """
            do
                goto done
                local value = 1
                ::done::
            end
            return 2
            """.trimIndent(),
            "goto-end-label.lua",
        )

        assertEquals("goto-end-label.lua", prototype.sourceName)
    }

    @Test
    fun `allows goto to label before trailing semicolon no ops`() {
        val prototype = Compiler.compile(
            """
            do
                goto done
                local value = 1
                ::done::;;
            end
            return 2
            """.trimIndent(),
            "goto-end-label-semicolon.lua",
        )

        assertEquals("goto-end-label-semicolon.lua", prototype.sourceName)
    }

    @Test
    fun `allows goto to labels in trailing no op label runs`() {
        val firstPrototype = Compiler.compile(
            """
            do
                goto first
                local value = 1
                ::first::
                ::second::
            end
            return 2
            """.trimIndent(),
            "goto-first-trailing-label-run.lua",
        )
        val secondPrototype = Compiler.compile(
            """
            do
                goto second
                local value = 1
                ::first::
                ::second::
            end
            return 2
            """.trimIndent(),
            "goto-second-trailing-label-run.lua",
        )

        assertEquals("goto-first-trailing-label-run.lua", firstPrototype.sourceName)
        assertEquals("goto-second-trailing-label-run.lua", secondPrototype.sourceName)
    }

    @Test
    fun `allows nested goto to enclosing end label after local declarations`() {
        val prototype = Compiler.compile(
            """
            do
                do
                    goto done
                end
                local skipped = 1
                ::done::
            end
            return 2
            """.trimIndent(),
            "goto-nested-export-end-label.lua",
        )

        assertEquals("goto-nested-export-end-label.lua", prototype.sourceName)
    }

    @Test
    fun `rejects exported goto into later outer local scope`() {
        val error = assertFailsWith<CompilerException> {
            Compiler.compile(
                """
                local outer = 0
                do
                    local inner = 1
                    goto done
                end
                local later = 2
                ::done::
                return outer
                """.trimIndent(),
                "goto-export-local-scope.lua",
            )
        }

        assertEquals(
            "goto-export-local-scope.lua:4:5: <goto done> at line 4 jumps into the scope of 'later'",
            error.message,
        )
    }

    @Test
    fun `closes captured locals before goto escapes scope`() {
        val prototype = Compiler.compile(
            """
            local function outer()
                local get
                do
                    local captured = "live"
                    get = function()
                        return captured
                    end
                    goto done
                end
                ::done::
                local captured = "shadow"
                return get()
            end
            return outer()
            """.trimIndent(),
            "goto-close.lua",
        )

        val outer = prototype.nested.single()
        val disassembly = Disassembler.disassemble(outer)
        assertTrue(disassembly.contains("CLOSE_UPVALUES R1"), disassembly)
    }

    @Test
    fun `rejects goto into local variable scope`() {
        val error = assertFailsWith<CompilerException> {
            Compiler.compile(
                """
                goto done
                local value = 1
                ::done::
                return value
                """.trimIndent(),
                "goto-local-scope.lua",
            )
        }

        assertEquals(
            "goto-local-scope.lua:1:1: <goto done> at line 1 jumps into the scope of 'value'",
            error.message,
        )
    }

    @Test
    fun `reports first local name for goto into multiple local declarations`() {
        val error = assertFailsWith<CompilerException> {
            Compiler.compile(
                """
                goto done
                local first, second = 1, 2
                ::done::
                return first, second
                """.trimIndent(),
                "goto-multiple-local-scope.lua",
            )
        }

        assertEquals(
            "goto-multiple-local-scope.lua:1:1: <goto done> at line 1 jumps into the scope of 'first'",
            error.message,
        )
    }

    @Test
    fun `rejects goto into nested end label scope`() {
        val error = assertFailsWith<CompilerException> {
            Compiler.compile(
                """
                goto inside
                do
                    ::inside::
                end
                return 1
                """.trimIndent(),
                "goto-nested-end-label.lua",
            )
        }

        assertEquals("goto-nested-end-label.lua:1:1: no visible label 'inside' for <goto> at line 1", error.message)
    }

    @Test
    fun `rejects goto into nested non end label scope`() {
        val error = assertFailsWith<CompilerException> {
            Compiler.compile(
                """
                goto inside
                do
                    ::inside::
                    local value = 1
                end
                """.trimIndent(),
                "goto-block-scope.lua",
            )
        }

        assertEquals("goto-block-scope.lua:1:1: no visible label 'inside' for <goto> at line 1", error.message)
    }
}
