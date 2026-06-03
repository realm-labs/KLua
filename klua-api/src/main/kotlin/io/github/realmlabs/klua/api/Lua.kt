package io.github.realmlabs.klua.api

class Lua private constructor(
    val config: LuaConfig,
) {
    companion object {
        @JvmStatic
        @JvmOverloads
        fun create(config: LuaConfig = LuaConfig()): Lua = Lua(config)
    }

    fun load(source: String, chunkName: String = "chunk"): LuaChunk = LuaChunk(source, chunkName)
}
