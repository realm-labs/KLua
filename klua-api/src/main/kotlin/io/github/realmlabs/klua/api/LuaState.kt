package io.github.realmlabs.klua.api

class LuaState private constructor(
    val config: LuaConfig,
) {
    companion object {
        @JvmStatic
        @JvmOverloads
        fun create(config: LuaConfig = LuaConfig()): LuaState = LuaState(config)
    }

    fun getTop(): Int = 0
}
