package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaCoroutineFunction
import io.github.realmlabs.klua.api.LuaCoroutineHandle
import io.github.realmlabs.klua.api.LuaCoroutineResult
import io.github.realmlabs.klua.api.LuaDebuggableCoroutineHandle
import io.github.realmlabs.klua.api.LuaException
import io.github.realmlabs.klua.api.LuaFunction
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState
import io.github.realmlabs.klua.api.LuaStackFrame
import io.github.realmlabs.klua.api.LuaTypedValue
import io.github.realmlabs.klua.api.LuaYieldException
import io.github.realmlabs.klua.api.LuaYieldableFunction
import io.github.realmlabs.klua.api.continueWith
import io.github.realmlabs.klua.api.withContinuation

internal object LuaCoroutineLibrary {
    fun open(state: LuaState): LuaState {
        val runtime = CoroutineRuntime()
        state.newTable()
        setYieldableFunctionField(state, "close") { context -> coroutineClose(context, runtime) }
        setFunctionField(state, "create") { context -> coroutineCreate(context, "create") }
        setYieldableFunctionField(state, "isyieldable") { context -> coroutineIsYieldable(context, runtime) }
        setFunctionField(state, "resume") { context -> coroutineResume(context, runtime) }
        setFunctionField(state, "running") { coroutineRunning(runtime) }
        setFunctionField(state, "status") { context -> coroutineStatus(context, runtime) }
        setFunctionField(state, "wrap") { context -> coroutineWrap(context, runtime) }
        setYieldableFunctionField(state, "yield") { context -> coroutineYield(context, runtime) }
        state.setGlobal("coroutine")
        return state
    }

    private fun coroutineCreate(context: LuaCallContext, functionName: String): LuaReturn {
        if (context.typeName(1) != "function") {
            throw LuaRuntimeException("bad argument #1 to '$functionName' (function expected)")
        }
        val function = context.get(1) as? LuaFunction
            ?: throw LuaRuntimeException("bad argument #1 to '$functionName' (function expected)")
        return LuaReturn.of(LuaCoroutine(function, (function as? LuaCoroutineFunction)?.createCoroutine()))
    }

    private fun coroutineResume(context: LuaCallContext, runtime: CoroutineRuntime): LuaReturn {
        val coroutine = requiredCoroutine(context, 1, "resume")
        return resumeCoroutine(
            context,
            runtime,
            coroutine,
            (2..context.argumentCount).map { index -> argumentValue(context, index) },
        )
    }

    private fun resumeCoroutine(
        context: LuaCallContext,
        runtime: CoroutineRuntime,
        coroutine: LuaCoroutine,
        arguments: List<Any?>,
    ): LuaReturn {
        if (coroutine.status == CoroutineStatus.DEAD) {
            return LuaReturn.of(false, "cannot resume dead coroutine")
        }
        if (coroutine.status == CoroutineStatus.RUNNING) {
            return LuaReturn.of(false, "cannot resume non-suspended coroutine")
        }

        coroutine.status = CoroutineStatus.RUNNING
        val previousRunning = runtime.running
        runtime.running = coroutine
        return try {
            val handle = coroutine.handle
            if (handle != null) {
                when (val result = handle.resume(arguments)) {
                    is LuaCoroutineResult.Returned -> {
                        coroutine.status = CoroutineStatus.DEAD
                        LuaReturn.ofValues(listOf(true) + result.values)
                    }
                    is LuaCoroutineResult.Yielded -> {
                        if (result.values.singleOrNull() === SelfCloseSignal) {
                            coroutine.status = CoroutineStatus.DEAD
                            return LuaReturn.of(true)
                        }
                        coroutine.status = CoroutineStatus.SUSPENDED
                        LuaReturn.ofValues(listOf(true) + result.values)
                    }
                    is LuaCoroutineResult.RuntimeError -> {
                        val errorValue = result.errorValue()
                        coroutine.status = CoroutineStatus.DEAD
                        coroutine.rememberCloseError(errorValue)
                        LuaReturn.of(false, errorValue)
                    }
                }
            } else if (coroutine.pendingYield != null) {
                resumeHostYieldableCoroutine(coroutine, coroutine.pendingYield, arguments)
            } else {
                try {
                    val result = context.call(coroutine.function, arguments)
                    coroutine.status = CoroutineStatus.DEAD
                    LuaReturn.ofValues(listOf(true) + result.values)
                } catch (yield: LuaYieldException) {
                    if (coroutine.function !is LuaYieldableFunction) {
                        coroutine.status = CoroutineStatus.DEAD
                        val errorValue = "attempt to yield across a non-yieldable boundary"
                        coroutine.rememberCloseError(errorValue)
                        return LuaReturn.of(false, errorValue)
                    }
                    suspendHostYieldableCoroutine(coroutine, yield)
                }
            }
        } catch (exception: LuaException) {
            val errorValue = exception.errorValue()
            coroutine.status = CoroutineStatus.DEAD
            coroutine.rememberCloseError(errorValue)
            LuaReturn.of(false, errorValue)
        } catch (exception: RuntimeException) {
            val errorValue = exception.message ?: exception::class.java.simpleName
            coroutine.status = CoroutineStatus.DEAD
            coroutine.rememberCloseError(errorValue)
            LuaReturn.of(false, errorValue)
        } finally {
            runtime.running = previousRunning
        }
    }

