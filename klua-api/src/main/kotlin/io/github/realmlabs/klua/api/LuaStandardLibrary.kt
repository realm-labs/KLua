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
    IO,
    OS,
    DEBUG,
    ;

    companion object {
        @JvmStatic
        fun all(): Set<LuaStandardLibrary> = Collections.unmodifiableSet(entries.toSet())

        @JvmStatic
        fun safe(): Set<LuaStandardLibrary> = SAFE_LIBRARIES

        private val SAFE_LIBRARIES: Set<LuaStandardLibrary> = Collections.unmodifiableSet(
            setOf(BASE, MATH, STRING, TABLE, UTF8, COROUTINE),
        )
    }
}
