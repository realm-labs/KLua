package io.github.realmlabs.klua.api

import java.util.function.Consumer

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

    @JvmOverloads
    fun compileBytecode(source: String, chunkName: String = "chunk"): ByteArray = state.compileBytecode(source, chunkName)

    fun loadBytecode(bytes: ByteArray): LuaChunk = LuaChunk.bytecode(state, bytes)

    fun globals(): LuaGlobals = globals

    fun <T : Any> registerType(type: Class<T>, configure: Consumer<LuaUserDataType<T>>) {
        state.registerType(type, configure)
    }
}