    private fun resumeHostYieldableCoroutine(
        coroutine: LuaCoroutine,
        yield: LuaYieldException?,
        arguments: List<Any?>,
    ): LuaReturn {
        require(yield != null) { "host coroutine has no pending yield" }
        coroutine.pendingYield = null
        return try {
            val result = yield.continueWith(arguments)
            coroutine.status = CoroutineStatus.DEAD
            LuaReturn.ofValues(listOf(true) + result.values)
        } catch (nextYield: LuaYieldException) {
            suspendHostYieldableCoroutine(coroutine, nextYield)
        }
    }

    private fun suspendHostYieldableCoroutine(coroutine: LuaCoroutine, yield: LuaYieldException): LuaReturn {
        coroutine.pendingYield = yield.withContinuation { arguments ->
            yield.continueWith(arguments)
        }
        coroutine.status = CoroutineStatus.SUSPENDED
        return LuaReturn.ofValues(listOf(true) + yield.values)
    }

    private fun coroutineRunning(runtime: CoroutineRuntime): LuaReturn {
        val running = runtime.running
        return if (running == null) {
            LuaReturn.of(runtime.main, true)
        } else {
            LuaReturn.of(running, false)
        }
    }

    private fun coroutineYield(context: LuaCallContext, runtime: CoroutineRuntime): LuaReturn {
        if (runtime.running == null) {
            throw LuaRuntimeException("attempt to yield from outside a coroutine")
        }
        context.yield((1..context.argumentCount).map { index -> argumentValue(context, index) })
    }

    private fun coroutineClose(context: LuaCallContext, runtime: CoroutineRuntime): LuaReturn {
        val coroutine = optionalCurrentCoroutine(context, runtime, "close")
        if (coroutine.isMain) {
            if (isRunningCoroutine(coroutine, runtime)) {
                throw LuaRuntimeException("cannot close main thread")
            }
            throw LuaRuntimeException("cannot close a normal coroutine")
        }
        if (coroutine.hasCloseError) {
            val errorValue = coroutine.closeError
            coroutine.clearCloseError()
            coroutine.status = CoroutineStatus.DEAD
            return LuaReturn.of(false, errorValue)
        }
        if (coroutine.status == CoroutineStatus.RUNNING) {
            if (!isRunningCoroutine(coroutine, runtime)) {
                throw LuaRuntimeException("cannot close a normal coroutine")
            }
            coroutine.status = CoroutineStatus.DEAD
            context.yield(listOf(SelfCloseSignal))
        }
        coroutine.handle?.let { handle ->
            return closeCoroutineHandle(coroutine, handle)
        }
        if (coroutine.pendingYield != null) {
            return closeHostYieldableCoroutine(coroutine)
        }
        coroutine.status = CoroutineStatus.DEAD
        return LuaReturn.of(true)
    }

