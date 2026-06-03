package io.github.realmlabs.klua.api

class LuaReturn private constructor(
    val values: List<Any?>,
) {
    fun getCount(): Int = values.size

    fun get(index: Int): Any? {
        require(index >= 1) { "return index must be positive: $index" }
        return values.getOrNull(index - 1)
    }

    companion object {
        @JvmStatic
        fun none(): LuaReturn = LuaReturn(emptyList())

        @JvmStatic
        fun of(vararg values: Any?): LuaReturn = LuaReturn(values.toList())

        @JvmStatic
        fun ofValues(values: List<Any?>): LuaReturn = LuaReturn(values.toList())
    }
}
