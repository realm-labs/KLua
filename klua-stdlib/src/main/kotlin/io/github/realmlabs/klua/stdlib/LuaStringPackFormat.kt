package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaRuntimeException

internal object LuaStringPackFormat {
    private const val MAX_INT_SIZE = 16L
    private const val NATIVE_MAX_ALIGN = 16L
    private const val C_SHORT_SIZE = 2L
    private const val C_INT_SIZE = 4L
    private const val C_LONG_SIZE = 8L
    private const val LUA_INTEGER_SIZE = 8L
    private const val SIZE_T_SIZE = 8L
    private const val FLOAT_SIZE = 4L
    private const val DOUBLE_SIZE = 8L
    private const val LUA_NUMBER_SIZE = 8L

    fun packSize(format: String): Long {
        val scanner = PackFormatScanner(format)
        var totalSize = 0L
        while (!scanner.isDone()) {
            val details = scanner.nextDetails(totalSize)
            if (details.option == PackOption.String || details.option == PackOption.ZeroTerminatedString) {
                throw LuaRuntimeException("bad argument #1 to 'packsize' (variable-length format)")
            }
            val size = checkedAdd(details.size, details.padding) {
                "bad argument #1 to 'packsize' (format result too large)"
            }
            totalSize = checkedAdd(totalSize, size) {
                "bad argument #1 to 'packsize' (format result too large)"
            }
        }
        return totalSize
    }

    private class PackFormatScanner(private val format: String) {
        private var cursor = 0
        private var maxAlign = 1L

        fun isDone(): Boolean = cursor >= format.length

        fun nextDetails(totalSize: Long): PackOptionDetails {
            val option = nextOption()
            var align = option.size
            if (option.option == PackOption.AlignPadding) {
                if (isDone()) {
                    throw LuaRuntimeException("bad argument #1 to 'packsize' (invalid next option for option 'X')")
                }
                val next = nextOption()
                align = next.size
                if (next.option == PackOption.Char || align == 0L) {
                    throw LuaRuntimeException("bad argument #1 to 'packsize' (invalid next option for option 'X')")
                }
            }

            val padding = if (align <= 1L || option.option == PackOption.Char) {
                0L
            } else {
                val effectiveAlign = minOf(align, maxAlign)
                if (!effectiveAlign.isPowerOfTwo()) {
                    throw LuaRuntimeException(
                        "bad argument #1 to 'packsize' (format asks for alignment not power of 2)",
                    )
                }
                alignmentPadding(totalSize, effectiveAlign)
            }
            return option.copy(padding = padding)
        }

        private fun nextOption(): PackOptionDetails {
            val option = format[cursor++]
            return when (option) {
                'b' -> PackOptionDetails(PackOption.Int, 1L)
                'B' -> PackOptionDetails(PackOption.UInt, 1L)
                'h' -> PackOptionDetails(PackOption.Int, C_SHORT_SIZE)
                'H' -> PackOptionDetails(PackOption.UInt, C_SHORT_SIZE)
                'l' -> PackOptionDetails(PackOption.Int, C_LONG_SIZE)
                'L' -> PackOptionDetails(PackOption.UInt, C_LONG_SIZE)
                'j' -> PackOptionDetails(PackOption.Int, LUA_INTEGER_SIZE)
                'J' -> PackOptionDetails(PackOption.UInt, LUA_INTEGER_SIZE)
                'T' -> PackOptionDetails(PackOption.UInt, SIZE_T_SIZE)
                'f' -> PackOptionDetails(PackOption.Float, FLOAT_SIZE)
                'n' -> PackOptionDetails(PackOption.Number, LUA_NUMBER_SIZE)
                'd' -> PackOptionDetails(PackOption.Double, DOUBLE_SIZE)
                'i' -> PackOptionDetails(PackOption.Int, readLimitedSize(C_INT_SIZE))
                'I' -> PackOptionDetails(PackOption.UInt, readLimitedSize(C_INT_SIZE))
                's' -> PackOptionDetails(PackOption.String, readLimitedSize(SIZE_T_SIZE))
                'c' -> PackOptionDetails(PackOption.Char, readRequiredSizeForChar())
                'z' -> PackOptionDetails(PackOption.ZeroTerminatedString, 0L)
                'x' -> PackOptionDetails(PackOption.Padding, 1L)
                'X' -> PackOptionDetails(PackOption.AlignPadding, 0L)
                ' ' -> PackOptionDetails(PackOption.NoOp, 0L)
                '<',
                '>',
                '=',
                -> PackOptionDetails(PackOption.NoOp, 0L)
                '!' -> {
                    maxAlign = readLimitedSize(NATIVE_MAX_ALIGN)
                    PackOptionDetails(PackOption.NoOp, 0L)
                }
                else -> throw LuaRuntimeException("invalid format option '$option'")
            }
        }

        private fun readRequiredSizeForChar(): Long {
            if (cursor >= format.length || !format[cursor].isDigit()) {
                throw LuaRuntimeException("missing size for format option 'c'")
            }
            return readSize(default = -1L)
        }

        private fun readLimitedSize(default: Long): Long {
            val size = readSize(default)
            if (size !in 1L..MAX_INT_SIZE) {
                throw LuaRuntimeException("integral size ($size) out of limits [1,$MAX_INT_SIZE]")
            }
            return size
        }

        private fun readSize(default: Long): Long {
            if (cursor >= format.length || !format[cursor].isDigit()) {
                return default
            }
            var value = 0L
            while (cursor < format.length && format[cursor].isDigit()) {
                val digit = format[cursor].digitToInt().toLong()
                value = checkedAdd(checkedMultiply(value, 10L) { "format size too large" }, digit) {
                    "format size too large"
                }
                cursor++
            }
            return value
        }
    }

    private data class PackOptionDetails(
        val option: PackOption,
        val size: Long,
        val padding: Long = 0L,
    )

    private enum class PackOption {
        Int,
        UInt,
        Float,
        Number,
        Double,
        Char,
        String,
        ZeroTerminatedString,
        Padding,
        AlignPadding,
        NoOp,
    }

    private fun alignmentPadding(totalSize: Long, align: Long): Long {
        val remainder = totalSize and (align - 1L)
        return (align - remainder) and (align - 1L)
    }

    private fun Long.isPowerOfTwo(): Boolean {
        return this > 0L && (this and (this - 1L)) == 0L
    }

    private fun checkedAdd(left: Long, right: Long, message: () -> String): Long {
        return try {
            java.lang.Math.addExact(left, right)
        } catch (_: ArithmeticException) {
            throw LuaRuntimeException(message())
        }
    }

    private fun checkedMultiply(left: Long, right: Long, message: () -> String): Long {
        return try {
            java.lang.Math.multiplyExact(left, right)
        } catch (_: ArithmeticException) {
            throw LuaRuntimeException(message())
        }
    }
}
