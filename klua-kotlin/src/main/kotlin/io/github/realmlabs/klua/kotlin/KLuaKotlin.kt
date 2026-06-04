package io.github.realmlabs.klua.kotlin

import io.github.realmlabs.klua.api.Lua
import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaChunk
import io.github.realmlabs.klua.api.LuaFunction
import io.github.realmlabs.klua.api.LuaGlobals
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaUserDataType
import kotlin.jvm.JvmName

fun Lua.Companion.createDefault(): Lua = create()

val Lua.globals: LuaGlobals
    get() = globals()

operator fun LuaGlobals.get(name: String): Any? = get(name)

operator fun LuaGlobals.set(name: String, value: Any?) {
    set(name, value)
}

fun LuaGlobals.function(name: String, function: LuaFunction) {
    setFunction(name, function)
}

inline fun <reified T : Any> Lua.registerType(noinline configure: LuaUserDataType<T>.() -> Unit) {
    registerType(T::class.java) { type -> type.configure() }
}

operator fun LuaChunk.invoke(vararg arguments: Any?): LuaReturn {
    return call(*arguments)
}

inline fun <reified T : Any> LuaReturn.getUserData(index: Int): T {
    return getUserData(index, T::class.java)
}

fun Lua.functionContext(block: LuaCallContext.() -> LuaReturn): LuaFunction {
    return LuaFunction { context -> context.block() }
}

@JvmName("luaFunction0")
fun Lua.function(block: () -> Any?): LuaFunction {
    return LuaFunction { LuaReturn.of(block()) }
}

@JvmName("luaFunction1Long")
fun Lua.function(block: (Long) -> Any?): LuaFunction {
    return LuaFunction { context -> LuaReturn.of(block(context.requireLong(1))) }
}

@JvmName("luaFunction2Long")
fun Lua.function(block: (Long, Long) -> Any?): LuaFunction {
    return LuaFunction { context -> LuaReturn.of(block(context.requireLong(1), context.requireLong(2))) }
}

private fun LuaCallContext.requireLong(index: Int): Long {
    return toInteger(index)
        ?: throw IllegalArgumentException("argument $index is not an integer")
}
