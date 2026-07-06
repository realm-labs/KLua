package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaFunction
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.io.PushbackInputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

internal object LuaIoLibrary {
    fun open(state: LuaState): LuaState {
        val stdin = IoFileHandle.input(System.`in`, nonClosing = true, closeResult = ::standardFileCloseResult)
        val stdout = IoFileHandle.output(System.out, nonClosing = true, closeResult = ::standardFileCloseResult)
        val stderr = IoFileHandle.output(System.err, nonClosing = true, closeResult = ::standardFileCloseResult)
        val defaultFiles = IoDefaultFiles(input = stdin, output = stdout)
        state.registerType(IoFileHandle::class.java) { type ->
            type.method("close") { receiver, _ -> closeHandle(receiver) }
            type.method("flush") { receiver, _ -> flushHandle(receiver) }
            type.method("lines") { receiver, context -> linesHandle(receiver, context, closeOnEnd = false) }
            type.method("read") { receiver, context -> readHandle(receiver, context) }
            type.method("seek") { receiver, context -> seekHandle(receiver, context) }
            type.method("setvbuf") { receiver, context -> setBufferMode(receiver, context) }
            type.method("write") { receiver, context -> writeHandle(receiver, context) }
        }

        state.newTable()
        setFunctionField(state, "close") { context -> ioClose(context, defaultFiles) }
        setFunctionField(state, "flush") { _ -> flushHandle(defaultOutput(defaultFiles)) }
        setFunctionField(state, "input") { context -> ioInput(context, defaultFiles) }
        setFunctionField(state, "lines") { context -> ioLines(context, defaultFiles) }
        setFunctionField(state, "open", ::ioOpen)
        setFunctionField(state, "output") { context -> ioOutput(context, defaultFiles) }
        setFunctionField(state, "popen", ::ioPopen)
        setFunctionField(state, "read") { context -> readHandle(defaultInput(defaultFiles), context) }
        setFunctionField(state, "tmpfile") { _ -> ioTmpFile() }
        setFunctionField(state, "type", ::ioType)
        setFunctionField(state, "write") { context -> writeHandle(defaultOutput(defaultFiles), context) }
        setHandleField(state, "stdin", stdin)
        setHandleField(state, "stdout", stdout)
        setHandleField(state, "stderr", stderr)
        state.setGlobal("io")
        return state
    }

    private fun ioOpen(context: LuaCallContext): LuaReturn {
        val filename = requiredString(context, 1, "io.open")
        val mode = if (context.isNone(2) || context.isNil(2)) {
            "r"
        } else {
            requiredString(context, 2, "io.open")
        }
        return ioOpenValue(filename, mode)
    }

    private fun ioOpenValue(filename: String, mode: String): LuaReturn {
        val parsed = parseMode(mode)
            ?: throw LuaRuntimeException("bad argument #2 to 'io.open' (invalid mode)")
        return try {
            val handle = IoFileHandle(
                Path.of(filename),
                RandomAccessFile(filename, parsed.randomAccessMode),
                readable = parsed.readable,
                writable = parsed.writable,
                appendWrites = parsed.append,
            )
            if (parsed.truncate) {
                handle.file.setLength(0L)
            }
            if (parsed.append) {
                handle.file.seek(handle.file.length())
            }
            LuaReturn.of(handle)
        } catch (error: IOException) {
            LuaReturn.of(null, error.message ?: error::class.java.simpleName, 1L)
        } catch (error: SecurityException) {
            LuaReturn.of(null, error.message ?: error::class.java.simpleName, 1L)
        }
    }

    private fun ioTmpFile(): LuaReturn {
        return try {
            val path = Files.createTempFile("klua-io-", ".tmp")
            LuaReturn.of(
                IoFileHandle(
                    path,
                    RandomAccessFile(path.toFile(), "rw"),
                    readable = true,
                    writable = true,
                    deleteOnClose = true,
                ),
            )
        } catch (error: IOException) {
            LuaReturn.of(null, error.message ?: error::class.java.simpleName, 1L)
        } catch (error: SecurityException) {
            LuaReturn.of(null, error.message ?: error::class.java.simpleName, 1L)
        }
    }

