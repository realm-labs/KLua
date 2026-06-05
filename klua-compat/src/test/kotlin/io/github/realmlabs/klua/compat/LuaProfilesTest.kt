package io.github.realmlabs.klua.compat

import io.github.realmlabs.klua.api.LuaVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LuaProfilesTest {
    @Test
    fun `profile factories select named lua versions`() {
        assertEquals(LuaVersion.LUA_51, LuaProfiles.lua51().version)
        assertEquals(LuaVersion.LUA_52, LuaProfiles.lua52().version)
        assertEquals(LuaVersion.LUA_53, LuaProfiles.lua53().version)
        assertEquals(LuaVersion.LUA_54, LuaProfiles.lua54().version)
        assertEquals(LuaVersion.LUA_55, LuaProfiles.lua55().version)
        assertEquals(LuaVersion.LUAJIT_21, LuaProfiles.luajit21().version)
    }

    @Test
    fun `production profile disables debug support`() {
        val config = LuaProfiles.production()

        assertEquals(LuaVersion.LUA_54, config.version)
        assertFalse(config.debugEnabled)
    }
}
