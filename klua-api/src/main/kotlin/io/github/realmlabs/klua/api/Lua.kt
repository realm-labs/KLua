package io.github.realmlabs.klua.api

class Lua private constructor(
    val config: LuaConfig,
    private val state: LuaState,
) {
    private val globals = LuaGlobals(state)

    companion object {
        @JvmStatic
        @JvmOverloads
        fun create(config: LuaConfig = LuaConfig()): Lua = Lua(config, LuaState.create(config))
    }

    @JvmOverloads
    fun load(source: String, chunkName: String = "chunk"): LuaChunk = LuaChunk(state, source, chunkName)

    fun globals(): LuaGlobals = globals
}