    private fun ioPopen(context: LuaCallContext): LuaReturn {
        val command = requiredString(context, 1, "io.popen")
        val mode = if (context.isNone(2) || context.isNil(2)) {
            "r"
        } else {
            requiredString(context, 2, "io.popen")
        }
        val normalizedMode = mode.removeSuffix("b").removeSuffix("t")
        if (normalizedMode != "r" && normalizedMode != "w") {
            throw LuaRuntimeException("bad argument #2 to 'io.popen' (invalid mode)")
        }
        return try {
            if (normalizedMode == "r") {
                val process = ProcessBuilder(shellCommand(command))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                LuaReturn.of(IoFileHandle.input(process.inputStream) { waitForProcessClose(process) })
            } else {
                val process = ProcessBuilder(shellCommand(command))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                LuaReturn.of(IoFileHandle.output(process.outputStream) { waitForProcessClose(process) })
            }
        } catch (error: IOException) {
            LuaReturn.of(null, error.message ?: error::class.java.simpleName, 1L)
        } catch (error: SecurityException) {
            LuaReturn.of(null, error.message ?: error::class.java.simpleName, 1L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw LuaRuntimeException("interrupted while opening process")
        }
    }

    private fun ioClose(context: LuaCallContext, defaultFiles: IoDefaultFiles): LuaReturn {
        val handle = if (context.isNone(1)) {
            defaultOutput(defaultFiles)
        } else {
            context.toUserData(1, IoFileHandle::class.java)
                ?: throw LuaRuntimeException("bad argument #1 to 'io.close' (FILE* expected)")
        }
        return closeHandle(handle)
    }

    private fun ioInput(context: LuaCallContext, defaultFiles: IoDefaultFiles): LuaReturn {
        if (!context.isNone(1) && !context.isNil(1)) {
            defaultFiles.input = defaultFileArgument(context, 1, mode = "r", functionName = "io.input")
        }
        return LuaReturn.of(defaultInput(defaultFiles))
    }

    private fun ioOutput(context: LuaCallContext, defaultFiles: IoDefaultFiles): LuaReturn {
        if (!context.isNone(1) && !context.isNil(1)) {
            defaultFiles.output = defaultFileArgument(context, 1, mode = "w", functionName = "io.output")
        }
        return LuaReturn.of(defaultOutput(defaultFiles))
    }

    private fun defaultFileArgument(
        context: LuaCallContext,
        index: Int,
        mode: String,
        functionName: String,
    ): IoFileHandle {
        val filename = context.toString(index)
        if (filename != null) {
            val result = ioOpenValue(filename, mode)
            return result.values.firstOrNull() as? IoFileHandle
                ?: throw LuaRuntimeException(result.get(2)?.toString() ?: "cannot open file '$filename'")
        }
        val handle = context.toUserData(index, IoFileHandle::class.java)
            ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (FILE* expected)")
        handle.ensureOpen()
        return handle
    }

    private fun ioLines(context: LuaCallContext, defaultFiles: IoDefaultFiles): LuaReturn {
        if (context.isNone(1) || context.isNil(1)) {
            val formats = readFormatsFromContext(context, 2, "io.lines")
            return LuaReturn.of(lineIterator(defaultInput(defaultFiles), formats, closeOnEnd = false))
        }
        val filename = requiredString(context, 1, "io.lines")
        val openResult = ioOpenValue(filename, "r")
        val handle = openResult.values.firstOrNull() as? IoFileHandle ?: return openResult
        val formats = readFormatsFromContext(context, 2, "io.lines")
        val iterator = lineIterator(handle, formats, closeOnEnd = true)
        return LuaReturn.of(iterator, null, null, handle)
    }

    private fun ioType(context: LuaCallContext): LuaReturn {
        if (context.isNone(1)) {
            throw LuaRuntimeException("bad argument #1 to 'io.type' (value expected)")
        }
        val handle = context.toUserData(1, IoFileHandle::class.java) ?: return LuaReturn.of(null)
        return LuaReturn.of(if (handle.closed) "closed file" else "file")
    }

    private fun defaultInput(defaultFiles: IoDefaultFiles): IoFileHandle {
        return defaultFiles.input ?: throw LuaRuntimeException("default input is not supported")
    }

    private fun defaultOutput(defaultFiles: IoDefaultFiles): IoFileHandle {
        return defaultFiles.output ?: throw LuaRuntimeException("default output is not supported")
    }

    private fun closeHandle(handle: IoFileHandle): LuaReturn {
        if (handle.closed) {
            throw LuaRuntimeException("attempt to use a closed file")
        }
        return try {
            val result = handle.close()
            if (handle.deleteOnClose && handle.path != null) {
                Files.deleteIfExists(handle.path)
            }
            result
        } catch (error: IOException) {
            LuaReturn.of(null, error.message ?: error::class.java.simpleName, 1L)
        }
    }

    private fun flushHandle(handle: IoFileHandle): LuaReturn {
        handle.ensureOpen()
        if (!handle.flushable) {
            return LuaReturn.of(null, "Bad file descriptor", 1L)
        }
        return try {
            handle.flush()
            LuaReturn.of(true)
        } catch (error: IOException) {
            LuaReturn.of(null, error.message ?: error::class.java.simpleName, 1L)
        }
    }

    private fun readHandle(handle: IoFileHandle, context: LuaCallContext): LuaReturn {
        handle.ensureOpen()
        if (!handle.readable) {
            return LuaReturn.of(null, "Bad file descriptor", 1L)
        }
        return LuaReturn.ofValues(readFormats(handle, readFormatsFromContext(context, 1, "read")))
    }

    private fun linesHandle(handle: IoFileHandle, context: LuaCallContext, closeOnEnd: Boolean): LuaReturn {
        handle.ensureOpen()
        val formats = readFormatsFromContext(context, 1, "lines")
        return LuaReturn.of(lineIterator(handle, formats, closeOnEnd))
    }

    private fun lineIterator(handle: IoFileHandle, formats: List<IoReadFormat>, closeOnEnd: Boolean): LuaFunction {
        return LuaFunction {
            if (handle.closed) {
                throw LuaRuntimeException("file is already closed")
            }
            val values = readFormats(handle, formats)
            if (values.firstOrNull() == null) {
                if (closeOnEnd && !handle.closed) {
                    closeHandle(handle)
                }
                LuaReturn.none()
            } else {
                LuaReturn.ofValues(values)
            }
        }
    }

    private fun readFormats(handle: IoFileHandle, formats: List<IoReadFormat>): List<Any?> {
        handle.ensureOpen()
        if (formats.isEmpty()) {
            return listOf(handle.readLine(chop = true))
        }
        val values = mutableListOf<Any?>()
        for (format in formats) {
            val value = when (format) {
                IoReadFormat.All -> handle.readAll()
                IoReadFormat.Line -> handle.readLine(chop = true)
                IoReadFormat.LineWithNewline -> handle.readLine(chop = false)
                IoReadFormat.Number -> handle.readNumber()
                is IoReadFormat.Chars -> handle.readChars(format.count)
            }
            values += value
            if (value == null) {
                break
            }
        }
        return values
    }

    private fun readFormatsFromContext(
        context: LuaCallContext,
        firstIndex: Int,
        functionName: String,
    ): List<IoReadFormat> {
        return (firstIndex..context.argumentCount).map { index ->
            val integer = context.toInteger(index)
            if (integer != null) {
                if (integer < 0L || integer > Int.MAX_VALUE) {
                    throw LuaRuntimeException("bad argument #$index to '$functionName' (out of range)")
                }
                return@map IoReadFormat.Chars(integer.toInt())
            }
            if (context.toNumber(index) != null) {
                throw LuaRuntimeException(
                    "bad argument #$index to '$functionName' (number has no integer representation)",
                )
            }
            when (val format = context.toString(index)) {
                "a", "*a" -> IoReadFormat.All
                "l", "*l" -> IoReadFormat.Line
                "L", "*L" -> IoReadFormat.LineWithNewline
                "n", "*n" -> IoReadFormat.Number
                null -> throw LuaRuntimeException("bad argument #$index to '$functionName' (string expected)")
                else -> throw LuaRuntimeException("bad argument #$index to '$functionName' (invalid format)")
            }
        }
    }

    private fun writeHandle(handle: IoFileHandle, context: LuaCallContext): LuaReturn {
        handle.ensureOpen()
        if (!handle.writable) {
            return LuaReturn.of(null, "Bad file descriptor", 1L)
        }
        return try {
            for (index in 1..context.argumentCount) {
                val value = context.toString(index)
                    ?: throw LuaRuntimeException("bad argument #$index to 'write' (string expected)")
                handle.write(value.toByteArray(Charsets.UTF_8))
            }
            LuaReturn.of(handle)
        } catch (error: IOException) {
            LuaReturn.of(null, error.message ?: error::class.java.simpleName, 1L)
        }
    }

    private fun seekHandle(handle: IoFileHandle, context: LuaCallContext): LuaReturn {
        handle.ensureOpen()
        val whence = if (context.isNone(1) || context.isNil(1)) {
            "cur"
        } else {
            requiredString(context, 1, "seek")
        }
        val offset = if (context.isNone(2) || context.isNil(2)) 0L else requiredInteger(context, 2, "seek")
        if (!handle.seekable) {
            return LuaReturn.of(null, "Illegal seek", 1L)
        }
        val base = when (whence) {
            "set" -> 0L
            "cur" -> handle.file.filePointer
            "end" -> handle.file.length()
            else -> throw LuaRuntimeException("bad argument #1 to 'seek' (invalid option '$whence')")
        }
        val position = base + offset
        if (position < 0L) {
            return LuaReturn.of(null, "Invalid argument", 1L)
        }
        return try {
            handle.file.seek(position)
            LuaReturn.of(handle.file.filePointer)
        } catch (error: IOException) {
            LuaReturn.of(null, error.message ?: error::class.java.simpleName, 1L)
        }
    }

    private fun setBufferMode(handle: IoFileHandle, context: LuaCallContext): LuaReturn {
        handle.ensureOpen()
        val mode = requiredString(context, 1, "setvbuf")
        if (mode !in FILE_BUFFER_MODES) {
            throw LuaRuntimeException("bad argument #1 to 'setvbuf' (invalid option '$mode')")
        }
        if (!context.isNone(2) && !context.isNil(2)) {
            requiredInteger(context, 2, "setvbuf")
        }
        return LuaReturn.of(true)
    }

    private fun setFunctionField(state: LuaState, name: String, function: (LuaCallContext) -> LuaReturn) {
        state.pushFunction(function)
        state.setField(-2, name)
    }

    private fun setHandleField(state: LuaState, name: String, handle: IoFileHandle) {
        state.pushUserData(handle)
        state.setField(-2, name)
    }

    private fun requiredString(context: LuaCallContext, index: Int, functionName: String): String {
        return context.toString(index)
            ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (string expected)")
    }

    private fun requiredInteger(context: LuaCallContext, index: Int, functionName: String): Long {
        return context.toInteger(index) ?: throw LuaRuntimeException(
            if (context.toNumber(index) != null) {
                "bad argument #$index to '$functionName' (number has no integer representation)"
            } else {
                "bad argument #$index to '$functionName' (number expected)"
            },
        )
    }

    private fun parseMode(mode: String): IoOpenMode? {
        if (mode.isEmpty()) {
            return null
        }
        val first = mode[0]
        if (first !in "rwa") {
            return null
        }
        var index = 1
        val update = index < mode.length && mode[index] == '+'
        if (update) {
            index++
        }
        while (index < mode.length && mode[index] == 'b') {
            index++
        }
        if (index != mode.length) {
            return null
        }
        val readable = first == 'r' || update
        val writable = first != 'r' || update
        return IoOpenMode(
            randomAccessMode = if (writable) "rw" else "r",
            readable = readable,
            writable = writable,
            append = first == 'a',
            truncate = first == 'w',
        )
    }

    private fun processCloseResult(exitCode: Int): LuaReturn {
        return if (exitCode == 0) {
            LuaReturn.of(true, "exit", 0L)
        } else {
            LuaReturn.of(null, "exit", exitCode.toLong())
        }
    }

    private fun standardFileCloseResult(): LuaReturn {
        return LuaReturn.of(null, "cannot close standard file")
    }

    private fun waitForProcessClose(process: Process): LuaReturn {
        return try {
            processCloseResult(process.waitFor())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw LuaRuntimeException("interrupted while closing process")
        }
    }

    private fun shellCommand(command: String): List<String> {
        return if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            listOf("cmd", "/c", command)
        } else {
            listOf("sh", "-c", command)
        }
    }

