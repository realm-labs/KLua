package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaExitException
import io.github.realmlabs.klua.api.LuaException
import io.github.realmlabs.klua.api.LuaFunction
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState
import io.github.realmlabs.klua.api.LuaStandardLibrary
import io.github.realmlabs.klua.api.LuaYieldException
import io.github.realmlabs.klua.api.LuaYieldableFunction
import io.github.realmlabs.klua.api.continueWith
import io.github.realmlabs.klua.api.withContinuation
import io.github.realmlabs.klua.core.value.luaRawBytes
import java.io.IOException
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Collections
import java.util.WeakHashMap
import java.util.function.Consumer

public object LuaStdlib {
    private val nextFunction = LuaFunction { context -> next(context) }
    private val ipairsIteratorFunction = LuaFunction { context -> ipairsNext(context) }
    private val garbageCollectorStates = Collections.synchronizedMap(WeakHashMap<LuaState, GarbageCollectorState>())

    @JvmStatic
    public fun openLibs(state: LuaState): LuaState {
        return openLibs(state, standardOutput())
    }

    @JvmStatic
    public fun openLibs(state: LuaState, output: Consumer<String>): LuaState {
        val libraries = state.config.standardLibraries
        if (LuaStandardLibrary.BASE in libraries) {
            openBase(state, output)
        }
        if (LuaStandardLibrary.MATH in libraries) {
            openMath(state)
        }
        if (LuaStandardLibrary.STRING in libraries) {
            openString(state)
        }
        if (LuaStandardLibrary.TABLE in libraries) {
            openTable(state)
        }
        if (LuaStandardLibrary.UTF8 in libraries) {
            openUtf8(state)
        }
        if (LuaStandardLibrary.COROUTINE in libraries) {
            openCoroutine(state)
        }
        if (LuaStandardLibrary.PACKAGE in libraries && state.config.unsafeStandardLibraryAccessEnabled) {
            openPackage(state)
        }
        if (LuaStandardLibrary.IO in libraries && state.config.unsafeStandardLibraryAccessEnabled) {
            openIo(state)
        }
        if (LuaStandardLibrary.OS in libraries && state.config.unsafeStandardLibraryAccessEnabled) {
            openOs(state)
        }
        if (LuaStandardLibrary.DEBUG in libraries && state.config.debugEnabled) {
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
        val garbageCollectorState = synchronized(garbageCollectorStates) {
            garbageCollectorStates.getOrPut(state) { GarbageCollectorState() }
        }
        state.register("collectgarbage", garbageCollectorState.function)
        if (state.config.unsafeStandardLibraryAccessEnabled) {
            state.register("dofile", LuaYieldableFunction { context -> dofile(context, state) })
        }
        state.register("error", ::error)
        state.register("getmetatable", ::getmetatable)
        state.register("ipairs", ::ipairs)
        state.register("load", ::load)
        if (state.config.unsafeStandardLibraryAccessEnabled) {
            state.register("loadfile") { context -> loadfile(context, state) }
        }
        state.register("next", nextFunction)
        registerYieldable(state, "pairs", ::pairs)
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

    private class GarbageCollectorState {
        var running: Boolean = true
        var mode: String = "incremental"
        val params: MutableMap<String, Int> = DEFAULT_GARBAGE_COLLECTOR_PARAMS
            .mapValuesTo(mutableMapOf()) { (_, value) -> encodeGarbageCollectorParam(value) }
        val function = LuaFunction { context -> collectgarbage(context, this) }
    }

    private data class LoadFileSource(
        val source: String,
        val chunkName: String,
        val bytes: ByteArray? = null,
    )

    private data class LoadFileRead(
        val source: LoadFileSource? = null,
        val error: String? = null,
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
        return if (state.config.unsafeStandardLibraryAccessEnabled) LuaPackageLibrary.open(state) else state
    }

    @JvmStatic
    public fun openIo(state: LuaState): LuaState {
        return if (state.config.unsafeStandardLibraryAccessEnabled) LuaIoLibrary.open(state) else state
    }

    @JvmStatic
    public fun openOs(state: LuaState): LuaState {
        return if (state.config.unsafeStandardLibraryAccessEnabled) LuaOsLibrary.open(state) else state
    }

    @JvmStatic
    public fun openDebug(state: LuaState): LuaState {
        return LuaDebugLibrary.open(state)
    }

    private fun assert(context: LuaCallContext): LuaReturn {
        if (!context.toBoolean(1)) {
            requireAnyArgument(context, "assert")
            val errorObject = if (context.isNone(2)) "assertion failed!" else argumentValue(context, 2)
            throw luaError(errorObject, errorLocationPrefix(context, 1))
        }
        return LuaReturn.ofValues((1..context.argumentCount).map { index -> argumentValue(context, index) })
    }

    private fun error(context: LuaCallContext): LuaReturn {
        val level = if (!context.isNone(2) && !context.isNil(2)) {
            requiredNumberInteger(context, 2, "error").toInt()
        } else {
            1
        }
        val errorObject = if (context.isNone(1)) null else argumentValue(context, 1)
        throw luaError(errorObject, errorLocationPrefix(context, level))
    }

    private fun collectgarbage(
        context: LuaCallContext,
        state: GarbageCollectorState,
    ): LuaReturn {
        return when (val option = optionalString(context, 1, "collect", "collectgarbage").substringBefore('\u0000')) {
            "collect" -> {
                System.gc()
                LuaReturn.of(0L)
            }
            "stop" -> {
                state.running = false
                LuaReturn.of(0L)
            }
            "restart" -> {
                state.running = true
                LuaReturn.of(0L)
            }
            "count" -> LuaReturn.of(usedMemoryKilobytes())
            "step" -> {
                if (!context.isNone(2) && !context.isNil(2)) {
                    requiredIntegerLikeLuaL(context, 2, "collectgarbage")
                }
                System.gc()
                LuaReturn.of(false)
            }
            "isrunning" -> LuaReturn.of(state.running)
            "incremental" -> {
                val previous = state.mode
                state.mode = "incremental"
                LuaReturn.of(previous)
            }
            "generational" -> {
                val previous = state.mode
                state.mode = "generational"
                LuaReturn.of(previous)
            }
            "param" -> {
                val parameter = requiredString(context, 2, "collectgarbage").substringBefore('\u0000')
                val encodedPrevious = state.params[parameter]
                    ?: throw LuaRuntimeException("bad argument #2 to 'collectgarbage' (invalid option '$parameter')")
                val previous = decodeGarbageCollectorParam(encodedPrevious)
                if (!context.isNone(3) && !context.isNil(3)) {
                    val value = requiredIntegerLikeLuaL(context, 3, "collectgarbage").toInt()
                    if (value >= 0) {
                        state.params[parameter] = encodeGarbageCollectorParam(value.toLong())
                    }
                }
                LuaReturn.of(previous)
            }
            else -> throw LuaRuntimeException("bad argument #1 to 'collectgarbage' (invalid option '$option')")
        }
    }

    private fun encodeGarbageCollectorParam(value: Long): Int {
        if (value >= MAX_GARBAGE_COLLECTOR_PARAM) {
            return 0xFF
        }
        val scaled = (value * 128L + 99L) / 100L
        if (scaled < 0x10L) {
            return scaled.toInt()
        }
        val log = ceilLog2(scaled + 1L) - 5
        return (((scaled shr log) - 0x10L) or ((log + 1L) shl 4)).toInt()
    }

    private fun decodeGarbageCollectorParam(encoded: Int): Long {
        var mantissa = encoded and 0xF
        var exponent = encoded ushr 4
        if (exponent > 0) {
            exponent--
            mantissa += 0x10
        }
        exponent -= 7
        val scaled = 100L * mantissa
        return if (exponent >= 0) scaled shl exponent else scaled shr -exponent
    }

    private fun ceilLog2(value: Long): Int {
        return Long.SIZE_BITS - java.lang.Long.numberOfLeadingZeros(value - 1L)
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
                sourceString.substringBefore('\u0000')
            } else {
                "=(load)"
            }
        } else {
            requiredString(context, 2, "load").substringBefore('\u0000')
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
                return LuaReturn.of(null, "attempt to yield across a C-call boundary")
            } catch (exception: LuaException) {
                return LuaReturn.of(null, errorObject(exception))
            } catch (exception: RuntimeException) {
                return LuaReturn.of(null, exception.message ?: exception::class.java.simpleName)
            }
        }
        if (isKLuaBinaryChunk(source)) {
            if ('b' !in mode) {
                return LuaReturn.of(null, binaryChunkModeError(mode))
            }
            val bytes = source.luaRawBytes()
            return if (context.isNone(4)) {
                context.loadBytecode(bytes, chunkName)
            } else {
                context.loadBytecode(bytes, chunkName, argumentValue(context, 4))
            }
        }
        if ('t' !in mode) {
            return LuaReturn.of(null, textChunkModeError(mode))
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

    private fun loadfile(context: LuaCallContext, state: LuaState): LuaReturn {
        val filename = if (context.isNone(1) || context.isNil(1)) {
            null
        } else {
            requiredString(context, 1, "loadfile").substringBefore('\u0000')
        }
        val mode = loadMode(context, 2, "loadfile")
        return loadFile(context, state, filename, mode, environmentIndex = 3)
    }

    private fun loadFile(
        context: LuaCallContext,
        state: LuaState,
        filename: String?,
        mode: String,
        environmentIndex: Int?,
    ): LuaReturn {
        val read = readLoadFileSource(filename, state)
        val source = read.source
            ?: return LuaReturn.of(null, read.error ?: "cannot read file '$filename'")
        val bytes = source.bytes ?: source.source.luaRawBytes()
        if (isKLuaBinaryChunk(bytes)) {
            if ('b' !in mode) {
                return LuaReturn.of(null, binaryChunkModeError(mode))
            }
            return if (context.isNone(3)) {
                context.loadBytecode(bytes, source.chunkName)
            } else {
                context.loadBytecode(bytes, source.chunkName, argumentValue(context, 3))
            }
        }
        if ('t' !in mode) {
            return LuaReturn.of(null, textChunkModeError(mode))
        }
        val environment = if (environmentIndex == null) {
            LoadEnvironment(null, provided = false)
        } else {
            optionalLoadEnvironment(context, environmentIndex)
        }
        return context.load(source.source, source.chunkName, environment.value, environment.provided)
    }

    private fun dofile(context: LuaCallContext, state: LuaState): LuaReturn {
        val filename = if (context.isNone(1) || context.isNil(1)) {
            null
        } else {
            requiredString(context, 1, "dofile").substringBefore('\u0000')
        }
        val read = readLoadFileSource(filename, state)
        val source = read.source
            ?: throw LuaRuntimeException(read.error ?: "cannot read file '$filename'")
        val bytes = source.bytes ?: source.source.luaRawBytes()
        val loaded = if (isKLuaBinaryChunk(bytes)) {
            context.loadBytecode(bytes, source.chunkName)
        } else {
            context.load(source.source, source.chunkName)
        }
        val function = loaded.get(1)
            ?: throw LuaRuntimeException(loaded.get(2)?.toString() ?: "cannot load file")
        return context.call(function, emptyList())
    }

    private fun readLoadFileSource(filename: String?, state: LuaState): LoadFileRead {
        try {
            if (filename == null) {
                val content = loadFileContent(state.config.standardInput.get())
                return LoadFileRead(source = LoadFileSource(content.source, "=stdin", content.bytes))
            } else {
                val bytes = Files.readAllBytes(Path.of(filename))
                val content = loadFileContent(bytes)
                return LoadFileRead(source = LoadFileSource(content.source, "@$filename", content.bytes))
            }
        } catch (error: IOException) {
            return LoadFileRead(error = error.message ?: "cannot read file '$filename'")
        } catch (error: InvalidPathException) {
            return LoadFileRead(error = error.reason.ifEmpty { "cannot read file '$filename'" })
        } catch (error: SecurityException) {
            return LoadFileRead(error = error.message ?: "cannot read file '$filename'")
        }
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

    private fun ipairs(context: LuaCallContext): LuaReturn {
        requireAnyArgument(context, "ipairs")
        return LuaReturn.ofValues(listOf(ipairsIteratorFunction, argumentValue(context, 1), 0L))
    }

    private fun ipairsNext(context: LuaCallContext): LuaReturn {
        val nextIndex = requiredNumberInteger(context, 2, "ipairs iterator") + 1L
        val value = try {
            context.getValueField(argumentValue(context, 1), nextIndex)
        } catch (error: IllegalArgumentException) {
            throw LuaRuntimeException(error.message ?: "attempt to index a ${context.typeName(1)} value")
        }
        return if (value == null) {
            LuaReturn.of(null)
        } else {
            LuaReturn.of(nextIndex, value)
        }
    }

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
            return try {
                pairsReturn(context.call(pairs, listOf(argumentValue(context, 1))))
            } catch (yield: LuaYieldException) {
                throw yield.withContinuation { arguments ->
                    pairsReturnResume(yield, arguments)
                }
            }
        }
        return LuaReturn.ofValues(listOf(nextFunction, argumentValue(context, 1), null, null))
    }

