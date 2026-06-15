package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaException
import io.github.realmlabs.klua.api.LuaFunction
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState
import io.github.realmlabs.klua.api.LuaYieldException
import io.github.realmlabs.klua.api.LuaYieldableFunction
import io.github.realmlabs.klua.api.continueWith
import io.github.realmlabs.klua.api.withContinuation
import java.io.IOException
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.function.Consumer

public object LuaStdlib {
    @JvmStatic
    public fun openLibs(state: LuaState): LuaState {
        return openLibs(state, standardOutput())
    }

    @JvmStatic
    public fun openLibs(state: LuaState, output: Consumer<String>): LuaState {
        openBase(state, output)
        openMath(state)
        openString(state)
        openTable(state)
        openUtf8(state)
        openCoroutine(state)
        openPackage(state)
        if (state.config.debugEnabled) {
            openDebug(state)
        }
        return state
    }

    @JvmStatic
    public fun openBase(state: LuaState): LuaState {
        return openBase(state, standardOutput())
    }

    @JvmStatic
    public fun openBase(state: LuaState, output: Consumer<String>): LuaState {
        state.installGlobalTable("_G")
        state.pushString("Lua 5.5")
        state.setGlobal("_VERSION")
        state.register("assert", ::assert)
        var garbageCollectorRunning = true
        var garbageCollectorMode = "incremental"
        val garbageCollectorParams = DEFAULT_GARBAGE_COLLECTOR_PARAMS.toMutableMap()
        state.register("collectgarbage") { context ->
            val result = collectgarbage(context, garbageCollectorRunning, garbageCollectorMode, garbageCollectorParams)
            garbageCollectorRunning = result.running
            garbageCollectorMode = result.mode
            result.returnValue
        }
        state.register("dofile", ::dofile)
        state.register("error", ::error)
        state.register("getmetatable", ::getmetatable)
        state.register("ipairs", ::ipairs)
        state.register("load", ::load)
        state.register("loadfile", ::loadfile)
        state.register("next", ::next)
        state.register("pairs", ::pairs)
        registerYieldable(state, "pcall", ::pcall)
        state.register("print") { context -> print(context, output) }
        state.register("rawequal", ::rawequal)
        state.register("rawget", ::rawget)
        state.register("rawlen", ::rawlen)
        state.register("rawset", ::rawset)
        state.register("select", ::select)
        state.register("setmetatable", ::setmetatable)
        state.register("tonumber", ::tonumber)
        state.register("tostring", ::tostring)
        state.register("type", ::type)
        var warningsEnabled = false
        state.register("warn") { context ->
            warningsEnabled = warn(context, output, warningsEnabled)
            LuaReturn.none()
        }
        registerYieldable(state, "xpcall", ::xpcall)
        return state
    }

    private fun registerYieldable(state: LuaState, name: String, function: (LuaCallContext) -> LuaReturn) {
        state.register(name, LuaYieldableFunction { context -> function(context) })
    }

    private data class GarbageCollectorResult(
        val running: Boolean,
        val mode: String,
        val returnValue: LuaReturn,
    )

    @JvmStatic
    public fun openMath(state: LuaState): LuaState {
        return LuaMathLibrary.open(state)
    }

    @JvmStatic
    public fun openString(state: LuaState): LuaState {
        return LuaStringLibrary.open(state)
    }

    @JvmStatic
    public fun openTable(state: LuaState): LuaState {
        return LuaTableLibrary.open(state)
    }

    @JvmStatic
    public fun openUtf8(state: LuaState): LuaState {
        return LuaUtf8Library.open(state)
    }

    @JvmStatic
    public fun openCoroutine(state: LuaState): LuaState {
        return LuaCoroutineLibrary.open(state)
    }

    @JvmStatic
    public fun openPackage(state: LuaState): LuaState {
        return LuaPackageLibrary.open(state)
    }

    @JvmStatic
    public fun openDebug(state: LuaState): LuaState {
        return LuaDebugLibrary.open(state)
    }

    private fun assert(context: LuaCallContext): LuaReturn {
        if (!context.toBoolean(1)) {
            requireAnyArgument(context, "assert")
            val errorObject = if (context.isNone(2)) "assertion failed!" else argumentValue(context, 2)
            throw luaError(locationPrefixedError(context, 1, errorObject))
        }
        return LuaReturn.ofValues((1..context.argumentCount).map { index -> argumentValue(context, index) })
    }

