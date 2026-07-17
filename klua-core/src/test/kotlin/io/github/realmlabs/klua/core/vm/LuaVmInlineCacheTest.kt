package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.compiler.Compiler
import io.github.realmlabs.klua.core.value.LuaInteger
import io.github.realmlabs.klua.core.value.LuaNativeFunction
import io.github.realmlabs.klua.core.value.LuaNil
import io.github.realmlabs.klua.core.value.LuaString
import io.github.realmlabs.klua.core.value.LuaTable
import io.github.realmlabs.klua.core.value.LuaValue
import kotlin.test.Test
import kotlin.test.assertEquals

class LuaVmInlineCacheTest {
    @Test
    fun `field read cache follows value shape metamethod and metatable changes`() {
        val globals = LuaTable()
        val receiver = LuaTable()
        val firstMetatable = LuaTable()
        val secondMetatable = LuaTable()
        receiver.rawSet(VALUE, LuaInteger(1))
        receiver.metatable = firstMetatable
        globals.rawSet(OBJECT, receiver)
        globals.rawSet(
            MUTATE,
            LuaNativeFunction { arguments ->
                when (arguments.single().integer()) {
                    1L -> receiver.rawSet(VALUE, LuaInteger(2))
                    2L -> {
                        receiver.rawSet(VALUE, LuaNil)
                        firstMetatable.rawSet(INDEX, constantFunction(30))
                    }
                    3L -> firstMetatable.rawSet(INDEX, constantFunction(40))
                    4L -> {
                        secondMetatable.rawSet(INDEX, constantFunction(50))
                        receiver.metatable = secondMetatable
                    }
                }
                emptyList()
            },
        )

        val result = LuaVm(globals).execute(
            Compiler.compile(
                """
                local result = {}
                for i = 1, 5 do
                    result[i] = object.value
                    mutate(i)
                end
                return result[1], result[2], result[3], result[4], result[5]
                """.trimIndent(),
                "inline-cache-field-read.lua",
            ),
        )

        assertEquals(integers(1, 2, 30, 40, 50), result)
    }

    @Test
    fun `field write cache follows newindex replacement removal and raw insertion`() {
        val globals = LuaTable()
        val receiver = LuaTable()
        val metatable = LuaTable()
        val writes = mutableListOf<Pair<String, Long>>()
        receiver.metatable = metatable
        metatable.rawSet(NEW_INDEX, recordingNewIndex("first", writes))
        globals.rawSet(OBJECT, receiver)
        globals.rawSet(
            MUTATE,
            LuaNativeFunction { arguments ->
                when (arguments.single().integer()) {
                    1L -> metatable.rawSet(NEW_INDEX, recordingNewIndex("second", writes))
                    2L -> metatable.rawSet(NEW_INDEX, LuaNil)
                    3L -> metatable.rawSet(NEW_INDEX, recordingNewIndex("third", writes))
                    4L -> receiver.rawSet(VALUE, LuaNil)
                }
                emptyList()
            },
        )

        LuaVm(globals).execute(
            Compiler.compile(
                """
                for i = 1, 5 do
                    object.value = i
                    mutate(i)
                end
                """.trimIndent(),
                "inline-cache-field-write.lua",
            ),
        )

        assertEquals(listOf("first" to 1L, "second" to 2L, "third" to 5L), writes)
        assertEquals(LuaNil, receiver.rawGet(VALUE))
    }

    @Test
    fun `global caches follow value deletion and environment metamethod changes`() {
        val globals = LuaTable()
        val metatable = LuaTable()
        globals.metatable = metatable
        globals.rawSet(ANSWER, LuaInteger(1))
        globals.rawSet(
            MUTATE,
            LuaNativeFunction { arguments ->
                when (arguments.single().integer()) {
                    1L -> globals.rawSet(ANSWER, LuaInteger(2))
                    2L -> {
                        globals.rawSet(ANSWER, LuaNil)
                        metatable.rawSet(INDEX, constantFunction(30))
                    }
                    3L -> metatable.rawSet(INDEX, constantFunction(40))
                }
                emptyList()
            },
        )

        val result = LuaVm(globals).execute(
            Compiler.compile(
                """
                local result = {}
                for i = 1, 4 do
                    result[i] = answer
                    mutate(i)
                end
                return result[1], result[2], result[3], result[4]
                """.trimIndent(),
                "inline-cache-global-read.lua",
            ),
        )

        assertEquals(integers(1, 2, 30, 40), result)
    }

    @Test
    fun `global write cache follows newindex changes and existing raw keys`() {
        val globals = LuaTable()
        val metatable = LuaTable()
        val writes = mutableListOf<Pair<String, Long>>()
        globals.metatable = metatable
        metatable.rawSet(NEW_INDEX, recordingNewIndex("first", writes))
        globals.rawSet(
            MUTATE,
            LuaNativeFunction { arguments ->
                when (arguments.single().integer()) {
                    1L -> metatable.rawSet(NEW_INDEX, recordingNewIndex("second", writes))
                    2L -> metatable.rawSet(NEW_INDEX, LuaNil)
                    3L -> metatable.rawSet(NEW_INDEX, recordingNewIndex("third", writes))
                    4L -> globals.rawSet(ANSWER, LuaNil)
                }
                emptyList()
            },
        )

        LuaVm(globals).execute(
            Compiler.compile(
                """
                for i = 1, 5 do
                    answer = i
                    mutate(i)
                end
                """.trimIndent(),
                "inline-cache-global-write.lua",
            ),
        )

        assertEquals(listOf("first" to 1L, "second" to 2L, "third" to 5L), writes)
        assertEquals(LuaNil, globals.rawGet(ANSWER))
    }

