package io.github.realmlabs.klua.tests

import io.github.realmlabs.klua.api.LuaState
import kotlin.test.Test
import kotlin.test.assertTrue

class ProjectFoundationTest {
    @Test
    fun `default state uses single lua 55 config`() {
        assertTrue(LuaState.create().config.debugEnabled)
    }
}
