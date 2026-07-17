package io.github.realmlabs.klua.consumer

import io.github.realmlabs.klua.api.Lua
import io.github.realmlabs.klua.kotlin.function
import io.github.realmlabs.klua.kotlin.globals
import io.github.realmlabs.klua.kotlin.set

public object KotlinEmbeddingSmoke {
    @JvmStatic
    public fun main(arguments: Array<String>) {
        val lua = Lua.create()
        lua.globals["base"] = 40L
        lua.globals["plusTwo"] = lua.function { value: Long -> value + 2 }
        val result = lua.load("return plusTwo(base)", "consumer-kotlin.lua").evalLong()
        check(result == 42L) { "expected Kotlin embedding result 42, got $result" }
        println("kotlin=42")
    }
}
