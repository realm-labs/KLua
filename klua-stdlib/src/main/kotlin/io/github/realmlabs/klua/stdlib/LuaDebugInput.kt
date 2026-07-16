package io.github.realmlabs.klua.stdlib

/** Supplies one logical `debug.debug` command line, or `null` at EOF. */
public fun interface LuaDebugInput {
    public fun readLine(): String?
}
