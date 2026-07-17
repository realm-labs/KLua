package io.github.realmlabs.klua.core.value

import io.github.realmlabs.klua.core.vm.LuaExecutionResult

import io.github.realmlabs.klua.core.bytecode.Prototype

internal sealed interface LuaValue

internal data object LuaNil : LuaValue

internal data class LuaBoolean(
    val value: Boolean,
) : LuaValue

internal data class LuaInteger(
    val value: Long,
) : LuaValue

internal data class LuaFloat(
    val value: Double,
) : LuaValue

internal class LuaString private constructor(
    inputBytes: ByteArray,
    copyInput: Boolean,
) : LuaValue {
    constructor(value: String) : this(value.luaRawBytes(), false)

    constructor(bytes: ByteArray) : this(bytes, true)

    private val bytes: ByteArray = if (copyInput) inputBytes.copyOf() else inputBytes

    private val rawHashCode: Int = bytes.contentHashCode()

    val value: String by lazy(LazyThreadSafetyMode.NONE) { bytes.toLuaByteString() }

    val byteLength: Int
        get() = bytes.size

    override fun equals(other: Any?): Boolean {
        return this === other || other is LuaString && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = rawHashCode

    fun byteCompareTo(other: LuaString): Int {
        val limit = minOf(bytes.size, other.bytes.size)
        for (index in 0 until limit) {
            val comparison = (bytes[index].toInt() and 0xff) - (other.bytes[index].toInt() and 0xff)
            if (comparison != 0) {
                return comparison
            }
        }
        return bytes.size - other.bytes.size
    }

    fun concatenatedWith(other: LuaString): LuaString {
        val combined = ByteArray(bytes.size + other.bytes.size)
        bytes.copyInto(combined)
        other.bytes.copyInto(combined, bytes.size)
        return LuaString(combined, false)
    }

    fun copyRawBytes(): ByteArray = bytes.copyOf()

    override fun toString(): String = "LuaString(value=$value)"
}

internal class LuaUserData(
    val value: Any,
    private val typeResolver: ((Any) -> LuaUserDataType?)? = null,
) : LuaValue {
    val type: LuaUserDataType?
        get() = typeResolver?.invoke(value)
}

internal class LuaUserDataType(
    private val methods: Map<String, LuaNativeFunction>,
    private val properties: Map<String, LuaUserDataProperty>,
) {
    fun method(name: String): LuaNativeFunction? = methods[name]

    fun property(name: String): LuaUserDataProperty? = properties[name]
}

internal data class LuaUserDataProperty(
    val getter: LuaNativeFunction?,
    val setter: LuaNativeFunction?,
)

internal data class LuaClosure(
    val prototype: Prototype,
    val upvalues: MutableList<LuaUpvalue> = mutableListOf(),
    val globals: LuaValue? = null,
    val environment: LuaUpvalue? = globals?.let(::LuaUpvalue),
) : LuaValue

internal class LuaNativeFunction(
    val yieldable: Boolean = false,
    private val contextualFunction: ((LuaNativeCallContext) -> List<LuaValue>)? = null,
    private val function: (List<LuaValue>) -> List<LuaValue>,
) : LuaValue {
    fun call(context: LuaNativeCallContext): List<LuaValue> {
        return contextualFunction?.invoke(context) ?: function(context.arguments)
    }

    fun withYieldable(yieldable: Boolean): LuaNativeFunction {
        return LuaNativeFunction(yieldable, contextualFunction, function)
    }

    fun copy(yieldable: Boolean = this.yieldable): LuaNativeFunction {
        return withYieldable(yieldable)
    }
}

internal open class LuaNativeCallContext(
    val arguments: List<LuaValue>,
    val isYieldable: Boolean = false,
    val callSiteName: String? = null,
    val callSiteNameWhat: String = "",
) {
    private var cachedLuaFrames: List<LuaNativeStackFrame>? = null

    val luaFrames: List<LuaNativeStackFrame>
        get() = cachedLuaFrames ?: loadLuaFrames().also { frames -> cachedLuaFrames = frames }

    protected open fun loadLuaFrames(): List<LuaNativeStackFrame> = emptyList()

    open fun setLocal(level: Int, index: Int, value: LuaValue): String? = null

    open fun setDebugHook(index: Int, mask: String, count: Int): Boolean = false

    open fun getDebugHook(): LuaNativeDebugHook? = null

    open fun call(
        index: Int,
        arguments: List<LuaValue>,
        errorHandlerIndex: Int? = null,
    ): LuaExecutionResult? = null

    open fun call(
        function: LuaValue,
        arguments: List<LuaValue>,
    ): LuaExecutionResult? = null

    open fun collectGarbage(): List<String> = emptyList()

    open fun collectGarbage(reportWarning: (String) -> Unit) {
        collectGarbage().forEach(reportWarning)
    }

    open fun isGarbageCollectorAvailable(): Boolean = true

    open fun stepGarbageCollector(reportWarning: (String) -> Unit): Boolean = false

    open fun setGarbageCollectorRunning(running: Boolean) = Unit

    open fun setGarbageCollectorStepSize(stepSize: Long) = Unit
}

internal data class LuaNativeStackFrame(
    val sourceName: String,
    val line: Int,
    val lineDefined: Int,
    val lastLineDefined: Int,
    val upvalueCount: Int,
    val parameterCount: Int,
    val isVararg: Boolean,
    val activeLines: List<Int>,
    val function: LuaValue? = null,
    val varargs: List<LuaValue> = emptyList(),
    val locals: List<LuaNativeLocalVariable> = emptyList(),
    val upvalues: List<LuaNativeUpvalue> = emptyList(),
    val globals: List<LuaNativeLocalVariable> = emptyList(),
    val callSiteName: String? = null,
    val callSiteNameWhat: String = "",
    val transferStart: Int = 0,
    val transferCount: Int = 0,
    val isTailCall: Boolean = false,
    val extraArgumentCount: Int = 0,
    val globalFunctionName: String? = null,
)

internal data class LuaNativeLocalVariable(
    val name: String,
    val value: LuaValue,
)

internal data class LuaNativeUpvalue(
    val name: String,
    val value: LuaValue,
)

internal data class LuaNativeDebugHook(
    val function: LuaValue,
    val mask: String,
    val count: Int,
)

internal class LuaUpvalue(
    var value: LuaValue,
)
