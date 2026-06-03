package io.github.realmlabs.klua.api

open class LuaException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
