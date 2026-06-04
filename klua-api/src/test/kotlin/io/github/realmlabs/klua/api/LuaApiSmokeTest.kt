package io.github.realmlabs.klua.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

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
    fun `facade calls chunks with host arguments`() {
        val lua = Lua.create()

        val result = lua.load(
            """
            local left, middle, right = ...
            return left + right, middle == nil, right
            """.trimIndent(),
            "api-call-arguments.lua",
        ).call(20L, null, 22L)

        assertEquals(42L, result.getLong(1))
        assertEquals(true, result.getBoolean(2))
        assertEquals(22L, result.getLong(3))
    }

    @Test
    fun `facade calls chunks with host userdata arguments`() {
        val lua = Lua.create()
        val host = HostObject("host")

        val result = lua.load("return ...", "api-call-userdata.lua").call(host)

        assertSame(host, result.get(1))
    }

    @Test
    fun `facade returns typed host userdata results`() {
        val lua = Lua.create()
        val host = HostObject("host")

        val result = lua.load("return ...", "api-return-userdata.lua").call(host)

        assertSame(host, result.getUserData(1))
        assertSame(host, result.getUserData(1, HostObject::class.java))
        val error = assertFailsWith<LuaRuntimeException> {
            result.getUserData(1, OtherObject::class.java)
        }

        assertEquals("return value 1 is not ${OtherObject::class.java.name}", error.message)
    }

    @Test
    fun `userdata result accessors reject scalar values`() {
        val lua = Lua.create()

        val result = lua.load("""return "not-userdata"""", "api-return-string.lua").eval()
        val error = assertFailsWith<LuaRuntimeException> {
            result.getUserData(1)
        }

        assertEquals("return value 1 is not userdata", error.message)
    }

    @Test
    fun `facade globals can carry host userdata through lua`() {
        val lua = Lua.create()
        val host = HostObject("host")

        lua.globals().set("host", host)
        val result = lua.load("return host").eval()

        assertSame(host, result.get(1))
    }

    @Test
    fun `facade registered userdata methods retain host identity`() {
        val lua = Lua.create()
        val host = HostObject("host")

        lua.registerType(HostObject::class.java) { type ->
            type.method("rename") { receiver, context ->
                receiver.name = context.toString(1) ?: error("name expected")
                LuaReturn.of(receiver)
            }
        }
        lua.globals().set("host", host)
        val result = lua.load("""return host:rename("renamed")""", "api-userdata-method.lua").eval()

        assertSame(host, result.get(1))
        assertEquals("renamed", host.name)
    }

    @Test
    fun `facade registered userdata properties can be read and written`() {
        val lua = Lua.create()
        val host = HostObject("host")

        lua.registerType(HostObject::class.java) { type ->
            type.property(
                "name",
                getter = { receiver -> LuaReturn.of(receiver.name) },
                setter = { receiver, value -> receiver.name = value as String },
            )
        }
        lua.globals().set("host", host)
        val result = lua.load(
            """
            local before = host.name
            host.name = "renamed"
            return before, host.name
            """.trimIndent(),
            "api-userdata-property.lua",
        ).eval()

        assertEquals("host", result.getString(1))
        assertEquals("renamed", result.getString(2))
        assertEquals("renamed", host.name)
    }

    @Test
    fun `call context yield reports non yieldable host boundary`() {
        val state = LuaState.create()
        state.register("yield") { context ->
            context.yield((1..context.argumentCount).map { index -> context.get(index) })
        }

        assertEquals(LuaStatus.OK, state.load("return yield(42)", "api-yield.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertEquals("attempt to yield across a non-yieldable boundary", state.toString(-1))
    }

    @Test
    fun `facade throws structured runtime errors`() {
        val lua = Lua.create()

        val error = assertFailsWith<LuaRuntimeException> {
            lua.load("""return "x" + 1""", "bad.lua").eval()
        }

        assertEquals("attempt to perform arithmetic on string", error.message)
        assertEquals("bad.lua", error.sourceName)
        assertEquals(1, error.line)
    }

    @Test
    fun `facade runtime errors expose lua call frames`() {
        val lua = Lua.create()

        val error = assertFailsWith<LuaRuntimeException> {
            lua.load(
                """
                local function inner()
                    return "x" + 1
                end
                local function outer()
                    return inner()
                end
                return outer()
                """.trimIndent(),
                "api-trace.lua",
            ).eval()
        }

        assertEquals(
            listOf(
                LuaStackFrame("api-trace.lua", 2),
                LuaStackFrame("api-trace.lua", 5),
                LuaStackFrame("api-trace.lua", 7),
            ),
            error.luaFrames,
        )
        assertEquals(
            "attempt to perform arithmetic on string\n" +
                "stack traceback:\n" +
                "\tapi-trace.lua:2\n" +
                "\tapi-trace.lua:5\n" +
                "\tapi-trace.lua:7",
            error.traceback,
        )
    }

    private data class HostObject(
        var name: String,
    )

    private data class OtherObject(
        val name: String,
    )
}