    private fun error(context: LuaCallContext): LuaReturn {
        val level = if (context.isNone(2) || context.isNil(2)) {
            1
        } else {
            requiredNumberIndex(context, 2, "error").toInt()
        }
        val errorObject = if (context.isNone(1)) null else argumentValue(context, 1)
        throw luaError(locationPrefixedError(context, level, errorObject))
    }

    private fun locationPrefixedError(context: LuaCallContext, level: Int, errorObject: Any?): Any? {
        if (level <= 0 || errorObject !is CharSequence) {
            return errorObject
        }
        val frame = context.luaFrames.getOrNull(level - 1) ?: return errorObject
        return buildString {
            append(luaShortSourceName(frame.sourceName))
            if (frame.line > 0) {
                append(':')
                append(frame.line)
            }
            append(": ")
            append(errorObject)
        }
    }

    private fun collectgarbage(
        context: LuaCallContext,
        running: Boolean,
        mode: String,
        params: MutableMap<String, Long>,
    ): GarbageCollectorResult {
        return when (val option = optionalString(context, 1, "collect", "collectgarbage")) {
            "collect" -> {
                System.gc()
                GarbageCollectorResult(running, mode, LuaReturn.of(0L))
            }
            "stop" -> GarbageCollectorResult(false, mode, LuaReturn.of(0L))
            "restart" -> GarbageCollectorResult(true, mode, LuaReturn.of(0L))
            "count" -> GarbageCollectorResult(running, mode, LuaReturn.of(usedMemoryKilobytes()))
            "step" -> {
                if (!context.isNone(2) && !context.isNil(2)) {
                    requiredIntegerLikeLuaL(context, 2, "collectgarbage")
                }
                System.gc()
                GarbageCollectorResult(running, mode, LuaReturn.of(false))
            }
            "isrunning" -> GarbageCollectorResult(running, mode, LuaReturn.of(running))
            "incremental" -> GarbageCollectorResult(running, "incremental", LuaReturn.of(mode))
            "generational" -> GarbageCollectorResult(running, "generational", LuaReturn.of(mode))
            "param" -> {
                val parameter = requiredString(context, 2, "collectgarbage")
                val previous = params[parameter]
                    ?: throw LuaRuntimeException("bad argument #2 to 'collectgarbage' (invalid option '$parameter')")
                if (!context.isNone(3) && !context.isNil(3)) {
                    val value = requiredIntegerLikeLuaL(context, 3, "collectgarbage")
                    if (value != -1L) {
                        params[parameter] = value
                    }
                }
                GarbageCollectorResult(running, mode, LuaReturn.of(previous))
            }
            else -> throw LuaRuntimeException("bad argument #1 to 'collectgarbage' (invalid option '$option')")
        }
    }

