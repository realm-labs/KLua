package io.github.realmlabs.klua.api

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
