package io.github.realmlabs.klua.compat

import io.github.realmlabs.klua.api.LuaConfig
import io.github.realmlabs.klua.api.LuaVersion
import io.github.realmlabs.klua.api.LuaVersionProfile

object LuaProfiles {
    fun lua51(): LuaConfig = LuaConfig(LuaVersion.LUA_51)
    fun lua52(): LuaConfig = LuaConfig(LuaVersion.LUA_52)
    fun lua53(): LuaConfig = LuaConfig(LuaVersion.LUA_53)
    fun lua54(): LuaConfig = LuaConfig(LuaVersion.LUA_54)
    fun lua55(): LuaConfig = LuaConfig(LuaVersion.LUA_55)
    fun luajit21(): LuaConfig = LuaConfig(LuaVersion.LUAJIT_21)
    fun production(): LuaConfig = LuaConfig(version = LuaVersion.LUA_54, debugEnabled = false)
}

object Lua54Profile : LuaVersionProfile {
    override val version: LuaVersion = LuaVersion.LUA_54
}
