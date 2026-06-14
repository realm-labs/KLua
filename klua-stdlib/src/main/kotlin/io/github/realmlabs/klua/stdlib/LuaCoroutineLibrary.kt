package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaCoroutineFunction
import io.github.realmlabs.klua.api.LuaCoroutineHandle
import io.github.realmlabs.klua.api.LuaCoroutineResult
import io.github.realmlabs.klua.api.LuaException
import io.github.realmlabs.klua.api.LuaFunction
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState
import io.github.realmlabs.klua.api.LuaTypedValue
import io.github.realmlabs.klua.api.LuaYieldException
import io.github.realmlabs.klua.api.LuaYieldableFunction
import io.github.realmlabs.klua.api.continueWith
import io.github.realmlabs.klua.api.withContinuation

internal object LuaCoroutineLibrary {
    fun open(state: LuaState): LuaState {
        val runtime = CoroutineRuntime()
        state.newTable()
        setFunctionField(state, "close") { context -> coroutineClose(context, runtime) }
        setFunctionField(state, "create", ::coroutineCreate)
        setFunctionField(state, "isyieldable") { context -> coroutineIsYieldable(context, runtime) }
        setFunctionField(state, "resume") { context -> coroutineResume(context, runtime) }
        setFunctionField(state, "running") { coroutineRunning(runtime) }
        setFunctionField(state, "status") { context -> coroutineStatus(context, runtime) }
        setFunctionField(state, "wrap") { context -> coroutineWrap(context, runtime) }
        setYieldableFunctionField(state, "yield") { context -> coroutineYield(context, runtime) }
        state.setGlobal("coroutine")
        return state
    }

    private fun coroutineCreate(context: LuaCallContext): LuaReturn {
        if (context.typeName(1) != "function") {
            throw LuaRuntimeException("bad argument #1 to 'coroutine.create' (function expected)")
        }
        val function = context.get(1) as? LuaFunction
            ?: throw LuaRuntimeException("bad argument #1 to 'coroutine.create' (function expected)")
        return LuaReturn.of(LuaCoroutine(function, (function as? LuaCoroutineFunction)?.createCoroutine()))
    }

    private fun coroutineResume(context: LuaCallContext, runtime: CoroutineRuntime): LuaReturn {
        val coroutine = requiredCoroutine(context, 1, "coroutine.resume")
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
                        coroutine.status = CoroutineStatus.SUSPENDED
                        LuaReturn.ofValues(listOf(true) + result.values)
                    }
                    is LuaCoroutineResult.RuntimeError -> {
                        coroutine.status = CoroutineStatus.DEAD
                        LuaReturn.of(false, result.errorValue())
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
                        return LuaReturn.of(false, "attempt to yield across a non-yieldable boundary")
                    }
                    suspendHostYieldableCoroutine(coroutine, yield)
                }
            }
        } catch (exception: LuaException) {
            coroutine.status = CoroutineStatus.DEAD
            LuaReturn.of(false, exception.errorValue())
        } catch (exception: RuntimeException) {
            coroutine.status = CoroutineStatus.DEAD
            LuaReturn.of(false, exception.message ?: exception::class.java.simpleName)
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
        val coroutine = optionalCurrentCoroutine(context, runtime, "coroutine.close")
        if (coroutine.isMain) {
            if (isRunningCoroutine(coroutine, runtime)) {
                throw LuaRuntimeException("cannot close main thread")
            }
            throw LuaRuntimeException("cannot close a normal coroutine")
        }
        if (coroutine.status == CoroutineStatus.RUNNING) {
            if (!isRunningCoroutine(coroutine, runtime)) {
                throw LuaRuntimeException("cannot close a normal coroutine")
            }
            return LuaReturn.of(false, "cannot close running coroutine")
        }
        coroutine.status = CoroutineStatus.DEAD
        return LuaReturn.of(true)
    }

    private fun coroutineIsYieldable(context: LuaCallContext, runtime: CoroutineRuntime): LuaReturn {
        if (context.isNone(1)) {
            return LuaReturn.of(runtime.running != null)
        }
        val coroutine = requiredCoroutine(context, 1, "coroutine.isyieldable")
        return LuaReturn.of(!coroutine.isMain)
    }

    private fun coroutineStatus(context: LuaCallContext, runtime: CoroutineRuntime): LuaReturn {
        val coroutine = requiredCoroutine(context, 1, "coroutine.status")
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
        val created = coroutineCreate(context).getUserData(1, LuaCoroutine::class.java)
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
                throwCoroutineWrapError(wrapperContext, result.get(2))
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

    private fun throwCoroutineWrapError(context: LuaCallContext, errorValue: Any?): Nothing {
        val message = errorValue?.toString() ?: "cannot resume coroutine"
        if (errorValue is CharSequence) {
            throw LuaRuntimeException(message)
        }
        throw LuaRuntimeException(
            context.valueTypeName(errorValue),
            errorObject = errorValue,
            hasErrorObject = true,
        )
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
    ) : LuaTypedValue {
        override val luaTypeName: String = "thread"
    }

    private enum class CoroutineStatus {
        SUSPENDED,
        RUNNING,
        DEAD,
    }
}
