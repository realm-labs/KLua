package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaException
import io.github.realmlabs.klua.api.LuaFunction
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState
import io.github.realmlabs.klua.api.LuaVersion
import io.github.realmlabs.klua.api.LuaYieldException
import io.github.realmlabs.klua.api.LuaYieldableFunction
import io.github.realmlabs.klua.api.continueWith
import io.github.realmlabs.klua.api.withContinuation
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
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
        state.pushString(luaVersionName(state.config.version))
        state.setGlobal("_VERSION")
        state.register("assert", ::assert)
        var garbageCollectorRunning = true
        var garbageCollectorMode = "incremental"
        state.register("collectgarbage") { context ->
            val result = collectgarbage(context, garbageCollectorRunning, garbageCollectorMode)
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

    private fun luaVersionName(version: LuaVersion): String {
        return when (version) {
            LuaVersion.LUA_51 -> "Lua 5.1"
            LuaVersion.LUA_52 -> "Lua 5.2"
            LuaVersion.LUA_53 -> "Lua 5.3"
            LuaVersion.LUA_54 -> "Lua 5.4"
            LuaVersion.LUA_55 -> "Lua 5.5"
            LuaVersion.LUAJIT_21 -> "LuaJIT 2.1"
        }
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
        state.newTable()
        setFunctionField(state, "concat", ::tableConcat)
        setFunctionField(state, "insert", ::tableInsert)
        setFunctionField(state, "move", ::tableMove)
        setFunctionField(state, "pack", ::tablePack)
        setFunctionField(state, "remove", ::tableRemove)
        setFunctionField(state, "sort", ::tableSort)
        setFunctionField(state, "unpack", ::tableUnpack)
        state.setGlobal("table")
        return state
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
            throw LuaRuntimeException(context.toString(2) ?: "assertion failed!")
        }
        return LuaReturn.ofValues((1..context.argumentCount).map { index -> argumentValue(context, index) })
    }

    private fun error(context: LuaCallContext): LuaReturn {
        throw LuaRuntimeException(context.toString(1) ?: context.typeName(1))
    }

    private fun collectgarbage(context: LuaCallContext, running: Boolean, mode: String): GarbageCollectorResult {
        return when (val option = context.toString(1) ?: "collect") {
            "collect" -> {
                System.gc()
                GarbageCollectorResult(running, mode, LuaReturn.none())
            }
            "stop" -> GarbageCollectorResult(false, mode, LuaReturn.none())
            "restart" -> GarbageCollectorResult(true, mode, LuaReturn.none())
            "count" -> GarbageCollectorResult(running, mode, LuaReturn.of(usedMemoryKilobytes()))
            "step" -> {
                System.gc()
                GarbageCollectorResult(running, mode, LuaReturn.of(true))
            }
            "isrunning" -> GarbageCollectorResult(running, mode, LuaReturn.of(running))
            "incremental" -> GarbageCollectorResult(running, "incremental", LuaReturn.of(mode))
            "generational" -> GarbageCollectorResult(running, "generational", LuaReturn.of(mode))
            else -> throw LuaRuntimeException("bad argument #1 to 'collectgarbage' (invalid option '$option')")
        }
    }

    private fun usedMemoryKilobytes(): Double {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()).toDouble() / 1024.0
    }

    private fun getmetatable(context: LuaCallContext): LuaReturn {
        if (!context.isTable(1)) {
            return LuaReturn.of(null)
        }
        val metatable = context.getMetatable(1) ?: return LuaReturn.of(null)
        return LuaReturn.of(context.getTableField(metatable, "__metatable") ?: metatable)
    }

    private fun load(context: LuaCallContext): LuaReturn {
        val sourceType = context.typeName(1)
        if (sourceType != "string" && sourceType != "function") {
            throw LuaRuntimeException("bad argument #1 to 'load' (string or function expected)")
        }
        val chunkName = if (context.isNone(2) || context.isNil(2)) {
            if (sourceType == "string") {
                requiredString(context, 1, "load")
            } else {
                "=(load)"
            }
        } else {
            requiredString(context, 2, "load")
        }
        val mode = optionalString(context, 3, "bt", "load")
        if ('t' !in mode) {
            return LuaReturn.of(null, textChunkModeError(mode))
        }
        val source = if (sourceType == "string") {
            requiredString(context, 1, "load")
        } else {
            readChunkSource(context)
        }
        if (source == null) {
            return LuaReturn.of(null, "reader function must return a string")
        }
        return context.load(source, chunkName)
    }

    private fun readChunkSource(context: LuaCallContext): String? {
        val source = StringBuilder()
        while (true) {
            val chunk = context.call(1, emptyList()).get(1) ?: break
            if (chunk !is String) {
                return null
            }
            source.append(chunk)
        }
        return source.toString()
    }

    private fun loadfile(context: LuaCallContext): LuaReturn {
        val filename = requiredString(context, 1, "loadfile")
        val mode = optionalString(context, 2, "bt", "loadfile")
        if ('t' !in mode) {
            return LuaReturn.of(null, textChunkModeError(mode))
        }
        val source = try {
            Files.readString(Path.of(filename))
        } catch (error: IOException) {
            return LuaReturn.of(null, error.message ?: "cannot read file '$filename'")
        }
        return context.load(source, filename)
    }

    private fun dofile(context: LuaCallContext): LuaReturn {
        val loaded = loadfile(context)
        val function = loaded.get(1)
        if (function == null) {
            throw LuaRuntimeException(loaded.get(2)?.toString() ?: "cannot load file")
        }
        return context.call(function, emptyList())
    }

    private fun ipairs(context: LuaCallContext): LuaReturn {
        requireAnyArgument(context, "ipairs")
        val iterator = LuaFunction { iteratorContext ->
            if (!iteratorContext.isTable(1)) {
                throw LuaRuntimeException("bad argument #1 to 'ipairs iterator' (table expected)")
            }
            val nextIndex = (iteratorContext.toInteger(2) ?: 0L) + 1L
            val value = iteratorContext.getTableValue(1, nextIndex)
            if (value == null) {
                LuaReturn.of(null)
            } else {
                LuaReturn.of(nextIndex, value)
            }
        }
        return LuaReturn.ofValues(listOf(iterator, argumentValue(context, 1), 0L))
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
        if (context.isTable(1)) {
            val pairs = context.getTableField(context.getMetatable(1), "__pairs")
            if (pairs != null) {
                return context.call(pairs, listOf(argumentValue(context, 1)))
            }
        }
        return LuaReturn.ofValues(listOf(LuaFunction(::next), argumentValue(context, 1), null))
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
        if (context.argumentCount == 0) {
            return warningsEnabled
        }

        val parts = (1..context.argumentCount).map { index -> requiredString(context, index, "warn") }
        if (parts.size == 1 && parts.single().startsWith("@")) {
            return when (parts.single()) {
                "@on" -> true
                "@off" -> false
                else -> warningsEnabled
            }
        }

        if (warningsEnabled) {
            output.accept("Lua warning: ${parts.joinToString("")}")
        }
        return warningsEnabled
    }

    private fun rawequal(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(rawEqual(context, 1, 2))
    }

    private fun rawEqual(context: LuaCallContext, leftIndex: Int, rightIndex: Int): Boolean {
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
            else -> false
        }
    }

    private fun rawget(context: LuaCallContext): LuaReturn {
        if (!context.isTable(1)) {
            throw LuaRuntimeException("bad argument #1 to 'rawget' (table expected)")
        }
        requireTableKey(context, 2)
        return LuaReturn.of(context.getTableValue(1, argumentValue(context, 2)))
    }

    private fun rawlen(context: LuaCallContext): LuaReturn {
        return when {
            context.typeName(1) == "string" -> LuaReturn.of(requiredString(context, 1, "rawlen").length.toLong())
            context.isTable(1) -> LuaReturn.of(context.tableLength(1) ?: 0L)
            else -> throw LuaRuntimeException("bad argument #1 to 'rawlen' (table or string expected)")
        }
    }

    private fun rawset(context: LuaCallContext): LuaReturn {
        if (!context.isTable(1)) {
            throw LuaRuntimeException("bad argument #1 to 'rawset' (table expected)")
        }
        requireTableKey(context, 2)
        context.setTableValue(1, argumentValue(context, 2), argumentValue(context, 3))
        return LuaReturn.of(context.getTable(1))
    }

    private fun select(context: LuaCallContext): LuaReturn {
        if (context.toString(1) == "#") {
            return LuaReturn.of((context.argumentCount - 1).toLong())
        }

        val index = context.toInteger(1)
            ?: throw LuaRuntimeException("bad argument #1 to 'select' (number expected)")
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
        requireAnyArgument(context, "xpcall")
        if (context.typeName(2) != "function") {
            throw LuaRuntimeException("bad argument #2 to 'xpcall' (function expected)")
        }
        return protectedCall(context, functionIndex = 1, firstArgumentIndex = 3, handlerIndex = 2)
    }

    private fun tonumber(context: LuaCallContext): LuaReturn {
        requireAnyArgument(context, "tonumber")
        val value = argumentValue(context, 1) ?: return LuaReturn.of(null)
        if (!context.isNone(2) && !context.isNil(2)) {
            val base = context.toInteger(2)
                ?: throw LuaRuntimeException("bad argument #2 to 'tonumber' (number expected)")
            if (base !in 2L..36L) {
                throw LuaRuntimeException("bad argument #2 to 'tonumber' (base out of range)")
            }
            return when (value) {
                is CharSequence -> LuaReturn.of(value.toString().trim().toLongOrNull(base.toInt()))
                else -> throw LuaRuntimeException("bad argument #1 to 'tonumber' (string expected)")
            }
        }
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
            protectedCallError(context, exception.message ?: exception::class.java.simpleName, handlerIndex)
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
            protectedCallError(context, exception.message ?: exception::class.java.simpleName, handlerIndex)
        } catch (exception: RuntimeException) {
            protectedCallError(context, exception.message ?: exception::class.java.simpleName, handlerIndex)
        }
    }

    private fun protectedCallError(context: LuaCallContext, message: String, handlerIndex: Int?): LuaReturn {
        if (handlerIndex == null) {
            return LuaReturn.of(false, message)
        }
        return try {
            val handlerResult = context.call(handlerIndex, listOf(message))
            LuaReturn.ofValues(listOf(false) + handlerResult.values)
        } catch (yield: LuaYieldException) {
            throw yield.withContinuation { arguments ->
                protectedCallErrorResume(yield, arguments)
            }
        }
    }

    private fun protectedCallErrorResume(yield: LuaYieldException, arguments: List<Any?>): LuaReturn {
        return try {
            val handlerResult = yield.continueWith(arguments)
            LuaReturn.ofValues(listOf(false) + handlerResult.values)
        } catch (nextYield: LuaYieldException) {
            throw nextYield.withContinuation { nextArguments ->
                protectedCallErrorResume(nextYield, nextArguments)
            }
        }
    }

    private fun tableConcat(context: LuaCallContext): LuaReturn {
        if (!context.isTable(1)) {
            throw LuaRuntimeException("bad argument #1 to 'table.concat' (table expected)")
        }

        val separator = if (context.isNone(2) || context.isNil(2)) {
            ""
        } else {
            requiredString(context, 2, "table.concat")
        }
        val start = if (context.isNone(3) || context.isNil(3)) {
            1L
        } else {
            requiredInteger(context, 3, "table.concat")
        }
        val end = if (context.isNone(4) || context.isNil(4)) {
            context.tableLength(1) ?: 0L
        } else {
            requiredInteger(context, 4, "table.concat")
        }

        if (start > end) {
            return LuaReturn.of("")
        }

        val builder = StringBuilder()
        var index = start
        while (index <= end) {
            if (index > start) {
                builder.append(separator)
            }
            builder.append(tableConcatValue(context, index))
            index++
        }
        return LuaReturn.of(builder.toString())
    }

    private fun tableConcatValue(context: LuaCallContext, index: Long): String {
        val value = try {
            context.getTableValue(1, index)
        } catch (_: IllegalArgumentException) {
            null
        }
        return when (value) {
            is Byte -> value.toLong().toString()
            is Short -> value.toLong().toString()
            is Int -> value.toLong().toString()
            is Long -> value.toString()
            is Float -> value.toDouble().toString()
            is Double -> value.toString()
            is CharSequence -> value.toString()
            else -> throw LuaRuntimeException("invalid value at index $index in table for 'concat'")
        }
    }

    private fun tableInsert(context: LuaCallContext): LuaReturn {
        if (!context.isTable(1)) {
            throw LuaRuntimeException("bad argument #1 to 'table.insert' (table expected)")
        }
        val length = context.tableLength(1) ?: 0L
        val position: Long
        val valueIndex: Int
        when (context.argumentCount) {
            2 -> {
                position = length + 1L
                valueIndex = 2
            }
            3 -> {
                position = requiredInteger(context, 2, "table.insert")
                valueIndex = 3
            }
            else -> throw LuaRuntimeException("wrong number of arguments to 'table.insert'")
        }
        if (position !in 1L..(length + 1L)) {
            throw LuaRuntimeException("bad argument #2 to 'table.insert' (position out of bounds)")
        }

        var index = length
        while (index >= position) {
            context.setTableValue(1, index + 1L, context.getTableValue(1, index))
            index--
        }
        context.setTableValue(1, position, argumentValue(context, valueIndex))
        return LuaReturn.none()
    }

    private fun tablePack(context: LuaCallContext): LuaReturn {
        val table = linkedMapOf<Any, Any?>("n" to context.argumentCount.toLong())
        for (index in 1..context.argumentCount) {
            table[index.toLong()] = argumentValue(context, index)
        }
        return LuaReturn.of(table)
    }

    private fun tableMove(context: LuaCallContext): LuaReturn {
        if (!context.isTable(1)) {
            throw LuaRuntimeException("bad argument #1 to 'table.move' (table expected)")
        }
        val first = requiredInteger(context, 2, "table.move")
        val last = requiredInteger(context, 3, "table.move")
        val target = requiredInteger(context, 4, "table.move")
        val destinationIndex = if (context.isNone(5) || context.isNil(5)) {
            1
        } else {
            if (!context.isTable(5)) {
                throw LuaRuntimeException("bad argument #5 to 'table.move' (table expected)")
            }
            5
        }

        if (first > last) {
            return LuaReturn.of(context.getTable(destinationIndex))
        }

        val sameTable = context.getTable(1) === context.getTable(destinationIndex)
        if (sameTable && target > first && target <= last) {
            var offset = last - first
            while (offset >= 0L) {
                context.setTableValue(destinationIndex, target + offset, context.getTableValue(1, first + offset))
                offset--
            }
        } else {
            var offset = 0L
            while (offset <= last - first) {
                context.setTableValue(destinationIndex, target + offset, context.getTableValue(1, first + offset))
                offset++
            }
        }
        return LuaReturn.of(context.getTable(destinationIndex))
    }

    private fun tableRemove(context: LuaCallContext): LuaReturn {
        if (!context.isTable(1)) {
            throw LuaRuntimeException("bad argument #1 to 'table.remove' (table expected)")
        }
        val length = context.tableLength(1) ?: 0L
        if (length == 0L && (context.isNone(2) || context.isNil(2))) {
            return LuaReturn.of(null)
        }
        val position = if (context.isNone(2) || context.isNil(2)) {
            length
        } else {
            requiredInteger(context, 2, "table.remove")
        }
        val validPosition = position in 1L..length ||
            position == length + 1L ||
            (length == 0L && position == 0L)
        if (!validPosition) {
            throw LuaRuntimeException("bad argument #2 to 'table.remove' (position out of bounds)")
        }
        if (position > length || position == 0L) {
            return LuaReturn.of(null)
        }

        val removed = context.getTableValue(1, position)
        var index = position
        while (index < length) {
            context.setTableValue(1, index, context.getTableValue(1, index + 1L))
            index++
        }
        context.setTableValue(1, length, null)
        return LuaReturn.of(removed)
    }

    private fun tableSort(context: LuaCallContext): LuaReturn {
        if (!context.isTable(1)) {
            throw LuaRuntimeException("bad argument #1 to 'table.sort' (table expected)")
        }
        val hasComparator = !(context.isNone(2) || context.isNil(2))
        if (hasComparator && context.typeName(2) != "function") {
            throw LuaRuntimeException("bad argument #2 to 'table.sort' (function expected)")
        }

        val length = context.tableLength(1) ?: 0L
        val values = mutableListOf<Any?>()
        var index = 1L
        while (index <= length) {
            values += context.getTableValue(1, index)
            index++
        }

        for (currentIndex in 1 until values.size) {
            val value = values[currentIndex]
            var cursor = currentIndex - 1
            while (cursor >= 0 && tableSortBefore(context, value, values[cursor], hasComparator)) {
                values[cursor + 1] = values[cursor]
                cursor--
            }
            values[cursor + 1] = value
        }

        for (valueIndex in values.indices) {
            context.setTableValue(1, valueIndex.toLong() + 1L, values[valueIndex])
        }
        return LuaReturn.none()
    }

    private fun tableSortBefore(
        context: LuaCallContext,
        left: Any?,
        right: Any?,
        hasComparator: Boolean,
    ): Boolean {
        if (!hasComparator) {
            return compareTableSortValues(left, right) < 0
        }

        val leftBeforeRight = context.call(2, listOf(left, right)).get(1).isLuaTruthy()
        if (leftBeforeRight && context.call(2, listOf(right, left)).get(1).isLuaTruthy()) {
            throw LuaRuntimeException("invalid order function for sorting")
        }
        return leftBeforeRight
    }

    private fun compareTableSortValues(left: Any?, right: Any?): Int {
        val leftNumber = left.asLuaNumber()
        val rightNumber = right.asLuaNumber()
        if (leftNumber != null && rightNumber != null) {
            return leftNumber.compareTo(rightNumber)
        }
        if (left is CharSequence && right is CharSequence) {
            return left.toString().compareTo(right.toString())
        }
        throw LuaRuntimeException("attempt to compare ${tableSortTypeName(left)} with ${tableSortTypeName(right)}")
    }

    private fun Any?.asLuaNumber(): Double? {
        return when (this) {
            is Byte -> toDouble()
            is Short -> toDouble()
            is Int -> toDouble()
            is Long -> toDouble()
            is Float -> toDouble()
            is Double -> this
            else -> null
        }
    }

    private fun Any?.isLuaTruthy(): Boolean {
        return this != null && this != false
    }

    private fun tableSortTypeName(value: Any?): String {
        return when (value) {
            null -> "nil"
            is Boolean -> "boolean"
            is Number -> "number"
            is CharSequence -> "string"
            is LuaFunction -> "function"
            else -> "table"
        }
    }

    private fun tableUnpack(context: LuaCallContext): LuaReturn {
        if (!context.isTable(1)) {
            throw LuaRuntimeException("bad argument #1 to 'table.unpack' (table expected)")
        }

        val start = if (context.isNone(2) || context.isNil(2)) {
            1L
        } else {
            requiredInteger(context, 2, "table.unpack")
        }
        val end = if (context.isNone(3) || context.isNil(3)) {
            context.tableLength(1) ?: 0L
        } else {
            requiredInteger(context, 3, "table.unpack")
        }

        if (start > end) {
            return LuaReturn.none()
        }

        val values = mutableListOf<Any?>()
        var index = start
        while (index <= end) {
            values += try {
                context.getTableValue(1, index)
            } catch (_: IllegalArgumentException) {
                throw LuaRuntimeException("invalid value at index $index in table for 'unpack'")
            }
            index++
        }
        return LuaReturn.ofValues(values)
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

    private fun textChunkModeError(mode: String): String {
        return "attempt to load a text chunk (mode is '$mode')"
    }

    private fun requiredInteger(context: LuaCallContext, index: Int, functionName: String): Long {
        return context.toInteger(index)
            ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (integer expected)")
    }

    private fun requireAnyArgument(context: LuaCallContext, functionName: String) {
        if (context.isNone(1)) {
            throw LuaRuntimeException("bad argument #1 to '$functionName' (value expected)")
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
        return when (context.typeName(index)) {
            "nil" -> "nil"
            "boolean" -> context.toBoolean(index).toString()
            "number",
            "string",
            -> context.toString(index) ?: context.typeName(index)
            "thread" -> context.get(index)?.let { value ->
                "thread: ${System.identityHashCode(value).toString(16)}"
            } ?: "thread"
            "userdata" -> context.get(index)?.toString() ?: "userdata"
            else -> context.typeName(index)
        }
    }

    private fun argumentValue(context: LuaCallContext, index: Int): Any? {
        return when (context.typeName(index)) {
            "table" -> context.getTable(index)
            else -> context.get(index)
        }
    }

    private fun parseNumber(text: String): Number? {
        val trimmed = text.trim()
        if (trimmed.isNamedFloatingPointLiteral()) {
            return null
        }
        return parseHexInteger(trimmed) ?: trimmed.toLongOrNull() ?: trimmed.toDoubleOrNull()
    }

    private fun String.isNamedFloatingPointLiteral(): Boolean {
        val unsigned = trimStart('+', '-')
        return unsigned.equals("nan", ignoreCase = true) || unsigned.equals("infinity", ignoreCase = true)
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
        val parsed = digits.toULongOrNull(16) ?: return null
        return when {
            sign < 0 && parsed <= Long.MIN_VALUE.toULong() -> -parsed.toLong()
            sign > 0 && parsed <= Long.MAX_VALUE.toULong() -> parsed.toLong()
            else -> null
        }
    }

    private fun standardOutput(): Consumer<String> {
        return Consumer { line -> println(line) }
    }

    private fun setFunctionField(state: LuaState, name: String, function: (LuaCallContext) -> LuaReturn) {
        state.pushFunction(function::invoke)
        state.setField(-2, name)
    }

}