    private fun closeCoroutineHandle(coroutine: LuaCoroutine, handle: LuaCoroutineHandle): LuaReturn {
        coroutine.status = CoroutineStatus.DEAD
        return when (val result = handle.close()) {
            is LuaCoroutineResult.Returned -> LuaReturn.of(true)
            is LuaCoroutineResult.Yielded -> LuaReturn.of(false, "attempt to yield while closing coroutine")
            is LuaCoroutineResult.RuntimeError -> {
                LuaReturn.of(false, if (result.hasErrorObject) result.errorObject else result.message)
            }
        }
    }

    private fun closeHostYieldableCoroutine(coroutine: LuaCoroutine): LuaReturn {
        val yield = coroutine.pendingYield
            ?: return LuaReturn.of(true)
        coroutine.pendingYield = null
        coroutine.status = CoroutineStatus.DEAD
        return try {
            yield.continueWith(emptyList())
            LuaReturn.of(true)
        } catch (exception: LuaYieldException) {
            LuaReturn.of(false, "attempt to yield while closing coroutine")
        } catch (exception: LuaException) {
            LuaReturn.of(false, coroutineErrorObject(exception))
        } catch (exception: RuntimeException) {
            LuaReturn.of(false, exception.message ?: exception::class.java.simpleName)
        }
    }

    private fun coroutineIsYieldable(context: LuaCallContext, runtime: CoroutineRuntime): LuaReturn {
        if (context.isNone(1)) {
            return LuaReturn.of(runtime.running != null && context.isYieldable)
        }
        val coroutine = requiredCoroutine(context, 1, "isyieldable")
        if (coroutine.isMain) {
            return LuaReturn.of(false)
        }
        if (coroutine.status != CoroutineStatus.RUNNING) {
            return LuaReturn.of(true)
        }
        return LuaReturn.of(isRunningCoroutine(coroutine, runtime) && context.isYieldable)
    }

    private fun coroutineStatus(context: LuaCallContext, runtime: CoroutineRuntime): LuaReturn {
        val coroutine = requiredCoroutine(context, 1, "status")
        return LuaReturn.of(
            when (coroutine.status) {
                CoroutineStatus.SUSPENDED -> "suspended"
                CoroutineStatus.RUNNING -> if (isRunningCoroutine(coroutine, runtime)) "running" else "normal"
                CoroutineStatus.DEAD -> "dead"
            },
        )
    }

    private fun isRunningCoroutine(coroutine: LuaCoroutine, runtime: CoroutineRuntime): Boolean {
        return runtime.running == coroutine || coroutine.isMain && runtime.running == null
    }

    private fun coroutineWrap(context: LuaCallContext, runtime: CoroutineRuntime): LuaReturn {
        val created = coroutineCreate(context, "wrap").getUserData(1, LuaCoroutine::class.java)
        val creationFrames = context.luaFrames
        val wrapper = LuaFunction { wrapperContext ->
            val result = resumeCoroutine(
                wrapperContext,
                runtime,
                created,
                (1..wrapperContext.argumentCount).map { index ->
                    argumentValue(wrapperContext, index)
                },
            )
            if (result.get(1) != true) {
                throwCoroutineWrapError(wrapperContext, result.get(2), creationFrames)
            }
            LuaReturn.ofValues(result.values.drop(1))
        }
        return LuaReturn.of(wrapper)
    }

    private fun LuaCoroutineResult.RuntimeError.errorValue(): Any? {
        return if (hasErrorObject) errorObject else message
    }

    private fun LuaException.errorValue(): Any? {
        return if (this is LuaRuntimeException && hasErrorObject) {
            errorObject
        } else {
            message ?: this::class.java.simpleName
        }
    }

