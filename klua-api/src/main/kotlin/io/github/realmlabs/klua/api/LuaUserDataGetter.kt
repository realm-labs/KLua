package io.github.realmlabs.klua.api

fun interface LuaUserDataGetter<T : Any> {
    fun get(receiver: T): LuaReturn
}
