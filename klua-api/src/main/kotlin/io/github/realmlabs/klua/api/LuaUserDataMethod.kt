package io.github.realmlabs.klua.api

fun interface LuaUserDataMethod<T : Any> {
    fun call(receiver: T, context: LuaCallContext): LuaReturn
}
