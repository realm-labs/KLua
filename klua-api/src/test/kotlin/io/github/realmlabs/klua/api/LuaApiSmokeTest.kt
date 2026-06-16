package io.github.realmlabs.klua.api

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LuaApiSmokeTest {
    @Test
    fun `state and facade use default config`() {
        assertTrue(LuaState.create().config.debugEnabled)
        assertTrue(Lua.create().config.debugEnabled)
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
    fun `facade compiles and evaluates bytecode chunks`() {
        val lua = Lua.create()
        val bytecode = lua.compileBytecode("return ... + 1", "api-bytecode.lua")

        assertEquals(42L, lua.loadBytecode(bytecode).call(41L).getLong(1))
    }

    @Test
    fun `facade loads bytecode resources`() {
        val lua = Lua.create()
        val bytecode = lua.compileBytecode("return ... + 1", "api-resource-bytecode.lua")
        val resources = bytecodeResources("scripts/main.kluac" to bytecode)

        assertEquals(42L, lua.loadBytecodeResource("scripts/main.kluac", resources).call(41L).getLong(1))
    }

    @Test
    fun `facade reports missing bytecode resources`() {
        val lua = Lua.create()

        val error = assertFailsWith<LuaSyntaxException> {
            lua.loadBytecodeResource("missing.kluac", bytecodeResources())
        }

        assertEquals("KLua bytecode resource not found: missing.kluac", error.message)
    }

    @Test
    fun `state loads bytecode chunks`() {
        val state = LuaState.create()
        val bytecode = state.compileBytecode("return 21 * 2", "api-state-bytecode.lua")

        assertEquals(LuaStatus.OK, state.loadBytecode(bytecode))
        assertEquals(LuaStatus.OK, state.pcall(0, -1))
        assertEquals(42L, state.toInteger(-1))
    }

    @Test
    fun `state loads bytecode resources`() {
        val state = LuaState.create()
        val bytecode = state.compileBytecode("return 21 * 2", "api-state-resource-bytecode.lua")
        val resources = bytecodeResources("scripts/main.kluac" to bytecode)

        assertEquals(LuaStatus.OK, state.loadBytecodeResource("/scripts/main.kluac", resources))
        assertEquals(LuaStatus.OK, state.pcall(0, -1))
        assertEquals(42L, state.toInteger(-1))
    }

    @Test
    fun `state reports missing bytecode resources`() {
        val state = LuaState.create()

        assertEquals(LuaStatus.SYNTAX_ERROR, state.loadBytecodeResource("missing.kluac", bytecodeResources()))
        assertIs<LuaSyntaxException>(state.getLastError())
        assertEquals("KLua bytecode resource not found: missing.kluac", state.toString(-1))
    }

    @Test
    fun `state rejects corrupted bytecode chunks`() {
        val state = LuaState.create()
        val bytecode = state.compileBytecode("return 1", "api-corrupt-bytecode.lua")
        bytecode[bytecode.lastIndex] = (bytecode.last().toInt() xor 1).toByte()

        assertEquals(LuaStatus.SYNTAX_ERROR, state.loadBytecode(bytecode))
        assertIs<LuaSyntaxException>(state.getLastError())
        assertTrue(state.toString(-1)?.startsWith("KLua bytecode payload checksum mismatch") == true)
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

        assertEquals("attempt to yield across a C-call boundary", state.toString(-1))
    }

    @Test
    fun `native pcall preserves explicit nil lua error objects`() {
        val state = LuaState.create()
        state.pushFunction {
            throw LuaRuntimeException("nil", errorObject = null, hasErrorObject = true)
        }

        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))
        val error = assertIs<LuaRuntimeException>(state.getLastError())

        assertTrue(error.hasErrorObject)
        assertEquals(null, error.errorObject)
        assertTrue(state.isNil(-1))
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
                "api-trace.lua" to 2,
                "api-trace.lua" to 5,
                "api-trace.lua" to 7,
            ),
            error.luaFrames.map { frame -> frame.sourceName to frame.line },
        )
        assertEquals(
            "attempt to perform arithmetic on string\n" +
                "stack traceback:\n" +
                "\t[string \"api-trace.lua\"]:2\n" +
                "\t[string \"api-trace.lua\"]:5\n" +
                "\t[string \"api-trace.lua\"]:7",
            error.traceback,
        )
    }

    @Test
    fun `facade runtime tracebacks format file chunk source names`() {
        val lua = Lua.create()

        val error = assertFailsWith<LuaRuntimeException> {
            lua.load(
                """
                local function inner()
                    return "x" + 1
                end
                return inner()
                """.trimIndent(),
                "@/tmp/api-file-trace.lua",
            ).eval()
        }

        assertEquals(
            "attempt to perform arithmetic on string\n" +
                "stack traceback:\n" +
                "\t/tmp/api-file-trace.lua:2\n" +
                "\t/tmp/api-file-trace.lua:4",
            error.traceback,
        )
    }

    @Test
    fun `facade enforces configured instruction limits`() {
        val lua = Lua.create(LuaConfig(instructionLimit = 10))

        val error = assertFailsWith<LuaRuntimeException> {
            lua.load("while true do end", "api-budget.lua").exec()
        }

        assertEquals("instruction limit exceeded", error.message)
        assertEquals("api-budget.lua", error.sourceName)
        assertEquals(1, error.line)
    }

    @Test
    fun `facade function calls enforce configured instruction limits`() {
        val lua = Lua.create(LuaConfig(instructionLimit = 10))
        val function = lua.load(
            "return function() while true do end end",
            "api-function-budget.lua",
        ).eval().get(1) as LuaFunction

        val error = assertFailsWith<LuaRuntimeException> {
            function.call(EmptyLuaCallContext)
        }

        assertEquals("instruction limit exceeded", error.message)
    }

    @Test
    fun `state runtime errors expose host call frames`() {
        val state = LuaState.create()
        state.register("explode") {
            throw IllegalStateException("host boom")
        }

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function outer()
                    return explode()
                end
                return outer()
                """.trimIndent(),
                "api-host-trace.lua",
            ),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))
        val error = assertIs<LuaRuntimeException>(state.getLastError())

        assertEquals("host boom", error.message)
        assertEquals(
            listOf(
                "[Kotlin]: explode" to 0,
                "api-host-trace.lua" to 2,
                "api-host-trace.lua" to 4,
            ),
            error.luaFrames.map { frame -> frame.sourceName to frame.line },
        )
        assertEquals(
            "host boom\n" +
                "stack traceback:\n" +
                "\t[Kotlin]: explode\n" +
                "\t[string \"api-host-trace.lua\"]:2\n" +
                "\t[string \"api-host-trace.lua\"]:4",
            error.traceback,
        )
    }

    @Test
    fun `facade runtime errors expose userdata host call frames`() {
        val lua = Lua.create()
        val host = HostObject("host")
        lua.registerType(HostObject::class.java) { type ->
            type.method("explode") { _, _ ->
                throw IllegalStateException("userdata boom")
            }
        }
        lua.globals().set("host", host)

        val error = assertFailsWith<LuaRuntimeException> {
            lua.load(
                """
                local function outer()
                    return host:explode()
                end
                return outer()
                """.trimIndent(),
                "api-userdata-host-trace.lua",
            ).eval()
        }

        assertEquals("userdata boom", error.message)
        assertEquals(
            listOf(
                "[Kotlin]: HostObject.explode" to 0,
                "api-userdata-host-trace.lua" to 2,
                "api-userdata-host-trace.lua" to 4,
            ),
            error.luaFrames.map { frame -> frame.sourceName to frame.line },
        )
        assertEquals(
            "userdata boom\n" +
                "stack traceback:\n" +
                "\t[Kotlin]: HostObject.explode\n" +
                "\t[string \"api-userdata-host-trace.lua\"]:2\n" +
                "\t[string \"api-userdata-host-trace.lua\"]:4",
            error.traceback,
        )
    }

    @Test
    fun `facade coroutine runtime errors expose lua call frames`() {
        val lua = Lua.create()
        val function = lua.load(
            """
            return function()
                local function inner()
                    return "x" + 1
                end
                return inner()
            end
            """.trimIndent(),
            "api-coroutine-trace.lua",
        ).eval().get(1) as LuaCoroutineFunction

        val error = assertIs<LuaCoroutineResult.RuntimeError>(function.createCoroutine().resume(emptyList()))

        assertEquals("attempt to perform arithmetic on string", error.message)
        assertEquals("api-coroutine-trace.lua", error.sourceName)
        assertEquals(3, error.line)
        assertEquals(
            listOf(
                "api-coroutine-trace.lua" to 3,
                "api-coroutine-trace.lua" to 5,
            ),
            error.luaFrames.map { frame -> frame.sourceName to frame.line },
        )
        assertEquals(
            "attempt to perform arithmetic on string\n" +
                "stack traceback:\n" +
                "\t[string \"api-coroutine-trace.lua\"]:3\n" +
                "\t[string \"api-coroutine-trace.lua\"]:5",
            error.traceback,
        )
    }

    @Test
    fun `facade coroutine resumes enforce configured instruction limits`() {
        val lua = Lua.create(LuaConfig(instructionLimit = 10))
        val function = lua.load(
            "return function() while true do end end",
            "api-coroutine-budget.lua",
        ).eval().get(1) as LuaCoroutineFunction

        val error = assertIs<LuaCoroutineResult.RuntimeError>(function.createCoroutine().resume(emptyList()))

        assertEquals("instruction limit exceeded", error.message)
        assertEquals("api-coroutine-budget.lua", error.sourceName)
        assertEquals(1, error.line)
    }

    private data class HostObject(
        var name: String,
    )

    private data class OtherObject(
        val name: String,
    )

    private object EmptyLuaCallContext : LuaCallContext {
        override val argumentCount: Int = 0

        override fun isNil(index: Int): Boolean = false

        override fun isNone(index: Int): Boolean = true

        override fun isTable(index: Int): Boolean = false

        override fun typeName(index: Int): String = "none"

        override fun get(index: Int): Any? = null

        override fun call(index: Int, arguments: List<Any?>): LuaReturn = error("no callable value")

        override fun call(function: Any?, arguments: List<Any?>): LuaReturn = error("no callable value")

        override fun yield(values: List<Any?>): Nothing = error("not yieldable")

        override fun load(source: String, chunkName: String): LuaReturn = error("loading not supported")

        override fun getTable(index: Int): Any? = null

        override fun getTableValue(index: Int, key: Any?): Any? = null

        override fun getTableField(table: Any?, key: Any?): Any? = null

        override fun setTableValue(index: Int, key: Any?, value: Any?) {
            error("no table value")
        }

        override fun getMetatable(index: Int): Any? = null

        override fun setMetatable(index: Int, metatable: Any?) {
            error("no metatable")
        }

        override fun nextTableEntry(index: Int, key: Any?): List<Any?>? = null

        override fun tableLength(index: Int): Long? = null

        override fun toBoolean(index: Int): Boolean = false

        override fun toInteger(index: Int): Long? = null

        override fun toNumber(index: Int): Double? = null

        override fun toString(index: Int): String? = null

        override fun toUserData(index: Int): Any? = null

        override fun <T : Any> toUserData(index: Int, type: Class<T>): T? = null
    }

    private fun bytecodeResources(vararg resources: Pair<String, ByteArray>): ClassLoader {
        val byName = resources.associate { (name, bytes) -> name to bytes.copyOf() }
        return object : ClassLoader(null) {
            override fun getResourceAsStream(name: String): ByteArrayInputStream? {
                return byName[name]?.let { bytes -> ByteArrayInputStream(bytes) }
            }
        }
    }
}
