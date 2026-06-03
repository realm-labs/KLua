package io.github.realmlabs.klua.kotlin

import io.github.realmlabs.klua.api.Lua
import io.github.realmlabs.klua.api.LuaReturn
import kotlin.test.Test
import kotlin.test.assertEquals

class KLuaKotlinTest {
    @Test
    fun `creates default Lua facade`() {
        val lua = Lua.createDefault()

        assertEquals(42L, lua.load("return 40 + 2").evalLong())
    }

    @Test
    fun `uses indexed globals`() {
        val lua = Lua.create()

        lua.globals["answer"] = 41L

        assertEquals(42L, lua.load("return answer + 1").evalLong())
        assertEquals(41L, lua.globals["answer"])
    }

    @Test
    fun `registers typed host functions`() {
        val lua = Lua.create()

        lua.globals["add"] = lua.function { left: Long, right: Long -> left + right }

        assertEquals(42L, lua.load("return add(20, 22)").evalLong())
    }

    @Test
    fun `registers context host functions`() {
        val lua = Lua.create()

        lua.globals.function("identity", lua.functionContext {
            LuaReturn.of(get(1))
        })

        assertEquals("ok", lua.load("""return identity("ok")""").evalString())
    }
}