    private data class IoOpenMode(
        val randomAccessMode: String,
        val readable: Boolean,
        val writable: Boolean,
        val append: Boolean,
        val truncate: Boolean,
    )

    private data class IoDefaultFiles(
        var input: IoFileHandle? = null,
        var output: IoFileHandle? = null,
    )

    private class IoFileHandle private constructor(
        val path: Path?,
        private val randomAccessFile: RandomAccessFile?,
        private val inputStream: PushbackInputStream?,
        private val outputStream: OutputStream?,
        private val readableMode: Boolean,
        private val writableMode: Boolean,
        private val appendWrites: Boolean,
        val deleteOnClose: Boolean = false,
        private val nonClosing: Boolean = false,
        val closeResult: (() -> LuaReturn)? = null,
    ) {
        constructor(
            path: Path?,
            file: RandomAccessFile,
            readable: Boolean,
            writable: Boolean,
            appendWrites: Boolean = false,
            deleteOnClose: Boolean = false,
            closeResult: (() -> LuaReturn)? = null,
        ) : this(
            path,
            file,
            null,
            null,
            readable,
            writable,
            appendWrites,
            deleteOnClose,
            nonClosing = false,
            closeResult,
        )

        var closed: Boolean = false

        val file: RandomAccessFile
            get() = randomAccessFile ?: throw LuaRuntimeException("bad file descriptor")

        val seekable: Boolean
            get() = randomAccessFile != null

        val readable: Boolean
            get() = readableMode

        val writable: Boolean
            get() = writableMode

        val flushable: Boolean
            get() = randomAccessFile != null || outputStream != null

        companion object {
            fun input(
                inputStream: java.io.InputStream,
                nonClosing: Boolean = false,
                closeResult: () -> LuaReturn,
            ): IoFileHandle {
                return IoFileHandle(
                    path = null,
                    randomAccessFile = null,
                    inputStream = PushbackInputStream(inputStream, 4),
                    outputStream = null,
                    readableMode = true,
                    writableMode = false,
                    appendWrites = false,
                    nonClosing = nonClosing,
                    closeResult = closeResult,
                )
            }

            fun output(
                outputStream: OutputStream,
                nonClosing: Boolean = false,
                closeResult: () -> LuaReturn,
            ): IoFileHandle {
                return IoFileHandle(
                    path = null,
                    randomAccessFile = null,
                    inputStream = null,
                    outputStream = outputStream,
                    readableMode = false,
                    writableMode = true,
                    appendWrites = false,
                    nonClosing = nonClosing,
                    closeResult = closeResult,
                )
            }
        }

        fun ensureOpen() {
            if (closed) {
                throw LuaRuntimeException("attempt to use a closed file")
            }
        }

        fun close(): LuaReturn {
            if (nonClosing) {
                return closeResult?.invoke() ?: LuaReturn.of(null, "cannot close standard file")
            }
            closed = true
            randomAccessFile?.close()
            inputStream?.close()
            outputStream?.close()
            return closeResult?.invoke() ?: LuaReturn.of(true)
        }

        fun flush() {
            outputStream?.let { output ->
                output.flush()
                return
            }
            file.fd.sync()
        }

        fun write(bytes: ByteArray) {
            outputStream?.let { output ->
                output.write(bytes)
                return
            }
            if (appendWrites) {
                file.seek(file.length())
            }
            file.write(bytes)
        }

        fun readAll(): String {
            inputStream?.let { input ->
                return input.readAllBytes().toString(Charsets.UTF_8)
            }
            val remaining = file.length() - file.filePointer
            if (remaining <= 0L) {
                return ""
            }
            if (remaining > Int.MAX_VALUE) {
                throw LuaRuntimeException("file is too large")
            }
            val bytes = ByteArray(remaining.toInt())
            file.readFully(bytes)
            return bytes.toString(Charsets.UTF_8)
        }

        fun readChars(count: Int): String? {
            inputStream?.let { input ->
                if (count == 0) {
                    val byte = input.read()
                    return if (byte < 0) {
                        null
                    } else {
                        input.unread(byte)
                        ""
                    }
                }
                val bytes = ByteArray(count)
                var read = 0
                while (read < count) {
                    val byte = input.read()
                    if (byte < 0) {
                        break
                    }
                    bytes[read] = byte.toByte()
                    read++
                }
                return if (read == 0) null else bytes.copyOf(read).toString(Charsets.UTF_8)
            }
            if (count == 0) {
                return if (file.filePointer < file.length()) "" else null
            }
            val bytes = ByteArray(count)
            val read = file.read(bytes)
            return if (read <= 0) null else bytes.copyOf(read).toString(Charsets.UTF_8)
        }

        fun readLine(chop: Boolean): String? {
            inputStream?.let { input ->
                val bytes = mutableListOf<Byte>()
                var sawLineTerminator = false
                while (true) {
                    val value = input.read()
                    if (value < 0) {
                        break
                    }
                    if (value == '\n'.code) {
                        sawLineTerminator = true
                        if (!chop) {
                            bytes += value.toByte()
                        }
                        break
                    }
                    bytes += value.toByte()
                }
                return if (bytes.isEmpty() && !sawLineTerminator) {
                    null
                } else {
                    bytes.toByteArray().toString(Charsets.UTF_8)
                }
            }
            val bytes = mutableListOf<Byte>()
            var sawLineTerminator = false
            while (true) {
                val value = try {
                    file.readUnsignedByte()
                } catch (_: EOFException) {
                    break
                }
                if (value == '\n'.code) {
                    sawLineTerminator = true
                    if (!chop) {
                        bytes += value.toByte()
                    }
                    break
                }
                bytes += value.toByte()
            }
            return if (bytes.isEmpty() && !sawLineTerminator) null else bytes.toByteArray().toString(Charsets.UTF_8)
        }

        fun readNumber(): Any? {
            skipWhitespace()
            val token = scanLuaNumber()
            val number = token.luaNumber()
            return number
        }

        private fun scanLuaNumber(): String {
            return buildString {
                appendOptionalSign()
                if (appendHexPrefix()) {
                    var count = appendDigits(hex = true)
                    if (appendChar('.')) {
                        count += appendDigits(hex = true)
                    }
                    if (count > 0 && appendAny('p', 'P')) {
                        appendOptionalSign()
                        appendDigits(hex = false)
                    }
                } else {
                    var count = appendDigits(hex = false)
                    if (appendChar('.')) {
                        count += appendDigits(hex = false)
                    }
                    if (count > 0 && appendAny('e', 'E')) {
                        appendOptionalSign()
                        appendDigits(hex = false)
                    }
                }
            }
        }

        private fun StringBuilder.appendOptionalSign() {
            appendAny('+', '-')
        }

        private fun StringBuilder.appendHexPrefix(): Boolean {
            val beforeLength = length
            val first = readByte() ?: return false
            if (first.toChar() != '0') {
                unreadByte(first)
                return false
            }
            val second = readByte()
            if (second != null && (second.toChar() == 'x' || second.toChar() == 'X')) {
                append('0')
                append(second.toChar())
                return true
            }
            if (second != null) {
                unreadByte(second)
            }
            unreadByte(first)
            setLength(beforeLength)
            return false
        }

        private fun StringBuilder.appendDigits(hex: Boolean): Int {
            var count = 0
            while (true) {
                val value = readByte() ?: break
                val char = value.toChar()
                if (if (hex) char.isHexDigit() else char.isDigit()) {
                    append(char)
                    count++
                } else {
                    unreadByte(value)
                    break
                }
            }
            return count
        }

        private fun StringBuilder.appendChar(expected: Char): Boolean {
            val value = readByte() ?: return false
            val char = value.toChar()
            return if (char == expected) {
                append(char)
                true
            } else {
                unreadByte(value)
                false
            }
        }

        private fun StringBuilder.appendAny(first: Char, second: Char): Boolean {
            val value = readByte() ?: return false
            val char = value.toChar()
            return if (char == first || char == second) {
                append(char)
                true
            } else {
                unreadByte(value)
                false
            }
        }

        private fun skipWhitespace() {
            while (true) {
                val value = readByte() ?: return
                if (!value.toChar().isWhitespace()) {
                    unreadByte(value)
                    return
                }
            }
        }

        private fun readByte(): Int? {
            inputStream?.let { input ->
                val value = input.read()
                return if (value < 0) null else value
            }
            return try {
                file.readUnsignedByte()
            } catch (_: EOFException) {
                null
            }
        }

        private fun unreadByte(value: Int) {
            inputStream?.let { input ->
                input.unread(value)
                return
            }
            file.seek(file.filePointer - 1)
        }
    }

