package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.bytecode.CallSiteInfo
import io.github.realmlabs.klua.core.bytecode.Prototype
import io.github.realmlabs.klua.core.value.LuaClosure
import io.github.realmlabs.klua.core.value.LuaNil
import io.github.realmlabs.klua.core.value.LuaTable
import io.github.realmlabs.klua.core.value.LuaUpvalue
import io.github.realmlabs.klua.core.value.LuaValue

internal class LuaThread {
    private val frames = mutableListOf<CallFrame>()
    private var nativeCallDepth = 0
    private var nonYieldableCallDepth = 0

    val currentFrame: CallFrame?
        get() = frames.lastOrNull()

    val callDepth: Int
        get() = frames.size

    val inNativeCall: Boolean
        get() = nativeCallDepth > 0

    val isYieldable: Boolean
        get() = nonYieldableCallDepth == 0

    fun pushCall(
        prototype: Prototype,
        arguments: List<LuaValue>,
        upvalues: List<LuaUpvalue> = emptyList(),
        environment: LuaUpvalue = LuaUpvalue(LuaTable()),
        function: LuaValue = LuaClosure(prototype, upvalues.toMutableList(), environment = environment),
        callSiteInfo: CallSiteInfo? = null,
    ): CallFrame {
        val stack = LuaStack(prototype.maxStackSize.coerceAtLeast(arguments.size))
        for (index in 0 until prototype.numParams) {
            stack.set(index, arguments.getOrElse(index) { LuaNil })
        }
        val varargs = if (prototype.isVararg) {
            copyVarargs(arguments, prototype.numParams)
        } else {
            mutableListOf()
        }
        val frame = CallFrame(
            prototype,
            function,
            stack,
            varargs,
            upvalues,
            environment,
            callSiteInfo,
        )
        pushFrame(frame)
        return frame
    }

    private fun copyVarargs(arguments: List<LuaValue>, firstVararg: Int): MutableList<LuaValue> {
        val varargs = ArrayList<LuaValue>((arguments.size - firstVararg).coerceAtLeast(0))
        for (index in firstVararg until arguments.size) {
            varargs += arguments[index]
        }
        return varargs
    }

    private fun pushFrame(frame: CallFrame) {
        frames += frame
    }

    fun popFrame(frame: CallFrame) {
        require(frames.lastOrNull() === frame) { "call frame stack is out of sync" }
        frames.removeAt(frames.lastIndex)
    }

    fun stackFrames(): List<CallFrame> {
        return frames.asReversed().toList()
    }

    fun clearFrames() {
        frames.clear()
    }

    fun <T> runNativeCall(block: () -> T): T {
        nativeCallDepth += 1
        return try {
            block()
        } finally {
            nativeCallDepth -= 1
        }
    }

    fun <T> runNonYieldableCall(block: () -> T): T {
        nonYieldableCallDepth += 1
        return try {
            block()
        } finally {
            nonYieldableCallDepth -= 1
        }
    }
}