    private fun usedMemoryKilobytes(): Double {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()).toDouble() / 1024.0
    }

    private fun getmetatable(context: LuaCallContext): LuaReturn {
        requireAnyArgument(context, "getmetatable")
        val metatable = context.getRawMetatable(1) ?: return LuaReturn.of(null)
        return LuaReturn.of(context.getTableField(metatable, "__metatable") ?: metatable)
    }

    private fun load(context: LuaCallContext): LuaReturn {
        val sourceType = context.typeName(1)
        val sourceString = context.toString(1)
        val mode = loadMode(context, 3, "load")
        val chunkName = if (context.isNone(2) || context.isNil(2)) {
            if (sourceString != null) {
                sourceString
            } else {
                "=(load)"
            }
        } else {
            requiredString(context, 2, "load")
        }
        if ('t' !in mode) {
            return LuaReturn.of(null, textChunkModeError(mode))
        }
        val source = if (sourceString != null) {
            sourceString
        } else {
            if (sourceType != "function") {
                throw LuaRuntimeException("bad argument #1 to 'load' (function expected)")
            }
            try {
                readChunkSource(context)
            } catch (yield: LuaYieldException) {
                throw yield
            } catch (exception: LuaException) {
                return LuaReturn.of(null, errorObject(exception))
            } catch (exception: RuntimeException) {
                return LuaReturn.of(null, exception.message ?: exception::class.java.simpleName)
            }
        }
        val environment = optionalLoadEnvironment(context, 4)
        return context.load(source, chunkName, environment.value, environment.provided)
    }

    private fun readChunkSource(context: LuaCallContext): String {
        val source = StringBuilder()
        while (true) {
            val chunk = context.call(1, emptyList()).get(1) ?: break
            val text = readerChunkToString(chunk)
            if (text.isEmpty()) {
                break
            }
            source.append(text)
        }
        return source.toString()
    }

    private fun readerChunkToString(chunk: Any?): String {
        return when (chunk) {
            is Byte -> chunk.toLong().toString()
            is Short -> chunk.toLong().toString()
            is Int -> chunk.toLong().toString()
            is Long -> chunk.toString()
            is Float -> chunk.toDouble().toString()
            is Double -> chunk.toString()
            is CharSequence -> chunk.toString()
            else -> throw LuaRuntimeException("reader function must return a string")
        }
    }

    private fun loadfile(context: LuaCallContext): LuaReturn {
        val mode = loadMode(context, 2, "loadfile")
        return loadFile(context, "loadfile", mode, environmentIndex = 3)
    }

    private fun loadFile(
        context: LuaCallContext,
        functionName: String,
        mode: String,
        environmentIndex: Int?,
    ): LuaReturn {
        val filename = if (context.isNone(1) || context.isNil(1)) {
            null
        } else {
            requiredString(context, 1, functionName)
        }
        if ('t' !in mode) {
            return LuaReturn.of(null, textChunkModeError(mode))
        }
        val source = try {
            if (filename == null) {
                String(System.`in`.readBytes(), StandardCharsets.UTF_8)
            } else {
                Files.readString(Path.of(filename))
            }
        } catch (error: IOException) {
            return LuaReturn.of(null, error.message ?: "cannot read file '$filename'")
        }
        val environment = if (environmentIndex == null) {
            LoadEnvironment(null, provided = false)
        } else {
            optionalLoadEnvironment(context, environmentIndex)
        }
        return context.load(source, filename ?: "=stdin", environment.value, environment.provided)
    }

    private fun optionalLoadEnvironment(context: LuaCallContext, index: Int): LoadEnvironment {
        if (context.isNone(index)) {
            return LoadEnvironment(null, provided = false)
        }
        return LoadEnvironment(if (context.isNil(index)) null else context.getLuaValue(index), provided = true)
    }

    private data class LoadEnvironment(
        val value: Any?,
        val provided: Boolean,
    )

    private fun dofile(context: LuaCallContext): LuaReturn {
        val loaded = loadFile(context, "dofile", "bt", environmentIndex = null)
        val function = loaded.get(1)
        if (function == null) {
            throw LuaRuntimeException(loaded.get(2)?.toString() ?: "cannot load file")
        }
        return context.call(function, emptyList())
    }

    private fun ipairs(context: LuaCallContext): LuaReturn {
        requireAnyArgument(context, "ipairs")
        val iterator = LuaFunction { iteratorContext ->
            val nextIndex = requiredNumberIndex(iteratorContext, 2, "ipairs iterator") + 1L
            val indexed = indexValue(iteratorContext, 1, nextIndex)
            val value = if (indexed.handled) {
                indexed.value
            } else {
                throw LuaRuntimeException("attempt to index a ${iteratorContext.typeName(1)} value")
            }
            if (value == null) {
                LuaReturn.of(null)
            } else {
                LuaReturn.of(nextIndex, value)
            }
        }
        return LuaReturn.ofValues(listOf(iterator, argumentValue(context, 1), 0L))
    }

    private fun indexValue(context: LuaCallContext, valueIndex: Int, key: Any?): IndexedValue {
        if (context.isTable(valueIndex)) {
            val rawValue = context.getTableValue(valueIndex, key)
            if (rawValue != null) {
                return IndexedValue(rawValue, handled = true)
            }
        }
        val index = context.getTableField(context.getRawMetatable(valueIndex), "__index")
            ?: return IndexedValue(null, handled = context.isTable(valueIndex) || context.typeName(valueIndex) == "string")
        return try {
            IndexedValue(context.call(index, listOf(argumentValue(context, valueIndex), key)).get(1), handled = true)
        } catch (_: IllegalArgumentException) {
            IndexedValue(context.getTableField(index, key), handled = true)
        }
    }

    private data class IndexedValue(
        val value: Any?,
        val handled: Boolean,
    )

    private fun next(context: LuaCallContext): LuaReturn {
        if (!context.isTable(1)) {
            throw LuaRuntimeException("bad argument #1 to 'next' (table expected)")
        }
        val key = if (context.isNone(2) || context.isNil(2)) {
            null
        } else {
            argumentValue(context, 2)
        }
        return LuaReturn.ofValues(context.nextTableEntry(1, key) ?: listOf(null))
    }

    private fun pairs(context: LuaCallContext): LuaReturn {
        requireAnyArgument(context, "pairs")
        val pairs = context.getTableField(context.getRawMetatable(1), "__pairs")
        if (pairs != null) {
            return pairsReturn(context.call(pairs, listOf(argumentValue(context, 1))))
        }
        return LuaReturn.ofValues(listOf(LuaFunction(::next), argumentValue(context, 1), null, null))
    }

    private fun pairsReturn(result: LuaReturn): LuaReturn {
        return LuaReturn.of(result.get(1), result.get(2), result.get(3), result.get(4))
    }

    private fun pcall(context: LuaCallContext): LuaReturn {
        requireAnyArgument(context, "pcall")
        return protectedCall(context, functionIndex = 1, firstArgumentIndex = 2, handlerIndex = null)
    }

    private fun print(context: LuaCallContext, output: Consumer<String>): LuaReturn {
        output.accept((1..context.argumentCount).joinToString("\t") { index -> toLuaString(context, index) })
        return LuaReturn.none()
    }

    private fun warn(context: LuaCallContext, output: Consumer<String>, warningsEnabled: Boolean): Boolean {
        val parts = mutableListOf(requiredString(context, 1, "warn"))
        for (index in 2..context.argumentCount) {
            parts += requiredString(context, index, "warn")
        }
        if (parts.size == 1 && parts.single().startsWith("@")) {
            return warningControlState(parts.single(), warningsEnabled)
        }

        if (warningsEnabled) {
            output.accept("Lua warning: ${parts.joinToString("")}")
            return true
        }
        return warningControlState(parts.last(), false)
    }

    private fun warningControlState(message: String, warningsEnabled: Boolean): Boolean {
        if (!message.startsWith("@")) {
            return warningsEnabled
        }
        return when (message) {
            "@on" -> true
            "@off" -> false
            else -> warningsEnabled
        }
    }

    private fun rawequal(context: LuaCallContext): LuaReturn {
        requireArgument(context, 1, "rawequal")
        requireArgument(context, 2, "rawequal")
        return LuaReturn.of(rawEqual(context, 1, 2))
    }

    private fun rawEqual(context: LuaCallContext, leftIndex: Int, rightIndex: Int): Boolean {
        context.rawEquals(leftIndex, rightIndex)?.let { return it }
        val leftType = context.typeName(leftIndex)
        val rightType = context.typeName(rightIndex)
        if (leftType != rightType) {
            return false
        }
        return when (leftType) {
            "nil" -> true
            "boolean" -> context.toBoolean(leftIndex) == context.toBoolean(rightIndex)
            "number" -> context.toNumber(leftIndex) == context.toNumber(rightIndex)
            "string" -> context.toString(leftIndex) == context.toString(rightIndex)
            "table" -> context.getTable(leftIndex) === context.getTable(rightIndex)
            "userdata" -> context.toUserData(leftIndex) === context.toUserData(rightIndex)
            "function" -> context.getLuaValue(leftIndex) === context.getLuaValue(rightIndex)
            else -> false
        }
    }

    private fun rawget(context: LuaCallContext): LuaReturn {
        if (!context.isTable(1)) {
            throw LuaRuntimeException("bad argument #1 to 'rawget' (table expected)")
        }
        requireArgument(context, 2, "rawget")
        return LuaReturn.of(context.getTableValue(1, argumentValue(context, 2)))
    }

    private fun rawlen(context: LuaCallContext): LuaReturn {
        return when {
            context.typeName(1) == "string" -> LuaReturn.of(requiredString(context, 1, "rawlen").luaByteLength())
            context.isTable(1) -> LuaReturn.of(context.tableLength(1) ?: 0L)
            else -> throw LuaRuntimeException("bad argument #1 to 'rawlen' (table or string expected)")
        }
    }

    private fun rawset(context: LuaCallContext): LuaReturn {
        if (!context.isTable(1)) {
            throw LuaRuntimeException("bad argument #1 to 'rawset' (table expected)")
        }
        requireArgument(context, 2, "rawset")
        if (context.isNone(3)) {
            throw LuaRuntimeException("bad argument #3 to 'rawset' (value expected)")
        }
        requireTableKey(context, 2)
        context.setTableValue(1, argumentValue(context, 2), argumentValue(context, 3))
        return LuaReturn.of(context.getTable(1))
    }

    private fun select(context: LuaCallContext): LuaReturn {
        if (context.typeName(1) == "string" && context.toString(1)?.startsWith("#") == true) {
            return LuaReturn.of((context.argumentCount - 1).toLong())
        }

        val index = requiredNumberIndex(context, 1, "select")
        val start = when {
            index > 0L -> index + 1L
            index < 0L -> context.argumentCount + index + 1L
            else -> throw LuaRuntimeException("bad argument #1 to 'select' (index out of range)")
        }
        if (start < 2L) {
            throw LuaRuntimeException("bad argument #1 to 'select' (index out of range)")
        }
        if (start > context.argumentCount.toLong()) {
            return LuaReturn.none()
        }
        return LuaReturn.ofValues((start.toInt()..context.argumentCount).map { argument -> argumentValue(context, argument) })
    }

    private fun setmetatable(context: LuaCallContext): LuaReturn {
        if (!context.isTable(1)) {
            throw LuaRuntimeException("bad argument #1 to 'setmetatable' (table expected)")
        }
        if (context.isNone(2)) {
            throw LuaRuntimeException("bad argument #2 to 'setmetatable' (nil or table expected)")
        }
        if (!context.isNone(2) && !context.isNil(2) && !context.isTable(2)) {
            throw LuaRuntimeException("bad argument #2 to 'setmetatable' (nil or table expected)")
        }
        val currentMetatable = context.getMetatable(1)
        if (context.getTableField(currentMetatable, "__metatable") != null) {
            throw LuaRuntimeException("cannot change a protected metatable")
        }
        context.setMetatable(1, context.getTable(2))
        return LuaReturn.of(context.getTable(1))
    }

    private fun xpcall(context: LuaCallContext): LuaReturn {
        if (context.typeName(2) != "function") {
            throw LuaRuntimeException("bad argument #2 to 'xpcall' (function expected)")
        }
        return protectedCall(context, functionIndex = 1, firstArgumentIndex = 3, handlerIndex = 2)
    }

    private fun tonumber(context: LuaCallContext): LuaReturn {
        requireAnyArgument(context, "tonumber")
        if (!context.isNone(2) && !context.isNil(2)) {
            val base = requiredTonumberBase(context)
            return when (val value = argumentValue(context, 1)) {
                is CharSequence -> {
                    if (base !in 2L..36L) {
                        throw LuaRuntimeException("bad argument #2 to 'tonumber' (base out of range)")
                    }
                    LuaReturn.of(parseBasedInteger(value.toString(), base.toInt()))
                }
                else -> throw LuaRuntimeException("bad argument #1 to 'tonumber' (string expected)")
            }
        }
        val value = argumentValue(context, 1) ?: return LuaReturn.of(null)
        return when (value) {
            is Byte -> LuaReturn.of(value.toLong())
            is Short -> LuaReturn.of(value.toLong())
            is Int -> LuaReturn.of(value.toLong())
            is Long -> LuaReturn.of(value)
            is Float -> LuaReturn.of(value.toDouble())
            is Double -> LuaReturn.of(value)
            is CharSequence -> LuaReturn.of(parseNumber(value.toString()))
            else -> LuaReturn.of(null)
        }
    }

    private fun requiredTonumberBase(context: LuaCallContext): Long {
        return context.toInteger(2)
            ?: if (context.toNumber(2) != null || context.typeName(2) == "number") {
                throw LuaRuntimeException("bad argument #2 to 'tonumber' (number has no integer representation)")
            } else {
                throw LuaRuntimeException("bad argument #2 to 'tonumber' (number expected)")
            }
    }

    private fun tostring(context: LuaCallContext): LuaReturn {
        requireAnyArgument(context, "tostring")
        return LuaReturn.of(toLuaString(context, 1))
    }

    private fun type(context: LuaCallContext): LuaReturn {
        requireAnyArgument(context, "type")
        return LuaReturn.of(context.typeName(1))
    }

    private fun protectedCall(
        context: LuaCallContext,
        functionIndex: Int,
        firstArgumentIndex: Int,
        handlerIndex: Int?,
    ): LuaReturn {
        return try {
            val result = context.call(functionIndex, (firstArgumentIndex..context.argumentCount).map { index -> argumentValue(context, index) })
            LuaReturn.ofValues(listOf(true) + result.values)
        } catch (yield: LuaYieldException) {
            throw yield.withContinuation { arguments ->
                protectedCallResume(context, yield, handlerIndex, arguments)
            }
        } catch (exception: LuaException) {
            protectedCallError(context, errorObject(exception), handlerIndex)
        } catch (exception: RuntimeException) {
            protectedCallError(context, exception.message ?: exception::class.java.simpleName, handlerIndex)
        }
    }

    private fun protectedCallResume(
        context: LuaCallContext,
        yield: LuaYieldException,
        handlerIndex: Int?,
        arguments: List<Any?>,
    ): LuaReturn {
        return try {
            val result = yield.continueWith(arguments)
            LuaReturn.ofValues(listOf(true) + result.values)
        } catch (nextYield: LuaYieldException) {
            throw nextYield.withContinuation { nextArguments ->
                protectedCallResume(context, nextYield, handlerIndex, nextArguments)
            }
        } catch (exception: LuaException) {
            protectedCallError(context, errorObject(exception), handlerIndex)
        } catch (exception: RuntimeException) {
            protectedCallError(context, exception.message ?: exception::class.java.simpleName, handlerIndex)
        }
    }

    private fun protectedCallError(context: LuaCallContext, errorObject: Any?, handlerIndex: Int?): LuaReturn {
        if (handlerIndex == null) {
            return LuaReturn.of(false, errorObject)
        }
        return try {
            val handlerResult = context.call(handlerIndex, listOf(errorObject))
            LuaReturn.of(false, handlerResult.get(1))
        } catch (yield: LuaYieldException) {
            throw yield.withContinuation { arguments ->
                protectedCallErrorResume(yield, arguments)
            }
        } catch (_: LuaException) {
            LuaReturn.of(false, "error in error handling")
        } catch (_: RuntimeException) {
            LuaReturn.of(false, "error in error handling")
        }
    }

    private fun protectedCallErrorResume(yield: LuaYieldException, arguments: List<Any?>): LuaReturn {
        return try {
            val handlerResult = yield.continueWith(arguments)
            LuaReturn.of(false, handlerResult.get(1))
        } catch (nextYield: LuaYieldException) {
            throw nextYield.withContinuation { nextArguments ->
                protectedCallErrorResume(nextYield, nextArguments)
            }
        } catch (_: LuaException) {
            LuaReturn.of(false, "error in error handling")
        } catch (_: RuntimeException) {
            LuaReturn.of(false, "error in error handling")
        }
    }

    private fun luaError(errorObject: Any?): LuaRuntimeException {
        if (errorObject == null) {
            return LuaRuntimeException("<no error object>", errorObject = null, hasErrorObject = true)
        }
        val message = when (errorObject) {
            is CharSequence -> errorObject.toString()
            else -> luaErrorTypeName(errorObject)
        }
        return LuaRuntimeException(message, errorObject = errorObject, hasErrorObject = true)
    }

    private fun errorObject(exception: LuaException): Any? {
        return if (exception is LuaRuntimeException && exception.hasErrorObject) {
            exception.errorObject
        } else {
            exception.message ?: exception::class.java.simpleName
        }
    }

    private fun luaErrorTypeName(value: Any): String {
        return when (value) {
            is Boolean -> "boolean"
            is Byte,
            is Short,
            is Int,
            is Long,
            is Float,
            is Double,
            -> "number"
            is LuaFunction -> "function"
            is Map<*, *> -> "table"
            else -> "userdata"
        }
    }

    private fun requiredString(context: LuaCallContext, index: Int, functionName: String): String {
        return context.toString(index)
            ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (string expected)")
    }

    private fun optionalString(
        context: LuaCallContext,
        index: Int,
        default: String,
        functionName: String,
    ): String {
        return if (context.isNone(index) || context.isNil(index)) {
            default
        } else {
            requiredString(context, index, functionName)
        }
    }

    private fun loadMode(context: LuaCallContext, index: Int, functionName: String): String {
        val mode = optionalString(context, index, "bt", functionName)
        if ('B' in mode) {
            throw LuaRuntimeException("bad argument #$index to '$functionName' (invalid mode)")
        }
        return mode
    }

    private fun textChunkModeError(mode: String): String {
        return "attempt to load a text chunk (mode is '$mode')"
    }

    private fun requiredInteger(context: LuaCallContext, index: Int, functionName: String): Long {
        return context.toInteger(index)
            ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (integer expected)")
    }

    private fun requiredNumber(context: LuaCallContext, index: Int, functionName: String): Double {
        return context.toNumber(index)
            ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (number expected)")
    }

    private fun requiredNumberIndex(context: LuaCallContext, index: Int, functionName: String): Long {
        return context.toInteger(index)
            ?: if (context.toNumber(index) != null || context.typeName(index) == "number") {
                throw LuaRuntimeException("bad argument #$index to '$functionName' (number has no integer representation)")
            } else {
                throw LuaRuntimeException("bad argument #$index to '$functionName' (number expected)")
            }
    }

    private fun requiredIntegerLikeLuaL(context: LuaCallContext, index: Int, functionName: String): Long {
        return context.toInteger(index)
            ?: if (context.toNumber(index) != null || context.typeName(index) == "number") {
                throw LuaRuntimeException("bad argument #$index to '$functionName' (number has no integer representation)")
            } else {
                throw LuaRuntimeException("bad argument #$index to '$functionName' (number expected)")
            }
    }

    private fun requireAnyArgument(context: LuaCallContext, functionName: String) {
        if (context.isNone(1)) {
            throw LuaRuntimeException("bad argument #1 to '$functionName' (value expected)")
        }
    }

    private fun requireArgument(context: LuaCallContext, index: Int, functionName: String) {
        if (context.isNone(index)) {
            throw LuaRuntimeException("bad argument #$index to '$functionName' (value expected)")
        }
    }

    private fun requireTableKey(context: LuaCallContext, index: Int) {
        if (context.isNone(index) || context.isNil(index)) {
            throw LuaRuntimeException("table index is nil")
        }
        if (context.typeName(index) == "number" && context.toNumber(index)?.isNaN() == true) {
            throw LuaRuntimeException("table index is NaN")
        }
    }

    private fun toLuaString(context: LuaCallContext, index: Int): String {
        val metatable = context.getRawMetatable(index)
        val metamethod = context.getTableField(metatable, "__tostring")
        if (metamethod != null) {
            return tostringMetamethodResult(context.call(metamethod, listOf(argumentValue(context, index))).get(1))
        }
        return when (context.typeName(index)) {
            "nil" -> "nil"
            "boolean" -> context.toBoolean(index).toString()
            "number" -> luaNumberToString(context.get(index))
            "string" -> context.toString(index) ?: context.typeName(index)
            "thread" -> context.get(index)?.let { value ->
                value.typedPointerString(typeName(context, metatable, "thread"))
            } ?: "thread"
            "function" -> context.get(index)?.typedPointerString(typeName(context, metatable, "function")) ?: "function"
            "table" -> tableToLuaString(context, index)
            "userdata" -> context.get(index)?.typedPointerString(typeName(context, metatable, "userdata")) ?: "userdata"
            else -> context.typeName(index)
        }
    }

    private fun tableToLuaString(context: LuaCallContext, index: Int): String {
        val metatable = context.getMetatable(index)
        return context.getTable(index)?.typedPointerString(typeName(context, metatable, "table")) ?: context.typeName(index)
    }

    private fun tostringMetamethodResult(result: Any?): String {
        return when (result) {
            is Byte -> result.toLong().toString()
            is Short -> result.toLong().toString()
            is Int -> result.toLong().toString()
            is Long -> result.toString()
            is Float -> luaFloatToString(result.toDouble())
            is Double -> luaFloatToString(result)
            is CharSequence -> result.toString()
            else -> throw LuaRuntimeException("'__tostring' must return a string")
        }
    }

    private fun typeName(context: LuaCallContext, metatable: Any?, default: String): String {
        return (context.getTableField(metatable, "__name") as? CharSequence)?.toString() ?: default
    }

    private fun luaNumberToString(value: Any?): String {
        return when (value) {
            is Byte -> value.toLong().toString()
            is Short -> value.toLong().toString()
            is Int -> value.toLong().toString()
            is Long -> value.toString()
            is Float -> luaFloatToString(value.toDouble())
            is Double -> luaFloatToString(value)
            else -> value?.toString() ?: "number"
        }
    }

    private fun luaFloatToString(value: Double): String {
        if (value.isNaN()) {
            return "nan"
        }
        if (value == Double.POSITIVE_INFINITY) {
            return "inf"
        }
        if (value == Double.NEGATIVE_INFINITY) {
            return "-inf"
        }
        val formatted = String.format(Locale.ROOT, "%.15g", value).lowercase(Locale.ROOT)
        val exponentIndex = formatted.indexOf('e')
        return if (exponentIndex >= 0) {
            val mantissa = formatted.substring(0, exponentIndex).trimLuaFloatTrailingZeros()
            mantissa + formatted.substring(exponentIndex)
        } else {
            val decimal = formatted.trimLuaFloatTrailingZeros()
            if (value.isFiniteWholeNumber() && '.' !in decimal) "$decimal.0" else decimal
        }
    }

    private fun String.trimLuaFloatTrailingZeros(): String {
        if ('.' !in this) {
            return this
        }
        return trimEnd('0').trimEnd('.')
    }

    private fun Double.isFiniteWholeNumber(): Boolean {
        return isFinite() && this % 1.0 == 0.0
    }

    private fun Any.typedPointerString(typeName: String): String {
        return "$typeName: ${System.identityHashCode(this).toString(16)}"
    }

    private fun argumentValue(context: LuaCallContext, index: Int): Any? {
        return when (context.typeName(index)) {
            "table" -> context.getTable(index)
            else -> context.get(index)
        }
    }

    private fun parseNumber(text: String): Number? {
        val trimmed = text.trimLuaAsciiWhitespace()
        if (trimmed.isNamedFloatingPointLiteral()) {
            return null
        }
        return parseHexInteger(trimmed) ?: trimmed.toLongOrNull() ?: trimmed.toDoubleOrNull()
    }

    private fun parseBasedInteger(text: String, base: Int): Long? {
        val trimmed = text.trimLuaAsciiWhitespace()
        if (trimmed.isEmpty()) {
            return null
        }
        val sign = when (trimmed.first()) {
            '-' -> -1
            '+' -> 1
            else -> 1
        }
        val digitsStart = if (trimmed.first() == '-' || trimmed.first() == '+') 1 else 0
        if (digitsStart == trimmed.length) {
            return null
        }

        var value = BigInteger.ZERO
        val radix = BigInteger.valueOf(base.toLong())
        for (index in digitsStart until trimmed.length) {
            val digit = trimmed[index].digitToIntOrNull(base) ?: return null
            value = value.multiply(radix).add(BigInteger.valueOf(digit.toLong()))
        }
        if (sign < 0) {
            value = value.negate()
        }
        return value.mod(UINT64_MODULUS).toLong()
    }

    private fun String.isNamedFloatingPointLiteral(): Boolean {
        val unsigned = trimStart('+', '-')
        return unsigned.equals("nan", ignoreCase = true) || unsigned.equals("infinity", ignoreCase = true)
    }

    private fun String.trimLuaAsciiWhitespace(): String {
        return trim { char -> char == ' ' || char == '\u000C' || char == '\n' || char == '\r' || char == '\t' || char == '\u000B' }
    }

    private fun parseHexInteger(text: String): Long? {
        val sign = when {
            text.startsWith("-") -> -1
            text.startsWith("+") -> 1
            else -> 1
        }
        val digitsStart = if (text.startsWith("-") || text.startsWith("+")) 1 else 0
        if (!text.regionMatches(digitsStart, "0x", 0, 2, ignoreCase = true)) {
            return null
        }
        val digits = text.substring(digitsStart + 2)
        if (digits.isEmpty() || digits.any { digit -> digit.digitToIntOrNull(16) == null }) {
            return null
        }
        var value = BigInteger.ZERO
        val radix = BigInteger.valueOf(16L)
        for (digit in digits) {
            value = value.multiply(radix).add(BigInteger.valueOf(digit.digitToInt(16).toLong()))
        }
        if (sign < 0) {
            value = value.negate()
        }
        return value.mod(UINT64_MODULUS).toLong()
    }

    private val UINT64_MODULUS: BigInteger = BigInteger.ONE.shiftLeft(Long.SIZE_BITS)
    private val DEFAULT_GARBAGE_COLLECTOR_PARAMS: Map<String, Long> = mapOf(
        "minormul" to 20L,
        "majorminor" to 50L,
        "minormajor" to 68L,
        "pause" to 250L,
        "stepmul" to 200L,
        "stepsize" to 9600L,
    )

    private fun standardOutput(): Consumer<String> {
        return Consumer { line -> println(line) }
    }

    private fun String.luaByteLength(): Long {
        return toByteArray(StandardCharsets.UTF_8).size.toLong()
    }

    private fun setFunctionField(state: LuaState, name: String, function: (LuaCallContext) -> LuaReturn) {
        state.pushFunction(function::invoke)
        state.setField(-2, name)
    }

}
