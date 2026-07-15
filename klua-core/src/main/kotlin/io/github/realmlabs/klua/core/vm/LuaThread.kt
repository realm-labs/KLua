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
        function: LuaClosure = LuaClosure(prototype, upvalues.toMutableList(), environment = environment),
        callSiteInfo: CallSiteInfo? = null,
    ): CallFrame {
        val framePrototype = function.prototype
        val varargs: MutableList<LuaValue>? = if (framePrototype.isVararg) {
            copyVarargs(arguments, framePrototype.numParams)
        } else {
            null
        }
        val frame = createAndPushFrame(
            function,
            framePrototype.maxStackSize.coerceAtLeast(arguments.size),
            varargs,
            environment,
            callSiteInfo,
        )
        for (index in 0 until framePrototype.numParams) {
            frame.set(index, arguments.getOrElse(index) { LuaNil })
        }
        return frame
    }

    fun pushCallFromStack(
        sourceStack: LuaStack,
        argumentStart: Int,
        argumentCount: Int,
        environment: LuaUpvalue,
        function: LuaClosure,
        callSiteInfo: CallSiteInfo? = null,
    ): CallFrame {
        require(argumentCount >= 0) { "argument count must be non-negative" }
        val prototype = function.prototype
        val varargs: MutableList<LuaValue>? = if (prototype.isVararg) {
            copyVarargs(sourceStack, argumentStart, argumentCount, prototype.numParams)
        } else {
            null
        }
        val frame = createAndPushFrame(
            function,
            prototype.maxStackSize.coerceAtLeast(argumentCount),
            varargs,
            environment,
            callSiteInfo,
        )
        for (index in 0 until prototype.numParams) {
            val value = if (index < argumentCount) sourceStack.get(argumentStart + index) else LuaNil
            frame.set(index, value)
        }
        return frame
    }

    fun pushFixedCall(
        function: LuaClosure,
        argumentCount: Int,
        firstArgument: LuaValue,
        secondArgument: LuaValue,
        environment: LuaUpvalue,
        thirdArgument: LuaValue = LuaNil,
        callSiteInfo: CallSiteInfo? = null,
    ): CallFrame {
        require(argumentCount in 2..3) { "fixed call argument count must be two or three" }
        val prototype = function.prototype
        val varargs: MutableList<LuaValue>? = if (prototype.isVararg) {
            copyFixedVarargs(
                prototype.numParams,
                argumentCount,
                firstArgument,
                secondArgument,
                thirdArgument,
            )
        } else {
            null
        }
        val frame = createAndPushFrame(
            function,
            prototype.maxStackSize.coerceAtLeast(argumentCount),
            varargs,
            environment,
            callSiteInfo,
        )
        for (index in 0 until prototype.numParams) {
            val value = if (index < argumentCount) {
                fixedArgument(index, firstArgument, secondArgument, thirdArgument)
            } else {
                LuaNil
            }
            frame.set(index, value)
        }
        return frame
    }

    private fun createAndPushFrame(
        function: LuaClosure,
        stackSize: Int,
        varargs: MutableList<LuaValue>?,
        environment: LuaUpvalue,
        callSiteInfo: CallSiteInfo?,
    ): CallFrame {
        val frame = CallFrame(
            function,
            stackSize,
            varargs,
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

    private fun copyVarargs(
        sourceStack: LuaStack,
        argumentStart: Int,
        argumentCount: Int,
        firstVararg: Int,
    ): MutableList<LuaValue> {
        val varargs = ArrayList<LuaValue>((argumentCount - firstVararg).coerceAtLeast(0))
        for (index in firstVararg until argumentCount) {
            varargs += sourceStack.get(argumentStart + index)
        }
        return varargs
    }

    private fun copyFixedVarargs(
        firstVararg: Int,
        argumentCount: Int,
        firstArgument: LuaValue,
        secondArgument: LuaValue,
        thirdArgument: LuaValue,
    ): MutableList<LuaValue> {
        val varargs = ArrayList<LuaValue>((argumentCount - firstVararg).coerceAtLeast(0))
        for (index in firstVararg until argumentCount) {
            varargs += fixedArgument(index, firstArgument, secondArgument, thirdArgument)
        }
        return varargs
    }

    private fun fixedArgument(
        index: Int,
        firstArgument: LuaValue,
        secondArgument: LuaValue,
        thirdArgument: LuaValue,
    ): LuaValue {
        return when (index) {
            0 -> firstArgument
            1 -> secondArgument
            2 -> thirdArgument
            else -> LuaNil
        }
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