    private sealed interface IoReadFormat {
        data object All : IoReadFormat
        data object Line : IoReadFormat
        data object LineWithNewline : IoReadFormat
        data object Number : IoReadFormat
        data class Chars(val count: Int) : IoReadFormat
    }

    private fun Char.isHexDigit(): Boolean {
        return isDigit() || this in 'a'..'f' || this in 'A'..'F'
    }

    private fun String.luaNumber(): Any? {
        if (isEmpty()) {
            return null
        }
        val normalized = lowercase(Locale.ROOT)
        val parsed = if (normalized.startsWith("0x") || normalized.startsWith("+0x") || normalized.startsWith("-0x")) {
            parseHexNumber()
        } else {
            toLongOrNull() ?: toDoubleOrNull()
        } ?: return null
        return when (parsed) {
            is Long -> parsed
            is Double -> parsed.luaInteger() ?: parsed
            else -> null
        }
    }

    private fun String.parseHexNumber(): Any? {
        val sign = if (startsWith("-")) -1 else 1
        val unsigned = removePrefix("+").removePrefix("-")
        val body = unsigned.removePrefix("0x").removePrefix("0X")
        if (body.isEmpty()) {
            return null
        }
        if ('p' in body || 'P' in body || '.' in body) {
            val parsed = try {
                java.lang.Double.parseDouble((if (sign < 0) "-" else "") + "0x" + body)
            } catch (_: NumberFormatException) {
                return null
            }
            return parsed.luaInteger() ?: parsed
        }
        val parsed = body.toULongOrNull(16) ?: return null
        val signed = if (sign < 0) {
            if (parsed == Long.MIN_VALUE.toULong()) Long.MIN_VALUE else -(parsed.toLong())
        } else {
            parsed.toLong()
        }
        return signed
    }

    private fun Double.luaInteger(): Long? {
        if (!isFinite() || this < Long.MIN_VALUE.toDouble() || this >= LUA_INTEGER_EXCLUSIVE_UPPER_BOUND) {
            return null
        }
        val integer = toLong()
        return if (integer.toDouble() == this) integer else null
    }

    private val LUA_INTEGER_EXCLUSIVE_UPPER_BOUND = -Long.MIN_VALUE.toDouble()
    private val FILE_BUFFER_MODES = setOf("no", "full", "line")
}
