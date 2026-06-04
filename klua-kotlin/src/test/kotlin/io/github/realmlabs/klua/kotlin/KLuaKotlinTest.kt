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
    fun `calls loaded chunks with invoke operator`() {
        val lua = Lua.create()

        val result = lua.load("local left, right = ...; return left + right")(20L, 22L)

        assertEquals(42L, result.getLong(1))
    }

    @Test
    fun `reads userdata returns with reified helper`() {
        val lua = Lua.create()
        val host = HostObject("host")

        val result = lua.load("return ...")(host)

        assertEquals(host, result.getUserData<HostObject>(1))
    }

    @Test
    fun `registers context host functions`() {
        val lua = Lua.create()

        lua.globals.function("identity", lua.functionContext {
            LuaReturn.of(get(1))
        })

        assertEquals("ok", lua.load("""return identity("ok")""").evalString())
    }

    @Test
    fun `registers userdata types with reified kotlin helper`() {
        val lua = Lua.create()
        val host = HostObject("host")

        lua.registerType<HostObject> {
            method("rename") { receiver, context ->
                receiver.name = context.toString(1) ?: error("name expected")
                LuaReturn.of(receiver.name)
            }
        }
        lua.globals["host"] = host

        assertEquals("renamed", lua.load("""return host:rename("renamed")""").evalString())
        assertEquals("renamed", host.name)
    }

    private data class HostObject(
        var name: String,
    )
}
