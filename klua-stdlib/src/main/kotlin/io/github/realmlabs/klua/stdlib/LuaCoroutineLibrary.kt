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
import io.github.realmlabs.klua.api.LuaYieldException
import io.github.realmlabs.klua.api.LuaYieldableFunction
import io.github.realmlabs.klua.api.continueWith
import io.github.realmlabs.klua.api.withContinuation

internal object LuaCoroutineLibrary {
    fun open(state: LuaState): LuaState {
        val runtime = CoroutineRuntime()
        state.newTable()
        setFunctionField(state, "close", ::coroutineClose)
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
                        LuaReturn.of(false, result.message)
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
            LuaReturn.of(false, exception.message ?: exception::class.java.simpleName)
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
            LuaReturn.of(null, true)
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

    private fun coroutineClose(context: LuaCallContext): LuaReturn {
        val coroutine = requiredCoroutine(context, 1, "coroutine.close")
        if (coroutine.status == CoroutineStatus.RUNNING) {
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
        return LuaReturn.of(coroutine.status != CoroutineStatus.DEAD)
    }

    private fun coroutineStatus(context: LuaCallContext, runtime: CoroutineRuntime): LuaReturn {
        val coroutine = requiredCoroutine(context, 1, "coroutine.status")
        return LuaReturn.of(
            when (coroutine.status) {
                CoroutineStatus.SUSPENDED -> "suspended"
                CoroutineStatus.RUNNING -> if (runtime.running == coroutine) "running" else "normal"
                CoroutineStatus.DEAD -> "dead"
            },
        )
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
                throw LuaRuntimeException(result.get(2)?.toString() ?: "cannot resume coroutine")
            }
            LuaReturn.ofValues(result.values.drop(1))
        }
        return LuaReturn.of(wrapper)
    }

    private fun requiredCoroutine(
        context: LuaCallContext,
        index: Int,
        functionName: String,
    ): LuaCoroutine {
        return context.toUserData(index, LuaCoroutine::class.java)
            ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (thread expected)")
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
        var running: LuaCoroutine? = null
    }

    private class LuaCoroutine(
        val function: LuaFunction,
        val handle: LuaCoroutineHandle? = null,
        var status: CoroutineStatus = CoroutineStatus.SUSPENDED,
        var pendingYield: LuaYieldException? = null,
    )

    private enum class CoroutineStatus {
        SUSPENDED,
        RUNNING,
        DEAD,
    }
}
