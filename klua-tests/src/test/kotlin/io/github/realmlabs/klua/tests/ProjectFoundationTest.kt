package io.github.realmlabs.klua.tests

import io.github.realmlabs.klua.api.LuaState
import io.github.realmlabs.klua.api.LuaVersion
import io.github.realmlabs.klua.compat.LuaProfiles
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectFoundationTest {
    @Test
    fun `default state and named profile remain lua 54 compatible`() {
        assertEquals(LuaVersion.LUA_54, LuaState.create().config.version)
        assertEquals(LuaVersion.LUA_54, LuaProfiles.lua54().version)
    }
}
