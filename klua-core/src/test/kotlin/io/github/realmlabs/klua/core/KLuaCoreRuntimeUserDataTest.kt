package io.github.realmlabs.klua.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class KLuaCoreRuntimeUserDataTest {
    @Test
    fun `passes userdata globals through lua to core functions`() {
        val host = HostObject("player")
        val globals = KLuaCoreGlobals.create()
        globals.set("host", KLuaCoreValue.UserDataValue(host))
        globals.setFunction("identity") { arguments ->
            KLuaCoreCallResult.Success(listOf(arguments.single()))
        }
        val chunk = assertIs<KLuaCoreLoad.Success>(
            KLuaCoreRuntime.compile("return identity(host)", "userdata.lua"),
        ).chunk

        val result = assertIs<KLuaCoreExecution.Success>(
            KLuaCoreRuntime.execute(chunk, emptyList(), globals),
        )
        val userData = assertIs<KLuaCoreValue.UserDataValue>(result.values.single())

        assertSame(host, userData.value)
    }

    @Test
    fun `passes userdata chunk arguments through lua returns`() {
        val host = HostObject("argument")
        val chunk = assertIs<KLuaCoreLoad.Success>(
            KLuaCoreRuntime.compile("return ...", "userdata-argument.lua"),
        ).chunk

        val result = assertIs<KLuaCoreExecution.Success>(
            KLuaCoreRuntime.execute(chunk, listOf(KLuaCoreValue.UserDataValue(host))),
        )
        val userData = assertIs<KLuaCoreValue.UserDataValue>(result.values.single())

        assertEquals("argument", userData.value.let { (it as HostObject).name })
        assertSame(host, userData.value)
    }

    @Test
    fun `calls registered userdata methods from lua source`() {
        val host = HostObject("player")
        val globals = KLuaCoreGlobals.create()
        globals.set("player", KLuaCoreValue.UserDataValue(host))
        globals.setUserDataMethod(HostObject::class.java, "rename") { receiver, arguments ->
            receiver.name = (arguments.single() as KLuaCoreValue.StringValue).value
            KLuaCoreCallResult.Success(listOf(KLuaCoreValue.UserDataValue(receiver)))
        }
        val chunk = assertIs<KLuaCoreLoad.Success>(
            KLuaCoreRuntime.compile("""return player:rename("renamed")""", "userdata-method.lua"),
        ).chunk

        val result = assertIs<KLuaCoreExecution.Success>(
            KLuaCoreRuntime.execute(chunk, emptyList(), globals),
        )
        val userData = assertIs<KLuaCoreValue.UserDataValue>(result.values.single())

        assertSame(host, userData.value)
        assertEquals("renamed", host.name)
    }

    @Test
    fun `gets and sets registered userdata properties from lua source`() {
        val host = HostObject("player")
        val globals = KLuaCoreGlobals.create()
        globals.set("player", KLuaCoreValue.UserDataValue(host))
        globals.setUserDataProperty(
            type = HostObject::class.java,
            name = "name",
            getter = { receiver ->
                KLuaCoreCallResult.Success(listOf(KLuaCoreValue.StringValue(receiver.name)))
            },
            setter = { receiver, value ->
                receiver.name = (value as KLuaCoreValue.StringValue).value
                KLuaCoreCallResult.Success(emptyList())
            },
        )
        val chunk = assertIs<KLuaCoreLoad.Success>(
            KLuaCoreRuntime.compile(
                """
                player.name = "renamed"
                return player.name
                """.trimIndent(),
                "userdata-property.lua",
            ),
        ).chunk

        val result = assertIs<KLuaCoreExecution.Success>(
            KLuaCoreRuntime.execute(chunk, emptyList(), globals),
        )

        assertEquals(listOf(KLuaCoreValue.StringValue("renamed")), result.values)
        assertEquals("renamed", host.name)
    }

    private data class HostObject(
        var name: String,
    )
}
