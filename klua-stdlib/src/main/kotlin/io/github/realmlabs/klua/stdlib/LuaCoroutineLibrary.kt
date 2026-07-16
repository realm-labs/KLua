package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaCoroutineFunction
import io.github.realmlabs.klua.api.LuaCoroutineHandle
import io.github.realmlabs.klua.api.LuaCoroutineResult
import io.github.realmlabs.klua.api.LuaDebugThread
import io.github.realmlabs.klua.api.LuaDebuggableCoroutineHandle
import io.github.realmlabs.klua.api.LuaException
import io.github.realmlabs.klua.api.LuaFunction
import io.github.realmlabs.klua.api.LuaMainThread
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState
import io.github.realmlabs.klua.api.LuaStackFrame
import io.github.realmlabs.klua.api.LuaTypedValue
import io.github.realmlabs.klua.api.LuaYieldException
import io.github.realmlabs.klua.api.LuaYieldableFunction
import io.github.realmlabs.klua.api.continueWith
import io.github.realmlabs.klua.api.withContinuation
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

internal object LuaCoroutineLibrary {
    private val libraryStates = Collections.synchronizedMap(WeakHashMap<LuaState, CoroutineLibraryState>())

    fun open(state: LuaState): LuaState {
        state.pushRegistryInteger(MAIN_THREAD_REGISTRY_INDEX)
        val main = state.toUserData(-1, LuaMainThread::class.java)
        state.pop()
        checkNotNull(main) { "LuaState registry main thread is unavailable" }
        val libraryState = synchronized(libraryStates) {
            libraryStates.getOrPut(state) {
                CoroutineLibraryState(main, WeakReference(state))
            }
        }
        state.newTable()
        setFunctionField(state, "close", libraryState.closeFunction)
        setFunctionField(state, "create", libraryState.createFunction)
        setFunctionField(state, "isyieldable", libraryState.isYieldableFunction)
        setFunctionField(state, "resume", libraryState.resumeFunction)
        setFunctionField(state, "running", libraryState.runningFunction)
        setFunctionField(state, "status", libraryState.statusFunction)
        setFunctionField(state, "wrap", libraryState.wrapFunction)
        setFunctionField(state, "yield", libraryState.yieldFunction)
        state.setGlobal("coroutine")
        return state
    }

    private fun coroutineCreate(context: LuaCallContext, functionName: String, runtime: CoroutineRuntime): LuaReturn {
        if (context.typeName(1) != "function") {
            throw LuaRuntimeException("bad argument #1 to '$functionName' (function expected)")
        }
        val function = context.get(1) as? LuaFunction
            ?: throw LuaRuntimeException("bad argument #1 to '$functionName' (function expected)")
        return LuaReturn.of(LuaCoroutine(function, runtime, (function as? LuaCoroutineFunction)?.createCoroutine()))
    }

    private fun coroutineResume(context: LuaCallContext, runtime: CoroutineRuntime): LuaReturn {
        val target = requiredCoroutineTarget(context, 1, "resume", runtime)
        if (target is CoroutineTarget.Main) {
            return LuaReturn.of(false, "cannot resume non-suspended coroutine")
        }
        val coroutine = (target as CoroutineTarget.Child).coroutine
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

        coroutine.needsErrorClose = false
        coroutine.status = CoroutineStatus.RUNNING
        val previousRunning = runtime.running
        runtime.running = coroutine
        runtime.updateMainCurrent(false)
        return try {
            val handle = coroutine.handle
            if (handle != null) {
                when (val result = handle.resume(arguments)) {
                    is LuaCoroutineResult.Returned -> {
                        coroutine.status = CoroutineStatus.DEAD
                        LuaReturn.ofValues(true, result.values)
                    }
                    is LuaCoroutineResult.Yielded -> {
                        if (result.values.singleOrNull() === SelfCloseSignal) {
                            val closeResult = closeCoroutineHandle(coroutine, handle)
                            if (closeResult.get(1) == false) {
                                val errorObject = closeResult.get(2)
                                coroutine.terminalError = CoroutineTerminalError(errorObject)
                                coroutine.needsErrorClose = true
                            }
                            return closeResult
                        }
                        coroutine.status = CoroutineStatus.SUSPENDED
                        LuaReturn.ofValues(true, result.values)
                    }
                    LuaCoroutineResult.DebugSuspended -> {
                        coroutine.status = CoroutineStatus.DEAD
                        coroutine.needsErrorClose = true
                        LuaReturn.of(false, "coroutine suspended by debugger outside a debug session")
                    }
                    is LuaCoroutineResult.RuntimeError -> {
                        coroutine.status = CoroutineStatus.DEAD
                        coroutine.needsErrorClose = true
                        val errorObject = if (result.hasErrorObject) result.errorObject else result.message
                        LuaReturn.of(false, errorObject)
                    }
                }
            } else if (coroutine.pendingYield != null) {
                resumeHostYieldableCoroutine(coroutine, coroutine.pendingYield, arguments)
            } else {
                try {
                    val result = context.call(coroutine.function, arguments)
                    coroutine.status = CoroutineStatus.DEAD
                    LuaReturn.ofValues(true, result.values)
                } catch (yield: LuaYieldException) {
                    if (coroutine.function !is LuaYieldableFunction) {
                        coroutine.status = CoroutineStatus.DEAD
                        val errorValue = "attempt to yield across a C-call boundary"
                        coroutine.rememberCloseError(errorValue)
                        coroutine.needsErrorClose = true
                        return LuaReturn.of(false, errorValue)
                    }
                    suspendHostYieldableCoroutine(coroutine, yield)
                }
            }
        } catch (exception: LuaException) {
            coroutine.status = CoroutineStatus.DEAD
            val errorObject = coroutineErrorObject(exception)
            coroutine.terminalError = CoroutineTerminalError(errorObject)
            coroutine.needsErrorClose = true
            LuaReturn.of(false, errorObject)
        } catch (exception: RuntimeException) {
            coroutine.status = CoroutineStatus.DEAD
            val errorObject = exception.message ?: exception::class.java.simpleName
            coroutine.terminalError = CoroutineTerminalError(errorObject)
            coroutine.needsErrorClose = true
            LuaReturn.of(false, errorObject)
        } finally {
            runtime.running = previousRunning
            runtime.updateMainCurrent(previousRunning == null)
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
            LuaReturn.ofValues(true, result.values)
        } catch (nextYield: LuaYieldException) {
            suspendHostYieldableCoroutine(coroutine, nextYield)
        }
    }

