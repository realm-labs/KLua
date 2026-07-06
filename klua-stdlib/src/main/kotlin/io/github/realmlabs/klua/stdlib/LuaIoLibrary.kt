package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaFunction
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState
import java.io.EOFException
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

internal object LuaIoLibrary {
    fun open(state: LuaState): LuaState {
        state.registerType(IoFileHandle::class.java) { type ->
            type.method("close") { receiver, _ -> closeHandle(receiver) }
            type.method("flush") { receiver, _ -> flushHandle(receiver) }
            type.method("lines") { receiver, context -> linesHandle(receiver, context, closeOnEnd = false) }
            type.method("read") { receiver, context -> readHandle(receiver, context) }
            type.method("seek") { receiver, context -> seekHandle(receiver, context) }
            type.method("write") { receiver, context -> writeHandle(receiver, context) }
        }

        state.newTable()
        setFunctionField(state, "close", ::ioClose)
        setFunctionField(state, "flush") { _ -> LuaReturn.of(true) }
        setFunctionField(state, "lines", ::ioLines)
        setFunctionField(state, "open", ::ioOpen)
        setFunctionField(state, "tmpfile", ::ioTmpFile)
        setFunctionField(state, "type", ::ioType)
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
            val handle = IoFileHandle(Path.of(filename), RandomAccessFile(filename, parsed.randomAccessMode))
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

    private fun ioTmpFile(context: LuaCallContext): LuaReturn {
        return try {
            val path = Files.createTempFile("klua-io-", ".tmp")
            LuaReturn.of(IoFileHandle(path, RandomAccessFile(path.toFile(), "rw"), deleteOnClose = true))
        } catch (error: IOException) {
            LuaReturn.of(null, error.message ?: error::class.java.simpleName, 1L)
        } catch (error: SecurityException) {
            LuaReturn.of(null, error.message ?: error::class.java.simpleName, 1L)
        }
    }

    private fun ioClose(context: LuaCallContext): LuaReturn {
        val handle = context.toUserData(1, IoFileHandle::class.java)
            ?: throw LuaRuntimeException("bad argument #1 to 'io.close' (FILE* expected)")
        return closeHandle(handle)
    }

    private fun ioLines(context: LuaCallContext): LuaReturn {
        if (context.isNone(1) || context.isNil(1)) {
            throw LuaRuntimeException("default input is not supported")
        }
        val filename = requiredString(context, 1, "io.lines")
        val openResult = ioOpenValue(filename, "r")
        val handle = openResult.values.firstOrNull() as? IoFileHandle ?: return openResult
        val formats = (2..context.argumentCount).map { index -> context.get(index) }
        val iterator = lineIterator(handle, formats, closeOnEnd = true)
        return LuaReturn.of(iterator, null, null, handle)
    }

    private fun ioType(context: LuaCallContext): LuaReturn {
        val handle = context.toUserData(1, IoFileHandle::class.java) ?: return LuaReturn.of(null)
        return LuaReturn.of(if (handle.closed) "closed file" else "file")
    }

    private fun closeHandle(handle: IoFileHandle): LuaReturn {
        if (handle.closed) {
            return LuaReturn.of(null, "file is already closed")
        }
        return try {
            handle.closed = true
            handle.file.close()
            if (handle.deleteOnClose && handle.path != null) {
                Files.deleteIfExists(handle.path)
            }
            LuaReturn.of(true)
        } catch (error: IOException) {
            LuaReturn.of(null, error.message ?: error::class.java.simpleName, 1L)
        }
    }

    private fun flushHandle(handle: IoFileHandle): LuaReturn {
        handle.ensureOpen()
        return try {
            handle.file.fd.sync()
            LuaReturn.of(true)
        } catch (error: IOException) {
            LuaReturn.of(null, error.message ?: error::class.java.simpleName, 1L)
        }
    }

    private fun readHandle(handle: IoFileHandle, context: LuaCallContext): LuaReturn {
        handle.ensureOpen()
        return LuaReturn.ofValues(readFormats(handle, (1..context.argumentCount).map { index -> context.get(index) }))
    }

    private fun linesHandle(handle: IoFileHandle, context: LuaCallContext, closeOnEnd: Boolean): LuaReturn {
        handle.ensureOpen()
        val formats = (1..context.argumentCount).map { index -> context.get(index) }
        return LuaReturn.of(lineIterator(handle, formats, closeOnEnd))
    }

    private fun lineIterator(handle: IoFileHandle, formats: List<Any?>, closeOnEnd: Boolean): LuaFunction {
        return LuaFunction {
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

    private fun readFormats(handle: IoFileHandle, formats: List<Any?>): List<Any?> {
        handle.ensureOpen()
        if (formats.isEmpty()) {
            return listOf(handle.readLine())
        }
        return formats.mapIndexed { index, format ->
            when (format) {
                "a", "*a" -> handle.readAll()
                "l", "*l" -> handle.readLine()
                else -> throw LuaRuntimeException("bad argument #${index + 1} to 'read' (unsupported format)")
            }
        }
    }

    private fun writeHandle(handle: IoFileHandle, context: LuaCallContext): LuaReturn {
        handle.ensureOpen()
        return try {
            for (index in 1..context.argumentCount) {
                val value = context.toString(index)
                    ?: throw LuaRuntimeException("bad argument #$index to 'write' (string expected)")
                handle.file.write(value.toByteArray(Charsets.UTF_8))
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

    private fun setFunctionField(state: LuaState, name: String, function: (LuaCallContext) -> LuaReturn) {
        state.pushFunction(function)
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
        val normalized = mode.removeSuffix("b")
        return when (normalized) {
            "r" -> IoOpenMode("r", append = false, truncate = false)
            "w" -> IoOpenMode("rw", append = false, truncate = true)
            "a" -> IoOpenMode("rw", append = true, truncate = false)
            "r+" -> IoOpenMode("rw", append = false, truncate = false)
            "w+" -> IoOpenMode("rw", append = false, truncate = true)
            "a+" -> IoOpenMode("rw", append = true, truncate = false)
            else -> null
        }
    }

    private data class IoOpenMode(
        val randomAccessMode: String,
        val append: Boolean,
        val truncate: Boolean,
    )

    private class IoFileHandle(
        val path: Path?,
        val file: RandomAccessFile,
        val deleteOnClose: Boolean = false,
    ) {
        var closed: Boolean = false

        fun ensureOpen() {
            if (closed) {
                throw LuaRuntimeException("attempt to use a closed file")
            }
        }

        fun readAll(): String {
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

        fun readLine(): String? {
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
                    break
                }
                if (value != '\r'.code) {
                    bytes += value.toByte()
                }
            }
            return if (bytes.isEmpty() && !sawLineTerminator) null else bytes.toByteArray().toString(Charsets.UTF_8)
        }
    }
}
