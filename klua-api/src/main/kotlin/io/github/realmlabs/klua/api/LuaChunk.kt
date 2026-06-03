package io.github.realmlabs.klua.api

class LuaChunk internal constructor(
    private val state: LuaState,
    val source: String,
    val chunkName: String,
) {
    fun eval(): LuaReturn {
        val base = state.getTop()
        try {
            checkStatus(state.load(source, chunkName))
            checkStatus(state.pcall(0, -1))
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

    private fun checkStatus(status: LuaStatus) {
        if (status == LuaStatus.OK) {
            return
        }
        throw state.getLastError() ?: LuaRuntimeException("Lua chunk failed with status $status")
    }
}