    private fun suspendHostYieldableCoroutine(coroutine: LuaCoroutine, yield: LuaYieldException): LuaReturn {
        coroutine.pendingYield = yield.withContinuation { arguments ->
            yield.continueWith(arguments)
        }
        coroutine.status = CoroutineStatus.SUSPENDED
        return LuaReturn.ofValues(true, yield.values)
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
        val target = optionalCurrentCoroutineTarget(context, runtime, "close")
        if (target is CoroutineTarget.Main) {
            if (runtime.running == null) {
                throw LuaRuntimeException("cannot close main thread")
            }
            throw LuaRuntimeException("cannot close a normal coroutine")
        }
        val coroutine = (target as CoroutineTarget.Child).coroutine
        if (coroutine.status == CoroutineStatus.RUNNING) {
            if (!isRunningCoroutine(coroutine, runtime)) {
                throw LuaRuntimeException("cannot close a normal coroutine")
            }
            coroutine.status = CoroutineStatus.DEAD
            context.yield(listOf(SelfCloseSignal))
        }
        return closeInactiveCoroutine(coroutine)
    }

    private fun closeInactiveCoroutine(coroutine: LuaCoroutine): LuaReturn {
        coroutine.needsErrorClose = false
        if (coroutine.hasCloseError) {
            val errorValue = coroutine.closeError
            coroutine.clearCloseError()
            coroutine.status = CoroutineStatus.DEAD
            return LuaReturn.of(false, errorValue)
        }
        coroutine.terminalError?.let { terminalError ->
            coroutine.terminalError = null
            return LuaReturn.of(false, terminalError.errorObject)
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
        val runtime = coroutine.runtime
        val previousRunning = runtime.running
        coroutine.status = CoroutineStatus.RUNNING
        runtime.running = coroutine
        runtime.updateMainCurrent(false)
        return try {
            when (val result = handle.close()) {
                is LuaCoroutineResult.Returned -> LuaReturn.of(true)
                is LuaCoroutineResult.Yielded -> LuaReturn.of(false, "attempt to yield while closing coroutine")
                LuaCoroutineResult.DebugSuspended -> LuaReturn.of(false, "attempt to suspend while closing coroutine")
                is LuaCoroutineResult.RuntimeError -> {
                    LuaReturn.of(false, if (result.hasErrorObject) result.errorObject else result.message)
                }
            }
        } finally {
            coroutine.status = CoroutineStatus.DEAD
            runtime.running = previousRunning
            runtime.updateMainCurrent(previousRunning == null)
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

    private fun coroutineErrorObject(exception: LuaException): Any? {
        return if (exception is LuaRuntimeException && exception.hasErrorObject) {
            exception.errorObject
        } else {
            exception.message ?: exception::class.java.simpleName
        }
    }

    private fun coroutineIsYieldable(context: LuaCallContext, runtime: CoroutineRuntime): LuaReturn {
        if (context.isNone(1)) {
            return LuaReturn.of(runtime.running != null && context.isYieldable)
        }
        val target = requiredCoroutineTarget(context, 1, "isyieldable", runtime)
        if (target is CoroutineTarget.Main) {
            return LuaReturn.of(false)
        }
        val coroutine = (target as CoroutineTarget.Child).coroutine
        if (coroutine.status != CoroutineStatus.RUNNING) {
            return LuaReturn.of(true)
        }
        return LuaReturn.of(isRunningCoroutine(coroutine, runtime) && context.isYieldable)
    }

    private fun coroutineStatus(context: LuaCallContext, runtime: CoroutineRuntime): LuaReturn {
        val target = requiredCoroutineTarget(context, 1, "status", runtime)
        if (target is CoroutineTarget.Main) {
            return LuaReturn.of(if (runtime.running == null) "running" else "normal")
        }
        val coroutine = (target as CoroutineTarget.Child).coroutine
        return LuaReturn.of(
            when (coroutine.status) {
                CoroutineStatus.SUSPENDED -> "suspended"
                CoroutineStatus.RUNNING -> if (isRunningCoroutine(coroutine, runtime)) "running" else "normal"
                CoroutineStatus.DEAD -> "dead"
            },
        )
    }

    private fun isRunningCoroutine(coroutine: LuaCoroutine, runtime: CoroutineRuntime): Boolean {
        return runtime.running == coroutine
    }

    private fun coroutineWrap(context: LuaCallContext, runtime: CoroutineRuntime): LuaReturn {
        val created = coroutineCreate(context, "wrap", runtime).getUserData(1, LuaCoroutine::class.java)
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
                val errorValue = if (created.needsErrorClose) {
                    val closeResult = closeInactiveCoroutine(created)
                    if (closeResult.get(1) == false) closeResult.get(2) else result.get(2)
                } else {
                    result.get(2)
                }
                throwCoroutineWrapError(wrapperContext, errorValue, creationFrames)
            }
            LuaReturn.ofValues(result.values.drop(1))
        }
        return LuaReturn.of(wrapper)
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

    private fun requiredCoroutineTarget(
        context: LuaCallContext,
        index: Int,
        functionName: String,
        runtime: CoroutineRuntime,
    ): CoroutineTarget {
        context.toUserData(index, LuaCoroutine::class.java)?.let { coroutine ->
            return CoroutineTarget.Child(coroutine)
        }
        val main = context.toUserData(index, LuaMainThread::class.java)
        if (main === runtime.main) {
            return CoroutineTarget.Main(main)
        }
        throw LuaRuntimeException("bad argument #$index to '$functionName' (thread expected)")
    }

    private fun optionalCurrentCoroutineTarget(
        context: LuaCallContext,
        runtime: CoroutineRuntime,
        functionName: String,
    ): CoroutineTarget {
        return if (context.isNone(1)) {
            runtime.running?.let(CoroutineTarget::Child) ?: CoroutineTarget.Main(runtime.main)
        } else {
            requiredCoroutineTarget(context, 1, functionName, runtime)
        }
    }

    private fun argumentValue(context: LuaCallContext, index: Int): Any? {
        return when (context.typeName(index)) {
            "table" -> context.getTable(index)
            "function" -> context.getLuaValue(index)
            else -> context.get(index)
        }
    }

    private fun setFunctionField(state: LuaState, name: String, function: LuaFunction) {
        state.pushFunction(function)
        state.setField(-2, name)
    }

    private class CoroutineLibraryState(
        main: LuaMainThread,
        stateReference: WeakReference<LuaState>,
    ) {
        private val runtime = CoroutineRuntime(main) { current ->
            stateReference.get()?.setMainThreadCurrent(current)
        }

        val closeFunction = LuaYieldableFunction { context -> coroutineClose(context, runtime) }
        val createFunction = LuaFunction { context -> coroutineCreate(context, "create", runtime) }
        val isYieldableFunction = LuaYieldableFunction { context -> coroutineIsYieldable(context, runtime) }
        val resumeFunction = LuaFunction { context -> coroutineResume(context, runtime) }
        val runningFunction = LuaFunction { coroutineRunning(runtime) }
        val statusFunction = LuaFunction { context -> coroutineStatus(context, runtime) }
        val wrapFunction = LuaFunction { context -> coroutineWrap(context, runtime) }
        val yieldFunction = LuaYieldableFunction { context -> coroutineYield(context, runtime) }
    }

    private class CoroutineRuntime(
        val main: LuaMainThread,
        val updateMainCurrent: (Boolean) -> Unit,
    ) {
        var running: LuaCoroutine? = null
    }

    private sealed interface CoroutineTarget {
        data class Main(val thread: LuaMainThread) : CoroutineTarget

        data class Child(val coroutine: LuaCoroutine) : CoroutineTarget
    }

    private class LuaCoroutine(
        val function: LuaFunction,
        val runtime: CoroutineRuntime,
        val handle: LuaCoroutineHandle? = null,
        var status: CoroutineStatus = CoroutineStatus.SUSPENDED,
        var pendingYield: LuaYieldException? = null,
        var terminalError: CoroutineTerminalError? = null,
        var needsErrorClose: Boolean = false,
        var closeError: Any? = null,
        var hasCloseError: Boolean = false,
    ) : LuaTypedValue, LuaDebugThread {
        override val luaTypeName: String = "thread"

        override val luaFrames: List<io.github.realmlabs.klua.api.LuaStackFrame>
            get() = (handle as? LuaDebuggableCoroutineHandle)?.luaFrames ?: emptyList()

        override val isCurrentDebugThread: Boolean
            get() = runtime.running == this

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

    private const val MAIN_THREAD_REGISTRY_INDEX = 3L

    private data class CoroutineTerminalError(
        val errorObject: Any?,
    )
}
