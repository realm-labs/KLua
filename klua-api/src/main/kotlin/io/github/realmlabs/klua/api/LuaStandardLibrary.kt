package io.github.realmlabs.klua.api

import java.util.Collections

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
        fun all(): Set<LuaStandardLibrary> = Collections.unmodifiableSet(entries.toSet())
    }
}
