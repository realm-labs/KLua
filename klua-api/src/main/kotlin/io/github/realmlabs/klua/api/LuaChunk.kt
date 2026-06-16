package io.github.realmlabs.klua.api

class LuaChunk internal constructor(
    private val state: LuaState,
    val source: String,
    val chunkName: String,
    private val bytecode: ByteArray? = null,
) {
    internal companion object {
        fun bytecode(state: LuaState, bytes: ByteArray): LuaChunk {
            return LuaChunk(state, source = "", chunkName = "bytecode", bytecode = bytes.copyOf())
        }
    }

    fun eval(): LuaReturn {
        return call()
    }

    fun call(vararg arguments: Any?): LuaReturn {
        val base = state.getTop()
        try {
            val status = if (bytecode == null) {
                state.load(source, chunkName)
            } else {
                state.loadBytecode(bytecode.copyOf())
            }
            checkStatus(status)
            for (argument in arguments) {
                pushValue(argument)
            }
            checkStatus(state.pcall(arguments.size, -1))
            return LuaReturn.ofValues(readResults(base))
        } finally {
            state.setTop(base)
        }
    }

    fun evalLong(): Long = eval().getLong(1)

    fun evalDouble(): Double = eval().getDouble(1)

    fun evalString(): String = eval().getString(1)

    fun evalBoolean(): Boolean = eval().getBoolean(1)

    fun exec() {
        eval()
    }

    private fun readResults(base: Int): List<Any?> {
        return (base + 1..state.getTop()).map { index -> state.toAny(index) }
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

    private fun checkStatus(status: LuaStatus) {
        if (status == LuaStatus.OK) {
            return
        }
        throw state.getLastError() ?: LuaRuntimeException("Lua chunk failed with status $status")
    }
}