    private fun throwCoroutineWrapError(
        context: LuaCallContext,
        errorValue: Any?,
        fallbackFrames: List<LuaStackFrame>,
    ): Nothing {
        val message = errorValue?.toString() ?: "cannot resume coroutine"
        if (errorValue is CharSequence) {
            throw LuaRuntimeException(locationPrefixedWrapError(context, message, fallbackFrames))
        }
        throw LuaRuntimeException(
            context.valueTypeName(errorValue),
            errorObject = errorValue,
            hasErrorObject = true,
        )
    }

    private fun locationPrefixedWrapError(
        context: LuaCallContext,
        message: String,
        fallbackFrames: List<LuaStackFrame>,
    ): String {
        val frame = context.luaFrames.firstOrNull { it.line > 0 }
            ?: fallbackFrames.firstOrNull { it.line > 0 }
            ?: context.luaFrames.firstOrNull()
            ?: fallbackFrames.firstOrNull()
            ?: return message
        return buildString {
            append(luaShortSourceName(frame.sourceName))
            if (frame.line > 0) {
                append(':')
                append(frame.line)
            }
            append(": ")
            append(message)
        }
    }

    private fun requiredCoroutine(
        context: LuaCallContext,
        index: Int,
        functionName: String,
    ): LuaCoroutine {
        return context.toUserData(index, LuaCoroutine::class.java)
            ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (thread expected)")
    }

    private fun optionalCurrentCoroutine(
        context: LuaCallContext,
        runtime: CoroutineRuntime,
        functionName: String,
    ): LuaCoroutine {
        return if (context.isNone(1)) {
            runtime.running ?: runtime.main
        } else {
            requiredCoroutine(context, 1, functionName)
        }
    }

    private fun argumentValue(context: LuaCallContext, index: Int): Any? {
        return if (context.typeName(index) == "table") {
            context.getTable(index)
        } else {
            context.get(index)
        }
    }

    private fun setFunctionField(state: LuaState, name: String, function: (LuaCallContext) -> LuaReturn) {
        state.pushFunction(function)
        state.setField(-2, name)
    }

    private fun setYieldableFunctionField(state: LuaState, name: String, function: (LuaCallContext) -> LuaReturn) {
        state.pushFunction(LuaYieldableFunction { context -> function(context) })
        state.setField(-2, name)
    }

    private class CoroutineRuntime {
        val main: LuaCoroutine = LuaCoroutine(
            function = LuaFunction { LuaReturn.of() },
            status = CoroutineStatus.RUNNING,
            isMain = true,
        )
        var running: LuaCoroutine? = null
    }

    private class LuaCoroutine(
        val function: LuaFunction,
        val handle: LuaCoroutineHandle? = null,
        var status: CoroutineStatus = CoroutineStatus.SUSPENDED,
        var pendingYield: LuaYieldException? = null,
        val isMain: Boolean = false,
        var closeError: Any? = null,
        var hasCloseError: Boolean = false,
    ) : LuaTypedValue, LuaDebugThread {
        override val luaTypeName: String = "thread"

        override val luaFrames: List<io.github.realmlabs.klua.api.LuaStackFrame>
            get() = (handle as? LuaDebuggableCoroutineHandle)?.luaFrames ?: emptyList()

        override fun setLocal(level: Int, index: Int, value: Any?): String? {
            return (handle as? LuaDebuggableCoroutineHandle)?.setLocal(level, index, value)
        }

        override fun setDebugHook(function: Any?, mask: String, count: Int): Boolean {
            return (handle as? LuaDebuggableCoroutineHandle)?.setDebugHook(function, mask, count) ?: false
        }

        override fun getDebugHook(): LuaReturn {
            return (handle as? LuaDebuggableCoroutineHandle)?.getDebugHook() ?: LuaReturn.of(null)
        }

        fun rememberCloseError(errorValue: Any?) {
            closeError = errorValue
            hasCloseError = true
        }

        fun clearCloseError() {
            closeError = null
            hasCloseError = false
        }
    }

    private enum class CoroutineStatus {
        SUSPENDED,
        RUNNING,
        DEAD,
    }

    private object SelfCloseSignal
}
