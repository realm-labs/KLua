package io.github.realmlabs.klua.api

data class LuaConfig(
    val version: LuaVersion = LuaVersion.LUA_54,
    val debugEnabled: Boolean = true,
)
