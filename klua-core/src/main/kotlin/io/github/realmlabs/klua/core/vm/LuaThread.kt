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
        isTailCall: Boolean = false,
        extraArgumentCount: Int = 0,
    ): CallFrame {
        val framePrototype = function.prototype
        val varargCount = if (framePrototype.isVararg) {
            (arguments.size - framePrototype.numParams).coerceAtLeast(0)
        } else {
            0
        }
        val frame = createAndPushFrame(
            function,
            framePrototype.maxStackSize.coerceAtLeast(arguments.size),
            varargCount,
            environment,
            callSiteInfo,
            isTailCall,
            extraArgumentCount,
        )
        for (index in 0 until framePrototype.numParams) {
            frame.set(index, arguments.getOrElse(index) { LuaNil })
        }
        for (index in 0 until varargCount) {
            check(frame.setVararg(index, arguments[framePrototype.numParams + index]))
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
        isTailCall: Boolean = false,
        extraArgumentCount: Int = 0,
    ): CallFrame {
        require(argumentCount >= 0) { "argument count must be non-negative" }
        val prototype = function.prototype
        val varargCount = if (prototype.isVararg) {
            (argumentCount - prototype.numParams).coerceAtLeast(0)
        } else {
            0
        }
        val frame = createAndPushFrame(
            function,
            prototype.maxStackSize.coerceAtLeast(argumentCount),
            varargCount,
            environment,
            callSiteInfo,
            isTailCall,
            extraArgumentCount,
        )
        for (index in 0 until prototype.numParams) {
            if (index < argumentCount) {
                frame.copyFrom(sourceStack, argumentStart + index, index)
            } else {
                frame.setNil(index)
            }
        }
        for (index in 0 until varargCount) {
            check(frame.copyVarargFrom(index, sourceStack, argumentStart + prototype.numParams + index))
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
        isTailCall: Boolean = false,
        extraArgumentCount: Int = 0,
    ): CallFrame {
        require(argumentCount in 2..3) { "fixed call argument count must be two or three" }
        val prototype = function.prototype
        val varargCount = if (prototype.isVararg) {
            (argumentCount - prototype.numParams).coerceAtLeast(0)
        } else {
            0
        }
        val frame = createAndPushFrame(
            function,
            prototype.maxStackSize.coerceAtLeast(argumentCount),
            varargCount,
            environment,
            callSiteInfo,
            isTailCall,
            extraArgumentCount,
        )
        for (index in 0 until prototype.numParams) {
            val value = if (index < argumentCount) {
                fixedArgument(index, firstArgument, secondArgument, thirdArgument)
            } else {
                LuaNil
            }
            frame.set(index, value)
        }
        for (index in 0 until varargCount) {
            check(
                frame.setVararg(
                    index,
                    fixedArgument(prototype.numParams + index, firstArgument, secondArgument, thirdArgument),
                ),
            )
        }
        return frame
    }

    private fun createAndPushFrame(
        function: LuaClosure,
        stackSize: Int,
        varargCount: Int,
        environment: LuaUpvalue,
        callSiteInfo: CallSiteInfo?,
        isTailCall: Boolean,
        extraArgumentCount: Int,
    ): CallFrame {
        val frame = CallFrame(
            function,
            stackSize,
            varargCount,
            environment,
            callSiteInfo,
            isTailCall,
            extraArgumentCount,
        )
        pushFrame(frame)
        return frame
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

    fun lifecycleRoots(): List<LuaValue> = buildList {
        for (frame in frames) {
            add(frame.function)
            frame.environment.forEachHeapValue(::add)
            frame.forEachHeapValue(::add)
            frame.forEachVarargHeapValue(::add)
            frame.protectedErrorHandler?.let(::add)
        }
    }

    fun clearFrames() {
        frames.clear()
    }

    fun discardFramesAbove(frame: CallFrame) {
        require(frame in frames) { "call frame is not active" }
        while (frames.lastOrNull() !== frame) {
            frames.removeAt(frames.lastIndex)
        }
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