    private fun pairsReturnResume(yield: LuaYieldException, arguments: List<Any?>): LuaReturn {
        return try {
            pairsReturn(yield.continueWith(arguments))
        } catch (nextYield: LuaYieldException) {
            throw nextYield.withContinuation { nextArguments ->
                pairsReturnResume(nextYield, nextArguments)
            }
        }
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
            "number" -> rawNumberEqual(context.get(leftIndex), context.get(rightIndex))
            "string" -> context.toString(leftIndex) == context.toString(rightIndex)
            "table" -> context.getTable(leftIndex) === context.getTable(rightIndex)
            "userdata" -> context.toUserData(leftIndex) === context.toUserData(rightIndex)
            "function" -> context.getLuaValue(leftIndex) === context.getLuaValue(rightIndex)
            else -> false
        }
    }

    private fun rawNumberEqual(left: Any?, right: Any?): Boolean {
        val leftInteger = left.luaIntegerSubtype()
        val rightInteger = right.luaIntegerSubtype()
        if (leftInteger != null) {
            return if (rightInteger != null) {
                leftInteger == rightInteger
            } else {
                (right as? Number)?.toDouble()?.luaInteger()?.let { leftInteger == it } ?: false
            }
        }
        val leftNumber = (left as? Number)?.toDouble() ?: return false
        return if (rightInteger != null) {
            leftNumber.luaInteger()?.let { it == rightInteger } ?: false
        } else {
            leftNumber == (right as? Number)?.toDouble()
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
        if (context.toString(1)?.startsWith("#") == true) {
            return LuaReturn.of((context.argumentCount - 1).toLong())
        }

        val index = requiredNumberInteger(context, 1, "select")
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
            val value = argumentValue(context, 1)
            if (value !is CharSequence) {
                throw LuaRuntimeException("bad argument #1 to 'tonumber' (string expected)")
            }
            if (base !in 2L..36L) {
                throw LuaRuntimeException("bad argument #2 to 'tonumber' (base out of range)")
            }
            return LuaReturn.of(parseBasedInteger(value.toString(), base.toInt()))
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
            val arguments = (firstArgumentIndex..context.argumentCount).map { index -> argumentValue(context, index) }
            val result = if (handlerIndex == null) {
                context.call(functionIndex, arguments)
            } else {
                context.callWithErrorHandler(functionIndex, arguments, handlerIndex)
            }
            LuaReturn.ofValues(listOf(true) + result.values)
        } catch (yield: LuaYieldException) {
            throw yield.withContinuation { arguments ->
                protectedCallResume(context, yield, handlerIndex, arguments)
            }
        } catch (exit: LuaExitException) {
            throw exit
        } catch (exception: LuaException) {
            protectedCallError(
                context,
                errorObject(exception),
                handlerIndex.takeUnless {
                    exception is LuaRuntimeException && exception.errorObjectFinalized
                },
            )
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
        } catch (exit: LuaExitException) {
            throw exit
        } catch (exception: LuaException) {
            protectedCallError(
                context,
                errorObject(exception),
                handlerIndex.takeUnless {
                    exception is LuaRuntimeException && exception.errorObjectFinalized
                },
            )
        } catch (exception: RuntimeException) {
            protectedCallError(context, exception.message ?: exception::class.java.simpleName, handlerIndex)
        }
    }

    private fun protectedCallError(context: LuaCallContext, errorObject: Any?, handlerIndex: Int?): LuaReturn {
        if (handlerIndex == null) {
            return LuaReturn.of(false, normalizeErrorObject(errorObject))
        }
        return try {
            val handlerResult = context.call(handlerIndex, listOf(errorObject))
            LuaReturn.of(false, normalizeErrorObject(handlerResult.get(1)))
        } catch (_: LuaYieldException) {
            LuaReturn.of(false, "error in error handling")
        } catch (exit: LuaExitException) {
            throw exit
        } catch (_: LuaException) {
            LuaReturn.of(false, "error in error handling")
        } catch (_: RuntimeException) {
            LuaReturn.of(false, "error in error handling")
        }
    }

    private fun normalizeErrorObject(errorObject: Any?): Any = errorObject ?: "<no error object>"

    private fun luaError(errorObject: Any?, prefix: String = ""): LuaRuntimeException {
        if (errorObject == null) {
            return LuaRuntimeException("<no error object>", errorObject = null, hasErrorObject = true)
        }
        val prefixedErrorObject = if (errorObject is CharSequence && prefix.isNotEmpty()) {
            prefix + errorObject
        } else {
            errorObject
        }
        val message = when (errorObject) {
            is CharSequence -> prefixedErrorObject.toString()
            else -> luaErrorTypeName(errorObject)
        }
        return LuaRuntimeException(message, errorObject = prefixedErrorObject, hasErrorObject = true)
    }

    private fun errorLocationPrefix(context: LuaCallContext, level: Int): String {
        if (level <= 0) {
            return ""
        }
        val frame = context.luaFrames.getOrNull(level - 1) ?: return ""
        if (frame.line <= 0) {
            return ""
        }
        return "${luaShortSourceName(frame.sourceName)}:${frame.line}: "
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
        val mode = optionalString(context, index, "bt", functionName).substringBefore('\u0000')
        if ('B' in mode) {
            throw LuaRuntimeException("bad argument #$index to '$functionName' (invalid mode)")
        }
        return mode
    }

    private fun textChunkModeError(mode: String): String {
        return "attempt to load a text chunk (mode is '$mode')"
    }

    private fun binaryChunkModeError(mode: String): String {
        return "attempt to load a binary chunk (mode is '$mode')"
    }

    private fun isKLuaBinaryChunk(source: String): Boolean {
        return source.length >= 4 && source.substring(0, 4) == "KLua"
    }

    private fun isKLuaBinaryChunk(bytes: ByteArray): Boolean {
        return bytes.size >= 4 &&
            bytes[0] == 'K'.code.toByte() &&
            bytes[1] == 'L'.code.toByte() &&
            bytes[2] == 'u'.code.toByte() &&
            bytes[3] == 'a'.code.toByte()
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

    private fun requiredNumberInteger(context: LuaCallContext, index: Int, functionName: String): Long {
        return context.toInteger(index) ?: throw LuaRuntimeException(
            if (context.toNumber(index) != null) {
                "bad argument #$index to '$functionName' (number has no integer representation)"
            } else {
                "bad argument #$index to '$functionName' (number expected)"
            },
        )
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
            "userdata" -> context.get(index)?.let { value ->
                if (metatable == null && value is LuaStdlibStringValue && value.luaToStringFallbackEnabled) {
                    value.luaToString()
                } else {
                    value.typedPointerString(typeName(context, metatable, "userdata"))
                }
            } ?: "userdata"
            else -> context.typeName(index)
        }
    }

    private fun tableToLuaString(context: LuaCallContext, index: Int): String {
        val metatable = context.getMetatable(index)
        return context.getTable(index)?.typedPointerString(typeName(context, metatable, "table")) ?: context.typeName(index)
    }

    private fun tostringMetamethodResult(result: Any?): String {
        return when (result) {
            is Byte,
            is Short,
            is Int,
            is Long,
            is Float,
            is Double,
            -> luaNumberToString(result)
            is CharSequence -> result.toString()
            else -> throw LuaRuntimeException("'__tostring' must return a string")
        }
    }

    private fun typeName(context: LuaCallContext, metatable: Any?, default: String): String {
        return (context.getTableField(metatable, "__name") as? CharSequence)?.toString() ?: default
    }

    private fun Any.typedPointerString(typeName: String): String {
        return "$typeName: ${System.identityHashCode(this).toString(16)}"
    }

    private fun argumentValue(context: LuaCallContext, index: Int): Any? {
        return when (context.typeName(index)) {
            "table" -> context.getTable(index)
            "function" -> context.getLuaValue(index)
            else -> context.get(index)
        }
    }

    private fun parseNumber(text: String): Number? {
        val trimmed = text.trimLuaAsciiWhitespace()
        if (trimmed.isNamedFloatingPointLiteral()) {
            return null
        }
        val normalized = trimmed.normalizeLuaNumberDecimalPoint()
        return parseHexInteger(trimmed) ?: normalized.toLongOrNull() ?: normalized.luaFloatFromString()
    }

    private fun String.luaFloatFromString(): Double? {
        val hexadecimal = isHexNumeral()
        val pattern = if (hexadecimal) LUA_HEXADECIMAL_FLOAT_PATTERN else LUA_DECIMAL_FLOAT_PATTERN
        if (!pattern.matches(this)) {
            return null
        }
        val parseable = if (hexadecimal && indexOf('p', ignoreCase = true) < 0) "${this}p0" else this
        return parseable.toDoubleOrNull()
    }

    private fun String.normalizeLuaNumberDecimalPoint(): String {
        val decimalPoint = luaLocaleDecimalPoint()
        if (decimalPoint == '.' || decimalPoint !in this) {
            return this
        }
        if ('.' in this) {
            return this
        }
        return replace(decimalPoint, '.')
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
            val digit = trimmed[index].asciiDigitToIntOrNull(base) ?: return null
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

    private fun Char.asciiDigitToIntOrNull(base: Int): Int? {
        val value = when (this) {
            in '0'..'9' -> code - '0'.code
            in 'a'..'z' -> code - 'a'.code + 10
            in 'A'..'Z' -> code - 'A'.code + 10
            else -> return null
        }
        return value.takeIf { it < base }
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
        if (digits.isEmpty() || digits.any { digit -> digit.asciiDigitToIntOrNull(16) == null }) {
            return null
        }
        var value = BigInteger.ZERO
        val radix = BigInteger.valueOf(16L)
        for (digit in digits) {
            val hexDigit = digit.asciiDigitToIntOrNull(16) ?: return null
            value = value.multiply(radix).add(BigInteger.valueOf(hexDigit.toLong()))
        }
        if (sign < 0) {
            value = value.negate()
        }
        return value.mod(UINT64_MODULUS).toLong()
    }

    private fun String.isHexNumeral(): Boolean {
        val digitsStart = if (startsWith("-") || startsWith("+")) 1 else 0
        return regionMatches(digitsStart, "0x", 0, 2, ignoreCase = true)
    }

    private fun Any?.luaIntegerSubtype(): Long? {
        return when (this) {
            is Byte -> toLong()
            is Short -> toLong()
            is Int -> toLong()
            is Long -> this
            else -> null
        }
    }

    private fun Double.luaInteger(): Long? {
        if (!isFinite() || this < Long.MIN_VALUE.toDouble() || this >= LUA_INTEGER_EXCLUSIVE_UPPER_BOUND) {
            return null
        }
        val integer = toLong()
        return if (integer.toDouble() == this) integer else null
    }

    private val UINT64_MODULUS: BigInteger = BigInteger.ONE.shiftLeft(Long.SIZE_BITS)
    private val LUA_INTEGER_EXCLUSIVE_UPPER_BOUND = -Long.MIN_VALUE.toDouble()
    private val LUA_DECIMAL_FLOAT_PATTERN =
        Regex("""[+-]?(?:(?:[0-9]+(?:\.[0-9]*)?)|(?:\.[0-9]+))(?:[eE][+-]?[0-9]+)?""")
    private val LUA_HEXADECIMAL_FLOAT_PATTERN =
        Regex("""[+-]?0[xX](?:(?:[0-9a-fA-F]+(?:\.[0-9a-fA-F]*)?)|(?:\.[0-9a-fA-F]+))(?:[pP][+-]?[0-9]+)?""")
    private val DEFAULT_GARBAGE_COLLECTOR_PARAMS: Map<String, Long> = mapOf(
        "minormul" to 20L,
        "majorminor" to 50L,
        "minormajor" to 68L,
        "pause" to 250L,
        "stepmul" to 200L,
        "stepsize" to 9600L,
    )
    private const val MAX_GARBAGE_COLLECTOR_PARAM = 396_800L

    private fun standardOutput(): Consumer<String> {
        return Consumer { line -> println(line) }
    }

    private fun String.luaByteLength(): Long {
        return luaRawBytes().size.toLong()
    }

    private fun setFunctionField(state: LuaState, name: String, function: (LuaCallContext) -> LuaReturn) {
        state.pushFunction(function::invoke)
        state.setField(-2, name)
    }

}

internal interface LuaStdlibStringValue {
    val luaToStringFallbackEnabled: Boolean

    fun luaToString(): String

    fun disableLuaToStringFallback()
}
