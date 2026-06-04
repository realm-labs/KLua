package io.github.realmlabs.klua.api

interface LuaCoroutineHandle {
    fun resume(arguments: List<Any?>): LuaCoroutineResult
}
