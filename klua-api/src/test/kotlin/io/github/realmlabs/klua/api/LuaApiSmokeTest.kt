package io.github.realmlabs.klua.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LuaApiSmokeTest {
    @Test
    fun `state and facade default to lua 54`() {
        assertEquals(LuaVersion.LUA_54, LuaState.create().config.version)
        assertEquals(LuaVersion.LUA_54, Lua.create().config.version)
    }

    @Test
    fun `loaded chunk keeps source identity`() {
        val chunk = Lua.create().load("return 42", "smoke.lua")

        assertEquals("return 42", chunk.source)
        assertEquals("smoke.lua", chunk.chunkName)
    }

    @Test
    fun `facade evaluates chunks and restores state`() {
        val lua = Lua.create()

        assertEquals(42L, lua.load("return 40 + 2").evalLong())
        assertEquals("ok", lua.load("""return "ok"""").evalString())
    }

    @Test
    fun `facade throws structured runtime errors`() {
        val lua = Lua.create()

        val error = assertFailsWith<LuaRuntimeException> {
            lua.load("""return "x" + 1""", "bad.lua").eval()
        }

        assertEquals("attempt to perform arithmetic on string", error.message)
    }
}
