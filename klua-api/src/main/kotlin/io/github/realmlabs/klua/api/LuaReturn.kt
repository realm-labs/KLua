package io.github.realmlabs.klua.api

class LuaReturn private constructor(
    val values: List<Any?>,
) {
    companion object {
        @JvmStatic
        fun none(): LuaReturn = LuaReturn(emptyList())

        @JvmStatic
        fun of(value: Any?): LuaReturn = LuaReturn(listOf(value))
    }
}
