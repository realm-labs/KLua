package io.github.realmlabs.klua.api

fun interface LuaUserDataSetter<T : Any> {
    fun set(receiver: T, value: Any?)
}
