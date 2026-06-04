package io.github.realmlabs.klua.api

interface LuaCoroutineFunction : LuaFunction {
    fun createCoroutine(): LuaCoroutineHandle
}