    @Test
    fun `dynamic table caches follow chained index targets and metatable replacement`() {
        val globals = LuaTable()
        val source = LuaTable()
        val sink = LuaTable()
        val sourceMetatable = LuaTable()
        val sinkMetatable = LuaTable()
        val secondSourceMetatable = LuaTable()
        val secondSinkMetatable = LuaTable()
        val readTargets = (1L..3L).map { value ->
            LuaTable().also { target -> target.rawSet(VALUE, LuaInteger(value * 10)) }
        }
        val writeTargets = (1..3).map { LuaTable() }
        source.metatable = sourceMetatable
        sink.metatable = sinkMetatable
        sourceMetatable.rawSet(INDEX, readTargets[0])
        sinkMetatable.rawSet(NEW_INDEX, writeTargets[0])
        globals.rawSet(SOURCE, source)
        globals.rawSet(SINK, sink)
        globals.rawSet(
            MUTATE,
            LuaNativeFunction { arguments ->
                when (arguments.single().integer()) {
                    1L -> {
                        sourceMetatable.rawSet(INDEX, readTargets[1])
                        sinkMetatable.rawSet(NEW_INDEX, writeTargets[1])
                    }
                    2L -> {
                        secondSourceMetatable.rawSet(INDEX, readTargets[2])
                        secondSinkMetatable.rawSet(NEW_INDEX, writeTargets[2])
                        source.metatable = secondSourceMetatable
                        sink.metatable = secondSinkMetatable
                    }
                }
                emptyList()
            },
        )

        val result = LuaVm(globals).execute(
            Compiler.compile(
                """
                local result = {}
                local key = "value"
                for i = 1, 3 do
                    result[i] = source[key]
                    sink[key] = i
                    mutate(i)
                end
                return result[1], result[2], result[3]
                """.trimIndent(),
                "inline-cache-dynamic-table.lua",
            ),
        )

        assertEquals(integers(10, 20, 30), result)
        assertEquals(integers(1, 2, 3), writeTargets.map { it.rawGet(VALUE) })
        assertEquals(LuaNil, sink.rawGet(VALUE))
    }

    @Test
    fun `call cache follows call metamethod and metatable replacement`() {
        val globals = LuaTable()
        val callable = LuaTable()
        val firstMetatable = LuaTable()
        val secondMetatable = LuaTable()
        callable.metatable = firstMetatable
        firstMetatable.rawSet(CALL, offsetCallFunction(10))
        globals.rawSet(CALLABLE, callable)
        globals.rawSet(
            MUTATE,
            LuaNativeFunction { arguments ->
                when (arguments.single().integer()) {
                    1L -> firstMetatable.rawSet(CALL, offsetCallFunction(20))
                    2L -> {
                        secondMetatable.rawSet(CALL, offsetCallFunction(30))
                        callable.metatable = secondMetatable
                    }
                }
                emptyList()
            },
        )

        val result = LuaVm(globals).execute(
            Compiler.compile(
                """
                local result = {}
                for i = 1, 3 do
                    result[i] = callable(i)
                    mutate(i)
                end
                return result[1], result[2], result[3]
                """.trimIndent(),
                "inline-cache-call.lua",
            ),
        )

        assertEquals(integers(11, 22, 33), result)
    }

    @Test
    fun `metamethod lookup cache follows operator replacement`() {
        val globals = LuaTable()
        val receiver = LuaTable()
        val metatable = LuaTable()
        receiver.metatable = metatable
        metatable.rawSet(ADD, constantFunction(10))
        globals.rawSet(OBJECT, receiver)
        globals.rawSet(
            MUTATE,
            LuaNativeFunction { arguments ->
                when (arguments.single().integer()) {
                    1L -> metatable.rawSet(ADD, constantFunction(20))
                    2L -> metatable.rawSet(ADD, constantFunction(30))
                }
                emptyList()
            },
        )

        val result = LuaVm(globals).execute(
            Compiler.compile(
                """
                local result = {}
                for i = 1, 3 do
                    result[i] = object + 1
                    mutate(i)
                end
                return result[1], result[2], result[3]
                """.trimIndent(),
                "inline-cache-operator.lua",
            ),
        )

        assertEquals(integers(10, 20, 30), result)
    }

    private fun constantFunction(value: Long): LuaNativeFunction {
        return LuaNativeFunction { listOf(LuaInteger(value)) }
    }

    private fun recordingNewIndex(
        label: String,
        writes: MutableList<Pair<String, Long>>,
    ): LuaNativeFunction {
        return LuaNativeFunction { arguments ->
            writes += label to arguments[2].integer()
            emptyList()
        }
    }

    private fun offsetCallFunction(offset: Long): LuaNativeFunction {
        return LuaNativeFunction { arguments ->
            listOf(LuaInteger(offset + arguments[1].integer()))
        }
    }

    private fun LuaValue.integer(): Long = (this as LuaInteger).value

    private fun integers(vararg values: Long): List<LuaInteger> = values.map(::LuaInteger)

    private companion object {
        val ADD = LuaString("__add")
        val ANSWER = LuaString("answer")
        val CALL = LuaString("__call")
        val CALLABLE = LuaString("callable")
        val INDEX = LuaString("__index")
        val MUTATE = LuaString("mutate")
        val NEW_INDEX = LuaString("__newindex")
        val OBJECT = LuaString("object")
        val SINK = LuaString("sink")
        val SOURCE = LuaString("source")
        val VALUE = LuaString("value")
    }
}
