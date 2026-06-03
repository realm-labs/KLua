package io.github.realmlabs.klua.api

fun interface LuaFunction {
    fun call(context: LuaCallContext): LuaReturn
}
