package io.github.realmlabs.klua.core.vm

internal class LuaVmException(
    message: String,
    val sourceName: String? = null,
    val line: Int? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    fun withLocation(sourceName: String, line: Int): LuaVmException {
        if (this.sourceName != null || line <= 0) {
            return this
        }
        return LuaVmException(message ?: "runtime error", sourceName, line, this)
    }
}
