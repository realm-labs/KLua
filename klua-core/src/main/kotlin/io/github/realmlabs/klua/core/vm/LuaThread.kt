package io.github.realmlabs.klua.core.vm

internal class LuaThread {
    private val frames = mutableListOf<CallFrame>()

    val currentFrame: CallFrame?
        get() = frames.lastOrNull()

    val callDepth: Int
        get() = frames.size

    fun pushFrame(frame: CallFrame) {
        frames += frame
    }

    fun popFrame(frame: CallFrame) {
        require(frames.lastOrNull() === frame) { "call frame stack is out of sync" }
        frames.removeAt(frames.lastIndex)
    }
}
