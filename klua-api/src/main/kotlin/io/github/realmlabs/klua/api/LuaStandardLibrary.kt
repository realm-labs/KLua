package io.github.realmlabs.klua.api

enum class LuaStandardLibrary {
    BASE,
    MATH,
    STRING,
    TABLE,
    UTF8,
    COROUTINE,
    PACKAGE,
    OS,
    DEBUG,
    ;

    companion object {
        @JvmStatic
        fun all(): Set<LuaStandardLibrary> = entries.toSet()
    }
}
