package io.github.realmlabs.klua.examples

import io.github.realmlabs.klua.api.Lua
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.kotlin.function
import io.github.realmlabs.klua.kotlin.globals
import io.github.realmlabs.klua.kotlin.registerType
import io.github.realmlabs.klua.kotlin.set

public object KotlinEmbeddingExample {
    @JvmStatic
    public fun main(arguments: Array<String>) {
        println(evaluate())
    }

    public fun evaluate(): Long {
        val lua = Lua.create()
        val counter = Counter()

        lua.registerType<Counter> {
            property("value", getter = { value -> LuaReturn.of(value.count) })
            method("add") { value, context ->
                value.count += context.toInteger(1) ?: error("integer delta expected")
                LuaReturn.none()
            }
        }
        lua.globals["counter"] = counter
        lua.globals["twice"] = lua.function { value: Long -> value * 2 }

        return lua.load(
            "counter:add(20); return twice(counter.value + 1)",
            "kotlin-example.lua",
        ).evalLong()
    }

    public data class Counter(
        public var count: Long = 0,
    )
}
