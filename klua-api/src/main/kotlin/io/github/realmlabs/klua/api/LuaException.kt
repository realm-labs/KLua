package io.github.realmlabs.klua.api

open class LuaException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class LuaSyntaxException(
    message: String,
    cause: Throwable? = null,
) : LuaException(message, cause)

class LuaRuntimeException(
    message: String,
    cause: Throwable? = null,
) : LuaException(message, cause)
