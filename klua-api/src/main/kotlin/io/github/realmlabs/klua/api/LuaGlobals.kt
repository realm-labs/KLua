package io.github.realmlabs.klua.api

class LuaGlobals internal constructor(
    private val state: LuaState,
) {
    fun get(name: String): Any? {
        val base = state.getTop()
        return try {
            state.getGlobal(name)
            state.toAny(-1)
        } finally {
            state.setTop(base)
        }
    }

    fun set(name: String, value: Any?) {
        pushValue(value)
        state.setGlobal(name)
    }

    fun setFunction(name: String, function: LuaFunction) {
        state.register(name, function)
    }

    private fun pushValue(value: Any?) {
        when (value) {
            null -> state.pushNil()
            is Boolean -> state.pushBoolean(value)
            is Byte -> state.pushInteger(value.toLong())
            is Short -> state.pushInteger(value.toLong())
            is Int -> state.pushInteger(value.toLong())
            is Long -> state.pushInteger(value)
            is Float -> state.pushNumber(value.toDouble())
            is Double -> state.pushNumber(value)
            is CharSequence -> state.pushString(value.toString())
            is LuaFunction -> state.pushFunction(value)
            else -> state.pushUserData(value)
        }
    }
}
