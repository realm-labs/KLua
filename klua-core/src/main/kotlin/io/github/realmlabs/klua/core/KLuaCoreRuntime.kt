package io.github.realmlabs.klua.core

import io.github.realmlabs.klua.core.bytecode.BytecodePackageCodec
import io.github.realmlabs.klua.core.bytecode.BytecodePackageDecode
import io.github.realmlabs.klua.core.compiler.Compiler
import io.github.realmlabs.klua.core.compiler.CompilerException
import io.github.realmlabs.klua.core.bytecode.Prototype
import io.github.realmlabs.klua.core.lexer.LexerException
import io.github.realmlabs.klua.core.parser.ParserException
import io.github.realmlabs.klua.core.value.LuaBoolean
import io.github.realmlabs.klua.core.value.LuaClosure
import io.github.realmlabs.klua.core.value.LuaFloat
import io.github.realmlabs.klua.core.value.LuaInteger
import io.github.realmlabs.klua.core.value.LuaNil
import io.github.realmlabs.klua.core.value.LuaNativeCallContext
import io.github.realmlabs.klua.core.value.LuaNativeFunction
import io.github.realmlabs.klua.core.value.LuaNativeStackFrame
import io.github.realmlabs.klua.core.value.LuaString
import io.github.realmlabs.klua.core.value.LuaTable
import io.github.realmlabs.klua.core.value.LuaUserData
import io.github.realmlabs.klua.core.value.LuaUserDataProperty
import io.github.realmlabs.klua.core.value.LuaUserDataType
import io.github.realmlabs.klua.core.value.LuaValue
import io.github.realmlabs.klua.core.value.luaRawBytes
import io.github.realmlabs.klua.core.vm.LuaExecutionResult
import io.github.realmlabs.klua.core.vm.LuaVm
import io.github.realmlabs.klua.core.vm.LuaVmDebugObserver
import io.github.realmlabs.klua.core.vm.LuaVmException
import io.github.realmlabs.klua.core.vm.LuaVmMetatableProvider
import io.github.realmlabs.klua.core.vm.LuaVmStackFrame
import io.github.realmlabs.klua.core.vm.LuaYieldContinuation
import io.github.realmlabs.klua.core.vm.LuaYieldSignal
import io.github.realmlabs.klua.core.vm.LuaYieldSignalContinuation
import java.util.IdentityHashMap

public object KLuaCoreRuntime {
    public fun compile(source: String, chunkName: String): KLuaCoreLoad {
        return try {
            KLuaCoreLoad.Success(KLuaCoreChunk(Compiler.compile(source, chunkName, isVarargChunk = true)))
        } catch (error: LexerException) {
            KLuaCoreLoad.SyntaxError(error.message ?: "lexer error")
        } catch (error: ParserException) {
            KLuaCoreLoad.SyntaxError(error.message ?: "parser error")
        } catch (error: CompilerException) {
            KLuaCoreLoad.SyntaxError(error.message ?: "compiler error")
        }
    }

    public fun compileBytecode(source: String, chunkName: String): KLuaCoreBytecodeLoad {
        return when (val load = compile(source, chunkName)) {
            is KLuaCoreLoad.Success -> KLuaCoreBytecodeLoad.Success(BytecodePackageCodec.encode(load.chunk.prototype))
            is KLuaCoreLoad.SyntaxError -> KLuaCoreBytecodeLoad.SyntaxError(load.message)
        }
    }

    public fun loadBytecode(bytes: ByteArray): KLuaCoreLoad {
        return when (val decoded = BytecodePackageCodec.decode(bytes)) {
            is BytecodePackageDecode.Decoded -> KLuaCoreLoad.Success(KLuaCoreChunk(decoded.prototype))
            is BytecodePackageDecode.Invalid -> KLuaCoreLoad.SyntaxError(decoded.reason)
        }
    }

    public fun dumpFunctionBytecode(function: KLuaCoreValue.FunctionValue, strip: Boolean = false): ByteArray? {
        val closure = function.sourceFunction as? LuaClosure ?: return null
        val prototype = if (strip) closure.prototype.strippedForDump() else closure.prototype
        return BytecodePackageCodec.encode(prototype)
    }

    public fun execute(source: String, chunkName: String): KLuaCoreExecution {
        return when (val load = compile(source, chunkName)) {
            is KLuaCoreLoad.Success -> execute(load.chunk)
            is KLuaCoreLoad.SyntaxError -> KLuaCoreExecution.SyntaxError(load.message)
        }
    }

    public fun execute(chunk: KLuaCoreChunk): KLuaCoreExecution {
        return execute(chunk, emptyList())
    }

    public fun execute(chunk: KLuaCoreChunk, arguments: List<KLuaCoreValue>): KLuaCoreExecution {
        return execute(chunk, arguments, KLuaCoreGlobals())
    }

    public fun execute(
        chunk: KLuaCoreChunk,
        arguments: List<KLuaCoreValue>,
        globals: KLuaCoreGlobals,
        limits: KLuaCoreExecutionLimits = KLuaCoreExecutionLimits(),
    ): KLuaCoreExecution {
        val vmArguments = arguments.map { value ->
            value.toLuaValueOrNull(globals)
                ?: run {
                    return KLuaCoreExecution.RuntimeError("cannot pass ${value.publicTypeName()} as Lua argument")
                }
        }
        return try {
            KLuaCoreExecution.Success(
                LuaVm(
                    globals.table,
                    globals.environment,
                    metatables = globals.vmMetatableProvider,
                    instructionLimit = limits.instructionLimit,
                ).execute(chunk.prototype, vmArguments).map { value ->
                    toPublicValue(value, globals)
                },
            )
        } catch (error: LuaVmException) {
            KLuaCoreExecution.RuntimeError(
                error.message ?: "runtime error",
                error.sourceName,
                error.line,
                error.rootCause(),
                error.luaFrames.toCoreStackFrames(),
                error.errorObject?.let { toPublicValue(it, globals) },
            )
        }
    }

    public fun lessThan(
        left: KLuaCoreValue,
        right: KLuaCoreValue,
        globals: KLuaCoreGlobals,
    ): KLuaCoreComparison {
        return compareValues(left, right, globals) { vm, luaLeft, luaRight ->
            vm.lessThan(luaLeft, luaRight)
        }
    }

    public fun luaEquals(
        left: KLuaCoreValue,
        right: KLuaCoreValue,
        globals: KLuaCoreGlobals,
    ): KLuaCoreComparison {
        return compareValues(left, right, globals) { vm, luaLeft, luaRight ->
            vm.luaEquals(luaLeft, luaRight)
        }
    }

    private fun compareValues(
        left: KLuaCoreValue,
        right: KLuaCoreValue,
        globals: KLuaCoreGlobals,
        compare: (LuaVm, LuaValue, LuaValue) -> Boolean,
    ): KLuaCoreComparison {
        val tableCache = IdentityHashMap<KLuaCoreValue.TableValue, LuaTable>()
        val luaLeft = left.toLuaValueOrNull(globals, tableCache)
            ?: return KLuaCoreComparison.RuntimeError("cannot compare ${left.publicTypeName()}")
        val luaRight = right.toLuaValueOrNull(globals, tableCache)
            ?: return KLuaCoreComparison.RuntimeError("cannot compare ${right.publicTypeName()}")
        return try {
            val vm = LuaVm(
                globals.table,
                globals.environment,
                metatables = globals.vmMetatableProvider,
            )
            KLuaCoreComparison.Success(compare(vm, luaLeft, luaRight))
        } catch (error: LuaVmException) {
            KLuaCoreComparison.RuntimeError(
                error.message ?: "runtime error",
                error.sourceName,
                error.line,
                error.rootCause(),
                error.luaFrames.toCoreStackFrames(),
                error.errorObject?.let { toPublicValue(it, globals) },
            )
        }
    }

    public fun equal(
        left: KLuaCoreValue,
        right: KLuaCoreValue,
        globals: KLuaCoreGlobals,
    ): KLuaCoreComparison {
        return compareValues(left, right, globals) { vm, luaLeft, luaRight ->
            vm.equal(luaLeft, luaRight)
        }
    }

    public fun canCreateCoroutine(function: KLuaCoreValue.FunctionValue): Boolean {
        return function.sourceFunction != null
    }

    public fun createCoroutine(
        function: KLuaCoreValue.FunctionValue,
        globals: KLuaCoreGlobals,
        limits: KLuaCoreExecutionLimits = KLuaCoreExecutionLimits(),
    ): KLuaCoreCoroutine? {
        val sourceFunction = function.sourceFunction ?: return null
        return KLuaCoreCoroutine(sourceFunction, function.sourceGlobals ?: globals, limits)
    }

    public fun createFunctionValue(
        function: KLuaCoreFunction,
        yieldable: Boolean = false,
    ): KLuaCoreValue.FunctionValue {
        return KLuaCoreValue.FunctionValue(function).also { functionValue ->
            functionValue.yieldable = yieldable
        }
    }

    public fun createContextFunctionValue(
        function: KLuaCoreContextFunction,
        yieldable: Boolean = false,
    ): KLuaCoreValue.FunctionValue {
        return KLuaCoreValue.FunctionValue { arguments ->
            function.call(KLuaCoreCallContext(arguments, emptyList(), isYieldable = yieldable))
        }.also { functionValue ->
            functionValue.contextFunction = function
            functionValue.yieldable = yieldable
        }
    }

    public fun createChunkFunctionValue(
        chunk: KLuaCoreChunk,
        globals: KLuaCoreGlobals,
    ): KLuaCoreValue.FunctionValue {
        return createChunkFunctionValue(chunk, globals, globals.table)
    }

    public fun createChunkFunctionValue(
        chunk: KLuaCoreChunk,
        globals: KLuaCoreGlobals,
        environment: KLuaCoreValue,
    ): KLuaCoreValue.FunctionValue? {
        val environmentValue = environment.toLuaValueOrNull(globals) ?: return null
        return createChunkFunctionValue(chunk, globals, environmentValue)
    }

    private fun createChunkFunctionValue(
        chunk: KLuaCoreChunk,
        globals: KLuaCoreGlobals,
        environmentValue: LuaValue,
    ): KLuaCoreValue.FunctionValue {
        val closure = LuaClosure(chunk.prototype, globals = environmentValue)
        return KLuaCoreValue.FunctionValue { arguments ->
            callPublicLuaFunction(closure, arguments, globals)
        }.also { functionValue ->
            functionValue.sourceFunction = closure
            functionValue.sourceGlobals = globals
            functionValue.yieldable = true
        }
    }

    public fun rawEqual(left: KLuaCoreValue, right: KLuaCoreValue): Boolean {
        if (left.publicTypeName() != right.publicTypeName()) {
            return false
        }
        return when (left) {
            KLuaCoreValue.Nil -> true
            is KLuaCoreValue.BooleanValue -> left.value == (right as KLuaCoreValue.BooleanValue).value
            is KLuaCoreValue.IntegerValue -> rawNumberEqual(left, right)
            is KLuaCoreValue.NumberValue -> rawNumberEqual(left, right)
            is KLuaCoreValue.StringValue -> left.value.luaRawBytes().contentEquals(
                (right as KLuaCoreValue.StringValue).value.luaRawBytes(),
            )
            is KLuaCoreValue.FunctionValue -> {
                val rightFunction = right as KLuaCoreValue.FunctionValue
                when {
                    left.sourceFunction != null && rightFunction.sourceFunction != null ->
                        left.sourceFunction === rightFunction.sourceFunction
                    else -> left.function === rightFunction.function
                }
            }
            is KLuaCoreValue.TableValue -> {
                val rightTable = right as KLuaCoreValue.TableValue
                when {
                    left.sourceTable != null && rightTable.sourceTable != null ->
                        left.sourceTable === rightTable.sourceTable
                    else -> left === rightTable
                }
            }
            is KLuaCoreValue.UserDataValue -> left.value === (right as KLuaCoreValue.UserDataValue).value
            is KLuaCoreValue.UnsupportedValue -> left === right
        }
    }

    public fun sameFunctionIdentity(left: KLuaCoreValue.FunctionValue, right: KLuaCoreValue.FunctionValue): Boolean {
        val leftSource = left.sourceFunction
        val rightSource = right.sourceFunction
        return if (leftSource != null || rightSource != null) {
            leftSource === rightSource
        } else {
            left.function === right.function
        }
    }

    public fun callFunction(
        function: KLuaCoreValue.FunctionValue,
        arguments: List<KLuaCoreValue>,
        globals: KLuaCoreGlobals,
        isYieldable: Boolean = true,
        limits: KLuaCoreExecutionLimits = KLuaCoreExecutionLimits(),
    ): KLuaCoreCallResult {
        val sourceFunction = function.sourceFunction
        if (sourceFunction != null) {
            return callPublicLuaFunction(sourceFunction, arguments, function.sourceGlobals ?: globals, isYieldable, limits)
        }
        val contextFunction = function.contextFunction
        if (contextFunction != null) {
            return contextFunction.call(KLuaCoreCallContext(arguments, emptyList(), isYieldable = isYieldable))
        }
        return function.function.call(arguments)
    }

    public fun getFunctionDebugInfo(function: KLuaCoreValue.FunctionValue): KLuaCoreFunctionDebugInfo? {
        val closure = function.sourceFunction as? LuaClosure ?: return null
        return KLuaCoreFunctionDebugInfo(
            sourceName = closure.prototype.sourceName,
            lineDefined = closure.prototype.lineDefined,
            lastLineDefined = closure.prototype.lastLineDefined,
            upvalueCount = closure.prototype.upvalues.size,
            parameterCount = closure.prototype.numParams,
            isVararg = closure.prototype.isVararg,
            activeLines = closure.prototype.validBreakpointLines.toList(),
            parameterNames = closure.prototype.localVars
                .take(closure.prototype.numParams)
                .map { local -> local.name },
            localNames = closure.prototype.localVars
                .filter { local -> local.startPc <= 0 && 0 < local.endPc }
                .map { local -> local.name },
        )
    }

    public fun getStringMetatable(globals: KLuaCoreGlobals): KLuaCoreValue.TableValue? {
        return globals.stringMetatable?.let { metatable -> toPublicValue(metatable, globals) as KLuaCoreValue.TableValue }
    }

    public fun getRawTypeMetatable(globals: KLuaCoreGlobals, typeName: String): KLuaCoreValue.TableValue? {
        return when (typeName) {
            "string" -> getStringMetatable(globals)
            else -> globals.rawTypeMetatables[typeName]
                ?.let { metatable -> toPublicValue(metatable, globals) as KLuaCoreValue.TableValue }
        }
    }

    public fun setStringMetatable(globals: KLuaCoreGlobals, metatable: KLuaCoreValue.TableValue?) {
        globals.stringMetatableConfigured = true
        globals.stringMetatable = metatable?.toLuaValueOrNull(globals) as? LuaTable
    }

    public fun setRawTypeMetatable(globals: KLuaCoreGlobals, typeName: String, metatable: KLuaCoreValue.TableValue?) {
        if (typeName == "string") {
            setStringMetatable(globals, metatable)
            return
        }
        val luaMetatable = metatable?.toLuaValueOrNull(globals) as? LuaTable
        if (luaMetatable == null) {
            globals.rawTypeMetatables.remove(typeName)
        } else {
            globals.rawTypeMetatables[typeName] = luaMetatable
        }
    }

    public fun setUserDataMetatable(globals: KLuaCoreGlobals, value: Any, metatable: KLuaCoreValue.TableValue?) {
        val luaMetatable = metatable?.toLuaValueOrNull(globals) as? LuaTable
        if (luaMetatable == null) {
            globals.userDataMetatables.remove(value)
        } else {
            globals.userDataMetatables[value] = luaMetatable
        }
    }

    public fun getUpvalue(
        function: KLuaCoreValue.FunctionValue,
        index: Int,
        globals: KLuaCoreGlobals,
    ): KLuaCoreUpvalue? {
        if (index <= 0) {
            return null
        }
        val closure = function.sourceFunction as? LuaClosure ?: return null
        val zeroIndex = index - 1
        val name = closure.prototype.upvalueNames.getOrNull(zeroIndex) ?: return null
        val upvalue = closure.upvalues.getOrNull(zeroIndex) ?: return null
        return KLuaCoreUpvalue(name, toPublicValue(upvalue.value, globals))
    }

    public fun getUpvalueId(
        function: KLuaCoreValue.FunctionValue,
        index: Int,
    ): KLuaCoreValue? {
        if (index <= 0) {
            return null
        }
        val closure = function.sourceFunction as? LuaClosure ?: return null
        val zeroIndex = index - 1
        val upvalue = closure.upvalues.getOrNull(zeroIndex) ?: return null
        return KLuaCoreValue.UserDataValue(upvalue)
    }

    public fun joinUpvalue(
        targetFunction: KLuaCoreValue.FunctionValue,
        targetIndex: Int,
        sourceFunction: KLuaCoreValue.FunctionValue,
        sourceIndex: Int,
    ): Boolean {
        if (targetIndex <= 0 || sourceIndex <= 0) {
            return false
        }
        val targetClosure = targetFunction.sourceFunction as? LuaClosure ?: return false
        val sourceClosure = sourceFunction.sourceFunction as? LuaClosure ?: return false
        val targetZeroIndex = targetIndex - 1
        val sourceZeroIndex = sourceIndex - 1
        val sourceUpvalue = sourceClosure.upvalues.getOrNull(sourceZeroIndex) ?: return false
        if (targetZeroIndex !in targetClosure.upvalues.indices) {
            return false
        }
        targetClosure.upvalues[targetZeroIndex] = sourceUpvalue
        return true
    }

    public fun setUpvalue(
        function: KLuaCoreValue.FunctionValue,
        index: Int,
        value: KLuaCoreValue,
        globals: KLuaCoreGlobals,
    ): String? {
        if (index <= 0) {
            return null
        }
        val closure = function.sourceFunction as? LuaClosure ?: return null
        val zeroIndex = index - 1
        val name = closure.prototype.upvalueNames.getOrNull(zeroIndex) ?: return null
        val upvalue = closure.upvalues.getOrNull(zeroIndex) ?: return null
        upvalue.value = value.toLuaValueOrNull(globals) ?: return null
        return name
    }
}

public class KLuaCoreChunk internal constructor(
    internal val prototype: Prototype,
)

public data class KLuaCoreExecutionLimits(
    public val instructionLimit: Long = 0,
) {
    init {
        require(instructionLimit >= 0) { "instructionLimit must be non-negative" }
    }
}

public class KLuaCoreGlobals internal constructor(
    internal val table: LuaTable = LuaTable(),
    internal val environment: LuaValue = table,
) {
    private val userDataTypes = linkedMapOf<Class<*>, MutableMap<String, LuaNativeFunction>>()
    private val userDataProperties = linkedMapOf<Class<*>, MutableMap<String, LuaUserDataProperty>>()
    internal var stringLibrary: LuaTable? = null
        private set
    internal var stringMetatable: LuaTable? = null
    internal var stringMetatableConfigured: Boolean = false
    internal val rawTypeMetatables: MutableMap<String, LuaTable> = linkedMapOf()
    internal val userDataMetatables: MutableMap<Any, LuaTable> = IdentityHashMap()
    internal val vmMetatableProvider: LuaVmMetatableProvider = object : LuaVmMetatableProvider {
        override fun stringMetatable(): LuaTable? = this@KLuaCoreGlobals.stringMetatable

        override fun isStringMetatableConfigured(): Boolean = stringMetatableConfigured

        override fun rawTypeMetatable(typeName: String): LuaTable? = this@KLuaCoreGlobals.rawTypeMetatable(typeName)

        override fun userDataMetatable(value: Any): LuaTable? = this@KLuaCoreGlobals.userDataMetatable(value)
    }

    internal fun rawTypeMetatable(typeName: String): LuaTable? {
        return when (typeName) {
            "string" -> stringMetatable
            else -> rawTypeMetatables[typeName]
        }
    }

    internal fun userDataMetatable(value: Any): LuaTable? = userDataMetatables[value]

    public companion object {
        @JvmStatic
        public fun create(): KLuaCoreGlobals = KLuaCoreGlobals()
    }

    public fun get(name: String): KLuaCoreValue = toPublicValue(table.rawGet(LuaString(name)), this)

    public fun set(name: String, value: KLuaCoreValue): Boolean {
        val luaValue = value.toLuaValueOrNull(this) ?: return false
        table.rawSet(LuaString(name), luaValue)
        if (name == "string" && stringLibrary == null && luaValue is LuaTable) {
            stringLibrary = luaValue
            stringMetatable = LuaTable().also { metatable ->
                metatable.rawSet(LuaString("__index"), luaValue)
            }
            stringMetatableConfigured = true
        }
        return true
    }

    public fun setGlobalTable(name: String) {
        table.rawSet(LuaString(name), table)
    }

    public fun withEnvironment(environment: KLuaCoreValue): KLuaCoreGlobals? {
        val environmentValue = environment.toLuaValueOrNull(this) ?: return null
        return KLuaCoreGlobals(table, environmentValue).also { globals ->
            userDataTypes.forEach { (type, methods) ->
                globals.userDataTypes[type] = methods.toMutableMap()
            }
            userDataProperties.forEach { (type, properties) ->
                globals.userDataProperties[type] = properties.toMutableMap()
            }
            globals.stringLibrary = stringLibrary
            globals.stringMetatable = stringMetatable
            globals.stringMetatableConfigured = stringMetatableConfigured
            globals.rawTypeMetatables.putAll(rawTypeMetatables)
            globals.userDataMetatables.putAll(userDataMetatables)
        }
    }

    public fun setUserDataMetatable(value: Any, metatable: KLuaCoreValue.TableValue?) {
        if (metatable == null) {
            userDataMetatables.remove(value)
            return
        }
        val luaMetatable = metatable.toLuaValueOrNull(this) as? LuaTable
            ?: throw LuaVmException("metatable must be a table")
        userDataMetatables[value] = luaMetatable
    }

    public fun setFunction(name: String, function: KLuaCoreFunction) {
        table.rawSet(
            LuaString(name),
            LuaNativeFunction { arguments -> callCoreFunction(function, arguments, this) },
        )
    }

    public fun setTableField(tableValue: KLuaCoreValue.TableValue, key: KLuaCoreValue, value: KLuaCoreValue) {
        val luaKey = key.toLuaValueOrNull(this)
            ?: throw LuaVmException("cannot use ${key.publicTypeName()} as Lua table key")
        val luaValue = value.toLuaValueOrNull(this)
            ?: throw LuaVmException("cannot use ${value.publicTypeName()} as Lua table value")
        if (value == KLuaCoreValue.Nil) {
            tableValue.fields.remove(key)
        } else {
            tableValue.fields[key] = value
        }
        tableValue.sourceTable?.rawSet(luaKey, luaValue)
    }

    public fun <T : Any> setUserDataMethod(
        type: Class<T>,
        name: String,
        method: KLuaCoreUserDataMethod<T>,
    ) {
        val methods = userDataTypes.getOrPut(type) { linkedMapOf() }
        methods[name] = LuaNativeFunction { arguments ->
            val receiver = arguments.firstOrNull() as? LuaUserData
                ?: throw LuaVmException("userdata method '$name' missing receiver")
            if (!type.isInstance(receiver.value)) {
                throw LuaVmException("userdata method '$name' expected ${type.name}")
            }
            callCoreUserDataMethod(type.cast(receiver.value), method, arguments.drop(1), this)
        }
    }

    public fun <T : Any> setUserDataProperty(
        type: Class<T>,
        name: String,
        getter: KLuaCoreUserDataGetter<T>?,
        setter: KLuaCoreUserDataSetter<T>?,
    ) {
        val properties = userDataProperties.getOrPut(type) { linkedMapOf() }
        properties[name] = LuaUserDataProperty(
            getter = getter?.let { propertyGetter ->
                LuaNativeFunction { arguments ->
                    val receiver = arguments.firstOrNull() as? LuaUserData
                        ?: throw LuaVmException("userdata property '$name' missing receiver")
                    if (!type.isInstance(receiver.value)) {
                        throw LuaVmException("userdata property '$name' expected ${type.name}")
                    }
                    callCoreUserDataGetter(type.cast(receiver.value), propertyGetter, this)
                }
            },
            setter = setter?.let { propertySetter ->
                LuaNativeFunction { arguments ->
                    val receiver = arguments.firstOrNull() as? LuaUserData
                        ?: throw LuaVmException("userdata property '$name' missing receiver")
                    if (!type.isInstance(receiver.value)) {
                        throw LuaVmException("userdata property '$name' expected ${type.name}")
                    }
                    val value = arguments.getOrNull(1)
                        ?: throw LuaVmException("userdata property '$name' missing value")
                    callCoreUserDataSetter(type.cast(receiver.value), propertySetter, value, this)
                }
            },
        )
    }

    internal fun userDataType(value: Any): LuaUserDataType? {
        val methods = linkedMapOf<String, LuaNativeFunction>()
        for ((_, registeredMethods) in applicableUserDataEntries(userDataTypes, value)) {
            methods.putAll(registeredMethods)
        }
        val properties = linkedMapOf<String, LuaUserDataProperty>()
        for ((_, registeredProperties) in applicableUserDataEntries(userDataProperties, value)) {
            for ((name, property) in registeredProperties) {
                properties[name] = properties[name]?.mergeWith(property) ?: property
            }
        }
        if (methods.isEmpty() && properties.isEmpty()) {
            return null
        }
        return LuaUserDataType(methods.toMap(), properties.toMap())
    }

    private fun <T> applicableUserDataEntries(
        registrations: Map<Class<*>, T>,
        value: Any,
    ): List<Map.Entry<Class<*>, T>> {
        return registrations.entries
            .filter { (type, _) -> type.isInstance(value) }
            .sortedWith { left, right -> compareUserDataSpecificity(left.key, right.key) }
    }

    private fun compareUserDataSpecificity(left: Class<*>, right: Class<*>): Int {
        return when {
            left == right -> 0
            left.isAssignableFrom(right) -> -1
            right.isAssignableFrom(left) -> 1
            else -> 0
        }
    }
}

private fun LuaUserDataProperty.mergeWith(moreSpecific: LuaUserDataProperty): LuaUserDataProperty {
    return LuaUserDataProperty(
        getter = moreSpecific.getter ?: getter,
        setter = moreSpecific.setter ?: setter,
    )
}

public fun interface KLuaCoreFunction {
    public fun call(arguments: List<KLuaCoreValue>): KLuaCoreCallResult
}

public fun interface KLuaCoreContextFunction {
    public fun call(context: KLuaCoreCallContext): KLuaCoreCallResult
}

public class KLuaCoreCallContext internal constructor(
    public val arguments: List<KLuaCoreValue>,
    private val luaFramesProvider: () -> List<KLuaCoreStackFrame>,
    public val isYieldable: Boolean,
    private val setLocalValue: (level: Int, index: Int, value: KLuaCoreValue) -> String?,
    private val setDebugHookValue: (index: Int, mask: String, count: Int) -> Boolean,
    private val getDebugHookValue: () -> KLuaCoreDebugHook?,
) {
    private var cachedLuaFrames: List<KLuaCoreStackFrame>? = null

    public constructor(
        arguments: List<KLuaCoreValue>,
        luaFrames: List<KLuaCoreStackFrame>,
        isYieldable: Boolean = false,
        setLocalValue: (level: Int, index: Int, value: KLuaCoreValue) -> String? = { _, _, _ -> null },
        setDebugHookValue: (index: Int, mask: String, count: Int) -> Boolean = { _, _, _ -> false },
        getDebugHookValue: () -> KLuaCoreDebugHook? = { null },
    ) : this(
        arguments,
        luaFramesProvider = { luaFrames },
        isYieldable,
        setLocalValue,
        setDebugHookValue,
        getDebugHookValue,
    )

    public val luaFrames: List<KLuaCoreStackFrame>
        get() = cachedLuaFrames ?: luaFramesProvider().also { frames -> cachedLuaFrames = frames }

    public fun setLocal(level: Int, index: Int, value: KLuaCoreValue): String? {
        return setLocalValue(level, index, value)
    }

    public fun setDebugHook(index: Int, mask: String, count: Int): Boolean {
        return setDebugHookValue(index, mask, count)
    }

    public fun getDebugHook(): KLuaCoreDebugHook? {
        return getDebugHookValue()
    }
}

public fun interface KLuaCoreUserDataMethod<T : Any> {
    public fun call(receiver: T, arguments: List<KLuaCoreValue>): KLuaCoreCallResult
}

public fun interface KLuaCoreUserDataGetter<T : Any> {
    public fun get(receiver: T): KLuaCoreCallResult
}

public fun interface KLuaCoreUserDataSetter<T : Any> {
    public fun set(receiver: T, value: KLuaCoreValue): KLuaCoreCallResult
}

public sealed interface KLuaCoreCallResult {
    public data class Success(
        public val values: List<KLuaCoreValue>,
    ) : KLuaCoreCallResult

    public data class Yielded(
        public val values: List<KLuaCoreValue>,
        public val continuation: KLuaCoreContinuation? = null,
    ) : KLuaCoreCallResult

    public data class RuntimeError(
        public val message: String,
        public val cause: Throwable? = null,
        public val nativeFrames: List<String> = emptyList(),
        public val errorObject: KLuaCoreValue? = null,
    ) : KLuaCoreCallResult
}

public sealed interface KLuaCoreLoad {
    public data class Success(
        public val chunk: KLuaCoreChunk,
    ) : KLuaCoreLoad

    public data class SyntaxError(
        public val message: String,
    ) : KLuaCoreLoad
}

public sealed interface KLuaCoreBytecodeLoad {
    public data class Success(
        public val bytes: ByteArray,
    ) : KLuaCoreBytecodeLoad {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Success) return false
            return bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    public data class SyntaxError(
        public val message: String,
    ) : KLuaCoreBytecodeLoad
}

public sealed interface KLuaCoreExecution {
    public data class Success(
        public val values: List<KLuaCoreValue>,
    ) : KLuaCoreExecution

    public data class SyntaxError(
        public val message: String,
    ) : KLuaCoreExecution

    public data class RuntimeError(
        public val message: String,
        public val sourceName: String? = null,
        public val line: Int? = null,
        public val cause: Throwable? = null,
        public val luaFrames: List<KLuaCoreStackFrame> = emptyList(),
        public val errorObject: KLuaCoreValue? = null,
    ) : KLuaCoreExecution {
        public val traceback: String = formatCoreTraceback(message, luaFrames)
    }
}

public sealed interface KLuaCoreComparison {
    public data class Success(
        public val value: Boolean,
    ) : KLuaCoreComparison

    public data class RuntimeError(
        public val message: String,
        public val sourceName: String? = null,
        public val line: Int? = null,
        public val cause: Throwable? = null,
        public val luaFrames: List<KLuaCoreStackFrame> = emptyList(),
        public val errorObject: KLuaCoreValue? = null,
    ) : KLuaCoreComparison {
        public val traceback: String = formatCoreTraceback(message, luaFrames)
    }
}

public data class KLuaCoreStackFrame(
    public val sourceName: String,
    public val line: Int,
    public val lineDefined: Int = 0,
    public val lastLineDefined: Int = 0,
    public val upvalueCount: Int = 0,
    public val parameterCount: Int = 0,
    public val isVararg: Boolean = false,
    public val activeLines: List<Int> = emptyList(),
    public val function: KLuaCoreValue? = null,
    public val varargs: List<KLuaCoreValue> = emptyList(),
    public val locals: List<KLuaCoreLocalVariable> = emptyList(),
    public val upvalues: List<KLuaCoreUpvalue> = emptyList(),
    public val globals: List<KLuaCoreLocalVariable> = emptyList(),
    public val callSiteName: String? = null,
    public val callSiteNameWhat: String = "",
    public val transferStart: Int = 0,
    public val transferCount: Int = 0,
)

public data class KLuaCoreLocalVariable(
    public val name: String,
    public val value: KLuaCoreValue,
)

public data class KLuaCoreUpvalue(
    public val name: String,
    public val value: KLuaCoreValue,
)

public data class KLuaCoreFunctionDebugInfo(
    public val sourceName: String,
    public val lineDefined: Int,
    public val lastLineDefined: Int,
    public val upvalueCount: Int,
    public val parameterCount: Int,
    public val isVararg: Boolean,
    public val activeLines: List<Int>,
    public val parameterNames: List<String>,
    public val localNames: List<String> = parameterNames,
)

public data class KLuaCoreDebugHook(
    public val function: KLuaCoreValue,
    public val mask: String,
    public val count: Int,
)

public enum class KLuaCoreDebugEvent {
    LINE,
}

public fun interface KLuaCoreDebugObserver {
    public fun shouldSuspend(event: KLuaCoreDebugEvent, sourceId: String, line: Int, callDepth: Int): Boolean
}

public fun interface KLuaCoreContinuation {
    public fun resume(arguments: List<KLuaCoreValue>): KLuaCoreCallResult
}

public class KLuaCoreCoroutine internal constructor(
    private val function: LuaValue,
    private val globals: KLuaCoreGlobals,
    private val limits: KLuaCoreExecutionLimits,
) {
    private val vm = LuaVm(
        globals.table,
        globals.environment,
        metatables = globals.vmMetatableProvider,
        instructionLimit = limits.instructionLimit,
        retainFramesOnUnhandledError = true,
    )
    private var started = false
    private var dead = false
    private var pendingContinuation: LuaYieldContinuation? = null
    private var terminalError: KLuaCoreCoroutineExecution.RuntimeError? = null
    private var terminalVmError: LuaVmException? = null

    public fun resume(arguments: List<KLuaCoreValue>): KLuaCoreCoroutineExecution {
        if (dead) {
            return KLuaCoreCoroutineExecution.RuntimeError("cannot resume dead coroutine")
        }
        terminalError = null
        terminalVmError = null
        val luaArguments = arguments.map { value ->
            value.toLuaValueOrNull(globals)
                ?: return KLuaCoreCoroutineExecution.RuntimeError("cannot pass ${value.publicTypeName()} as Lua argument")
        }
        return try {
            val continuation = pendingContinuation
            pendingContinuation = null
            val result = when {
                continuation != null -> continuation.resume(luaArguments)
                started -> vm.resumeYieldable(luaArguments)
                else -> vm.callYieldable(function, luaArguments)
            }
            result.toCoroutineExecution(globals).also {
                when (result) {
                    is LuaExecutionResult.Returned -> dead = true
                    is LuaExecutionResult.Yielded -> {
                        pendingContinuation = result.continuation ?: if (vm.currentFrame == null) {
                            LuaYieldContinuation { resumedArguments -> LuaExecutionResult.Returned(resumedArguments) }
                        } else {
                            null
                        }
                    }
                    LuaExecutionResult.DebugSuspended -> Unit
                }
            }
        } catch (error: LuaVmException) {
            dead = true
            KLuaCoreCoroutineExecution.RuntimeError(
                error.message ?: "runtime error",
                error.sourceName,
                error.line,
                error.rootCause(),
                vm.stackFrames().toCoreStackFramesFromNative(globals),
                error.errorObject?.let { toPublicValue(it, globals) },
            ).also {
                terminalError = it
                terminalVmError = error
            }
        } finally {
            started = true
        }
    }

    public fun close(): KLuaCoreCoroutineExecution {
        if (dead) {
            val vmError = terminalVmError ?: return KLuaCoreCoroutineExecution.Returned(emptyList())
            val retainedError = terminalError
            terminalVmError = null
            terminalError = null
            return try {
                vm.closeSuspended(vmError)
                KLuaCoreCoroutineExecution.Returned(emptyList())
            } catch (error: LuaVmException) {
                if (error === vmError && retainedError != null) {
                    retainedError
                } else {
                    error.toCoroutineRuntimeError(globals)
                }
            }
        }
        pendingContinuation = null
        dead = true
        return try {
            vm.closeSuspended()
            KLuaCoreCoroutineExecution.Returned(emptyList())
        } catch (error: LuaVmException) {
            error.toCoroutineRuntimeError(globals)
        }
    }

    public val luaFrames: List<KLuaCoreStackFrame>
        get() = vm.stackFrames().toCoreStackFramesFromNative(globals)

    public fun setLocal(level: Int, index: Int, value: KLuaCoreValue): String? {
        val luaValue = value.toLuaValueOrNull(globals) ?: return null
        return vm.setLocal(level, index, luaValue)
    }

    public fun setDebugHook(function: KLuaCoreValue?, mask: String, count: Int): Boolean {
        val luaValue = function?.toLuaValueOrNull(globals) ?: LuaNil
        return vm.setDebugHook(luaValue, mask, count)
    }

    public fun setDebugObserver(observer: KLuaCoreDebugObserver?) {
        vm.setDebugObserver(
            observer?.let { publicObserver ->
                LuaVmDebugObserver { sourceId, line, callDepth ->
                    publicObserver.shouldSuspend(KLuaCoreDebugEvent.LINE, sourceId, line, callDepth)
                }
            },
        )
    }

    public fun getDebugHook(): KLuaCoreDebugHook? {
        return vm.getDebugHook()?.let { hook ->
            KLuaCoreDebugHook(
                toPublicValue(hook.function, globals),
                hook.mask,
                hook.count,
            )
        }
    }
}

private fun LuaVmException.toCoroutineRuntimeError(globals: KLuaCoreGlobals): KLuaCoreCoroutineExecution.RuntimeError {
    return KLuaCoreCoroutineExecution.RuntimeError(
        message ?: "runtime error",
        sourceName,
        line,
        rootCause(),
        luaFrames.toCoreStackFrames(),
        errorObject?.let { toPublicValue(it, globals) },
    )
}

private fun LuaExecutionResult.toCoroutineExecution(globals: KLuaCoreGlobals): KLuaCoreCoroutineExecution {
    return when (this) {
        is LuaExecutionResult.Returned -> KLuaCoreCoroutineExecution.Returned(
            values.map { value -> toPublicValue(value, globals) },
        )
        is LuaExecutionResult.Yielded -> KLuaCoreCoroutineExecution.Yielded(
            values.map { value -> toPublicValue(value, globals) },
        )
        LuaExecutionResult.DebugSuspended -> KLuaCoreCoroutineExecution.DebugSuspended
    }
}

public sealed interface KLuaCoreCoroutineExecution {
    public data class Returned(
        public val values: List<KLuaCoreValue>,
    ) : KLuaCoreCoroutineExecution

    public data class Yielded(
        public val values: List<KLuaCoreValue>,
    ) : KLuaCoreCoroutineExecution

    public data object DebugSuspended : KLuaCoreCoroutineExecution

    public data class RuntimeError(
        public val message: String,
        public val sourceName: String? = null,
        public val line: Int? = null,
        public val cause: Throwable? = null,
        public val luaFrames: List<KLuaCoreStackFrame> = emptyList(),
        public val errorObject: KLuaCoreValue? = null,
    ) : KLuaCoreCoroutineExecution {
        public val traceback: String = formatCoreTraceback(message, luaFrames)
    }
}

public sealed interface KLuaCoreValue {
    public data object Nil : KLuaCoreValue

    public data class BooleanValue(
        public val value: Boolean,
    ) : KLuaCoreValue

    public data class IntegerValue(
        public val value: Long,
    ) : KLuaCoreValue

    public data class NumberValue(
        public val value: Double,
    ) : KLuaCoreValue

    public data class StringValue(
        public val value: String,
    ) : KLuaCoreValue

    public data class FunctionValue(
        public val function: KLuaCoreFunction,
    ) : KLuaCoreValue {
        internal var sourceFunction: LuaValue? = null
        internal var sourceGlobals: KLuaCoreGlobals? = null
        internal var contextFunction: KLuaCoreContextFunction? = null
        internal var yieldable: Boolean = false
    }

    public data class TableValue(
        public val fields: MutableMap<KLuaCoreValue, KLuaCoreValue>,
    ) : KLuaCoreValue {
        public var metatable: TableValue? = null
        internal var sourceTable: LuaTable? = null
    }

    public data class UserDataValue(
        public val value: Any,
    ) : KLuaCoreValue

    public data class UnsupportedValue(
        public val typeName: String,
    ) : KLuaCoreValue
}

private fun rawNumberEqual(left: KLuaCoreValue, right: KLuaCoreValue): Boolean {
    return when (left) {
        is KLuaCoreValue.IntegerValue -> when (right) {
            is KLuaCoreValue.IntegerValue -> left.value == right.value
            is KLuaCoreValue.NumberValue -> right.value.luaInteger()?.let { left.value == it } ?: false
            else -> false
        }
        is KLuaCoreValue.NumberValue -> when (right) {
            is KLuaCoreValue.IntegerValue -> left.value.luaInteger()?.let { it == right.value } ?: false
            is KLuaCoreValue.NumberValue -> left.value == right.value
            else -> false
        }
        else -> false
    }
}

private fun KLuaCoreValue.publicTypeName(): String {
    return when (this) {
        KLuaCoreValue.Nil -> "nil"
        is KLuaCoreValue.BooleanValue -> "boolean"
        is KLuaCoreValue.IntegerValue,
        is KLuaCoreValue.NumberValue,
        -> "number"
        is KLuaCoreValue.StringValue -> "string"
        is KLuaCoreValue.FunctionValue -> "function"
        is KLuaCoreValue.TableValue -> "table"
        is KLuaCoreValue.UserDataValue -> "userdata"
        is KLuaCoreValue.UnsupportedValue -> typeName
    }
}

private fun Double.luaInteger(): Long? {
    if (!java.lang.Double.isFinite(this) || this < Long.MIN_VALUE.toDouble() || this >= LUA_INTEGER_EXCLUSIVE_UPPER_BOUND) {
        return null
    }
    val integer = toLong()
    return if (integer.toDouble() == this) integer else null
}

private fun KLuaCoreValue.toLuaValueOrNull(globals: KLuaCoreGlobals): LuaValue? {
    return toLuaValueOrNull(globals, null)
}

private fun KLuaCoreValue.toLuaValueOrNull(
    globals: KLuaCoreGlobals,
    tableCache: MutableMap<KLuaCoreValue.TableValue, LuaTable>?,
): LuaValue? {
    return when (this) {
        KLuaCoreValue.Nil -> LuaNil
        is KLuaCoreValue.BooleanValue -> LuaBoolean(value)
        is KLuaCoreValue.IntegerValue -> LuaInteger(value)
        is KLuaCoreValue.NumberValue -> LuaFloat(value)
        is KLuaCoreValue.StringValue -> LuaString(value)
        is KLuaCoreValue.FunctionValue -> sourceFunction
            ?.takeIf { sourceGlobals == null || sourceGlobals === globals }
            ?: LuaNativeFunction(
                yieldable = yieldable,
                function = { arguments -> callCoreFunction(function, arguments, globals) },
                contextualFunction = contextFunction?.let { function ->
                    { context -> callCoreContextFunction(function, context, globals) }
                },
            )
        is KLuaCoreValue.TableValue -> {
            val resolvedTableCache = tableCache ?: IdentityHashMap()
            val cached = resolvedTableCache[this]
            if (cached != null) {
                return cached
            }
            val originalTable = this.sourceTable
            if (originalTable != null) {
                syncPublicTableToLua(originalTable, this, globals, resolvedTableCache)
                return originalTable
            }
            val table = LuaTable()
            resolvedTableCache[this] = table
            for ((fieldKey, fieldValue) in fields) {
                val luaKey = fieldKey.toLuaValueOrNull(globals, resolvedTableCache) ?: return null
                val luaValue = fieldValue.toLuaValueOrNull(globals, resolvedTableCache) ?: return null
                table.rawSet(luaKey, luaValue)
            }
            table.metatable = metatable?.toLuaValueOrNull(globals, resolvedTableCache) as? LuaTable
            table
        }
        is KLuaCoreValue.UserDataValue -> LuaUserData(value) { hostValue -> globals.userDataType(hostValue) }
        is KLuaCoreValue.UnsupportedValue -> null
    }
}

private fun toPublicValue(value: LuaValue, globals: KLuaCoreGlobals): KLuaCoreValue =
    toPublicValue(value, globals, null)

private fun toPublicValue(
    value: LuaValue,
    globals: KLuaCoreGlobals,
    tableCache: MutableMap<LuaTable, KLuaCoreValue.TableValue>?,
): KLuaCoreValue {
    return when (value) {
        LuaNil -> KLuaCoreValue.Nil
        is LuaBoolean -> KLuaCoreValue.BooleanValue(value.value)
        is LuaInteger -> KLuaCoreValue.IntegerValue(value.value)
        is LuaFloat -> KLuaCoreValue.NumberValue(value.value)
        is LuaString -> KLuaCoreValue.StringValue(value.value)
        is LuaTable -> {
            val resolvedTableCache = tableCache ?: IdentityHashMap()
            val cached = resolvedTableCache[value]
            if (cached != null) {
                return cached
            }
            val tableValue = KLuaCoreValue.TableValue(mutableMapOf())
            resolvedTableCache[value] = tableValue
            tableValue.sourceTable = value
            tableValue.fields.putAll(
                value.rawEntries()
                    .map { (key, fieldValue) ->
                        toPublicValue(key, globals, resolvedTableCache) to
                            toPublicValue(fieldValue, globals, resolvedTableCache)
                    },
            )
            tableValue.metatable = value.metatable?.let { metatable ->
                toPublicValue(metatable, globals, resolvedTableCache) as KLuaCoreValue.TableValue
            }
            tableValue
        }
        is LuaUserData -> KLuaCoreValue.UserDataValue(value.value)
        is LuaClosure,
        is LuaNativeFunction,
        -> KLuaCoreValue.FunctionValue { arguments ->
            callPublicLuaFunction(value, arguments, globals)
        }.also { functionValue ->
            functionValue.sourceFunction = value
            functionValue.sourceGlobals = globals
            functionValue.yieldable = when (value) {
                is LuaClosure -> true
                is LuaNativeFunction -> value.yieldable
            }
        }
    }
}

private fun LuaValue.publicTypeName(): String {
    return when (this) {
        is LuaBoolean -> "boolean"
        is LuaFloat,
        is LuaInteger,
        -> "number"
        LuaNil -> "nil"
        is LuaString -> "string"
        is LuaClosure,
        is LuaNativeFunction -> "function"
        is LuaTable -> "table"
        is LuaUserData -> "userdata"
    }
}

private fun LuaVmException.rootCause(): Throwable? {
    var cause = cause ?: return null
    while (cause is LuaVmException && cause.cause != null) {
        cause = cause.cause!!
    }
    return cause
}

private fun List<LuaVmStackFrame>.toCoreStackFrames(): List<KLuaCoreStackFrame> {
    return map { frame ->
        KLuaCoreStackFrame(
            frame.sourceName,
            frame.line,
            frame.lineDefined,
            frame.lastLineDefined,
            frame.upvalueCount,
            frame.parameterCount,
            frame.isVararg,
            frame.activeLines,
            callSiteName = frame.callSiteName,
            callSiteNameWhat = frame.callSiteNameWhat,
            transferStart = frame.transferStart,
            transferCount = frame.transferCount,
        )
    }
}

private fun List<LuaNativeStackFrame>.toCoreStackFramesFromNative(globals: KLuaCoreGlobals): List<KLuaCoreStackFrame> {
    return map { frame ->
        KLuaCoreStackFrame(
            sourceName = frame.sourceName,
            line = frame.line,
            lineDefined = frame.lineDefined,
            lastLineDefined = frame.lastLineDefined,
            upvalueCount = frame.upvalueCount,
            parameterCount = frame.parameterCount,
            isVararg = frame.isVararg,
            activeLines = frame.activeLines,
            function = frame.function?.let { function -> toPublicValue(function, globals) },
            varargs = frame.varargs.map { value -> toPublicValue(value, globals) },
            locals = frame.locals.map { local ->
                KLuaCoreLocalVariable(
                    local.name,
                    toPublicValue(local.value, globals),
                )
            },
            upvalues = frame.upvalues.map { upvalue ->
                KLuaCoreUpvalue(
                    upvalue.name,
                    toPublicValue(upvalue.value, globals),
                )
            },
            globals = frame.globals.map { global ->
                KLuaCoreLocalVariable(
                    global.name,
                    toPublicValue(global.value, globals),
                )
            },
            callSiteName = frame.callSiteName,
            callSiteNameWhat = frame.callSiteNameWhat,
            transferStart = frame.transferStart,
            transferCount = frame.transferCount,
        )
    }
}

private fun formatCoreTraceback(message: String, frames: List<KLuaCoreStackFrame>): String {
    return buildString {
        append(message)
        append("\nstack traceback:")
        for (frame in frames) {
            append("\n\t")
            append(luaShortSourceName(frame.sourceName))
            if (frame.line > 0) {
                append(':')
                append(frame.line)
            }
        }
    }
}

private fun luaShortSourceName(source: String): String {
    return when {
        source.startsWith("[Kotlin]") || source == "[C]" -> source
        source.startsWith("=") -> source.drop(1).take(LUA_IDSIZE - 1)
        source.startsWith("@") -> {
            val file = source.drop(1)
            if (source.length <= LUA_IDSIZE) {
                file
            } else {
                LUA_SOURCE_RETS + file.takeLast(LUA_IDSIZE - LUA_SOURCE_RETS.length)
            }
        }
        else -> {
            val firstLine = source.substringBefore('\n')
            val reserved = LUA_SOURCE_PREFIX.length + LUA_SOURCE_RETS.length + LUA_SOURCE_SUFFIX.length + 1
            val available = LUA_IDSIZE - reserved
            val body = if (source.length < available && '\n' !in source) {
                source
            } else {
                firstLine.take(available) + LUA_SOURCE_RETS
            }
            LUA_SOURCE_PREFIX + body + LUA_SOURCE_SUFFIX
        }
    }
}

private const val LUA_IDSIZE = 60
private const val LUA_SOURCE_RETS = "..."
private const val LUA_SOURCE_PREFIX = "[string \""
private const val LUA_SOURCE_SUFFIX = "\"]"
private const val LUA_INTEGER_EXCLUSIVE_UPPER_BOUND = 9223372036854775808.0

private fun callCoreFunction(
    function: KLuaCoreFunction,
    arguments: List<LuaValue>,
    globals: KLuaCoreGlobals,
): List<LuaValue> {
    return callCoreFunction(arguments, globals) { publicArguments ->
        function.call(publicArguments)
    }
}

private fun callCoreContextFunction(
    function: KLuaCoreContextFunction,
    context: LuaNativeCallContext,
    globals: KLuaCoreGlobals,
): List<LuaValue> {
    return callCoreFunction(context.arguments, globals) { publicArguments ->
        function.call(
            KLuaCoreCallContext(
                publicArguments,
                luaFramesProvider = { context.luaFrames.toCoreStackFramesFromNative(globals) },
                isYieldable = context.isYieldable,
                setLocalValue = { level, index, value ->
                    value.toLuaValueOrNull(globals)?.let { luaValue ->
                        context.setLocal(level, index, luaValue)
                    }
                },
                setDebugHookValue = { index, mask, count ->
                    context.setDebugHook(index, mask, count)
                },
                getDebugHookValue = {
                    context.getDebugHook()?.let { hook ->
                        KLuaCoreDebugHook(
                            toPublicValue(hook.function, globals),
                            hook.mask,
                            hook.count,
                        )
                    }
                },
            ),
        )
    }
}

private fun callCoreFunction(
    arguments: List<LuaValue>,
    globals: KLuaCoreGlobals,
    call: (List<KLuaCoreValue>) -> KLuaCoreCallResult,
): List<LuaValue> {
    val tableCache = if (arguments.any { value -> value is LuaTable }) {
        IdentityHashMap<LuaTable, KLuaCoreValue.TableValue>()
    } else {
        null
    }
    val publicArguments = arguments.map { value -> toPublicValue(value, globals, tableCache) }
    return try {
        when (val result = call(publicArguments)) {
            is KLuaCoreCallResult.Success -> {
                syncPublicTablesToLua(arguments, publicArguments, globals)
                result.values.map { value ->
                    value.toLuaReturnValue(arguments, publicArguments, globals)
                }
            }
            is KLuaCoreCallResult.Yielded -> {
                syncPublicTablesToLua(arguments, publicArguments, globals)
                throw result.toLuaYieldSignal(arguments, publicArguments, globals)
            }
            is KLuaCoreCallResult.RuntimeError -> {
                syncPublicTablesToLua(arguments, publicArguments, globals)
                throw LuaVmException(
                    result.message,
                    luaFrames = result.nativeFrames.toNativeStackFrames(),
                    errorObject = result.errorObject?.toLuaValueOrNull(globals),
                    cause = result.cause,
                )
            }
        }
    } catch (control: KLuaCoreControlException) {
        throw control
    } catch (error: LuaVmException) {
        throw error
    } catch (yield: LuaYieldSignal) {
        throw yield
    } catch (error: RuntimeException) {
        throw LuaVmException(error.message ?: error::class.java.simpleName, cause = error)
    }
}

private fun KLuaCoreCallResult.Yielded.toLuaYieldSignal(
    luaArguments: List<LuaValue>,
    publicArguments: List<KLuaCoreValue>,
    globals: KLuaCoreGlobals,
): LuaYieldSignal {
    return LuaYieldSignal(
        values.map { value ->
            value.toLuaReturnValue(luaArguments, publicArguments, globals)
        },
        continuation?.let { continuation ->
            LuaYieldSignalContinuation { arguments ->
                continuePublicYield(continuation, arguments, globals)
            }
        },
    )
}

private fun continuePublicYield(
    continuation: KLuaCoreContinuation,
    arguments: List<LuaValue>,
    globals: KLuaCoreGlobals,
): List<LuaValue> {
    val publicArguments = arguments.map { value -> toPublicValue(value, globals) }
    return when (val result = continuation.resume(publicArguments)) {
        is KLuaCoreCallResult.Success -> result.values.map { value ->
            value.toLuaValueOrNull(globals)
                ?: throw LuaVmException("cannot return ${value.publicTypeName()} as Lua value")
        }
        is KLuaCoreCallResult.Yielded -> throw result.toLuaYieldSignal(arguments, publicArguments, globals)
        is KLuaCoreCallResult.RuntimeError -> throw LuaVmException(
            result.message,
            luaFrames = result.nativeFrames.toNativeStackFrames(),
            errorObject = result.errorObject?.toLuaValueOrNull(globals),
            cause = result.cause,
        )
    }
}

private fun List<String>.toNativeStackFrames(): List<LuaVmStackFrame> {
    return map { name -> LuaVmStackFrame("[Kotlin]: $name") }
}

private fun KLuaCoreValue.toLuaReturnValue(
    luaArguments: List<LuaValue>,
    publicArguments: List<KLuaCoreValue>,
    globals: KLuaCoreGlobals,
): LuaValue {
    val tableCache = seedLuaTableCache(luaArguments, publicArguments)
    return toLuaValueOrNull(globals, tableCache)
        ?: throw LuaVmException("cannot return ${publicTypeName()} as Lua value")
}

private fun syncPublicTablesToLua(
    luaArguments: List<LuaValue>,
    publicArguments: List<KLuaCoreValue>,
    globals: KLuaCoreGlobals,
) {
    val tableCache = seedLuaTableCache(luaArguments, publicArguments) ?: return
    for (index in luaArguments.indices) {
        val luaTable = luaArguments[index] as? LuaTable ?: continue
        val publicTable = publicArguments.getOrNull(index) as? KLuaCoreValue.TableValue ?: continue
        syncPublicTableToLua(luaTable, publicTable, globals, tableCache)
    }
}

private fun syncPublicTableToLua(
    luaTable: LuaTable,
    publicTable: KLuaCoreValue.TableValue,
    globals: KLuaCoreGlobals,
    tableCache: MutableMap<KLuaCoreValue.TableValue, LuaTable>,
) {
    tableCache[publicTable] = luaTable
    val entries = publicTable.fields.map { (key, value) ->
        val luaKey = key.toLuaValueOrNull(globals, tableCache)
            ?: throw LuaVmException("cannot use ${key.publicTypeName()} as Lua table key")
        val luaValue = value.toLuaValueOrNull(globals, tableCache)
            ?: throw LuaVmException("cannot use ${value.publicTypeName()} as Lua table value")
        luaKey to luaValue
    }
    luaTable.rawReplace(entries)
    luaTable.metatable = publicTable.metatable?.toLuaValueOrNull(globals, tableCache) as? LuaTable
}

private fun seedLuaTableCache(
    luaValues: List<LuaValue>,
    publicValues: List<KLuaCoreValue>,
): MutableMap<KLuaCoreValue.TableValue, LuaTable>? {
    var tableCache: MutableMap<KLuaCoreValue.TableValue, LuaTable>? = null
    for (index in luaValues.indices) {
        val luaTable = luaValues[index] as? LuaTable ?: continue
        val publicTable = publicValues.getOrNull(index) as? KLuaCoreValue.TableValue ?: continue
        val resolvedTableCache = tableCache ?: IdentityHashMap<KLuaCoreValue.TableValue, LuaTable>().also {
            tableCache = it
        }
        seedLuaTableCache(luaTable, publicTable, resolvedTableCache)
    }
    return tableCache
}

private fun seedLuaTableCache(
    luaValue: LuaValue,
    publicValue: KLuaCoreValue?,
    tableCache: MutableMap<KLuaCoreValue.TableValue, LuaTable>,
) {
    val luaTable = luaValue as? LuaTable ?: return
    val publicTable = publicValue as? KLuaCoreValue.TableValue ?: return
    if (tableCache.put(publicTable, luaTable) != null) {
        return
    }
    val publicMetatable = publicTable.metatable
    val luaMetatable = luaTable.metatable
    if (publicMetatable != null && luaMetatable != null) {
        seedLuaTableCache(luaMetatable, publicMetatable, tableCache)
    }
}

private fun <T : Any> callCoreUserDataMethod(
    receiver: T,
    method: KLuaCoreUserDataMethod<T>,
    arguments: List<LuaValue>,
    globals: KLuaCoreGlobals,
): List<LuaValue> {
    return try {
        when (val result = method.call(receiver, arguments.map { value -> toPublicValue(value, globals) })) {
            is KLuaCoreCallResult.Success -> result.values.map { value ->
                value.toLuaValueOrNull(globals)
                    ?: throw LuaVmException("cannot return ${value.publicTypeName()} as Lua value")
            }
            is KLuaCoreCallResult.Yielded -> throw LuaYieldSignal(
                result.values.map { value ->
                    value.toLuaValueOrNull(globals)
                        ?: throw LuaVmException("cannot yield ${value.publicTypeName()} as Lua value")
                },
                result.continuation?.let { continuation ->
                    LuaYieldSignalContinuation { arguments -> continuePublicYield(continuation, arguments, globals) }
                },
            )
            is KLuaCoreCallResult.RuntimeError -> throw LuaVmException(
                result.message,
                luaFrames = result.nativeFrames.toNativeStackFrames(),
                errorObject = result.errorObject?.toLuaValueOrNull(globals),
                cause = result.cause,
            )
        }
    } catch (error: LuaVmException) {
        throw error
    } catch (yield: LuaYieldSignal) {
        throw yield
    } catch (error: RuntimeException) {
        throw LuaVmException(error.message ?: error::class.java.simpleName, cause = error)
    }
}

private fun <T : Any> callCoreUserDataGetter(
    receiver: T,
    getter: KLuaCoreUserDataGetter<T>,
    globals: KLuaCoreGlobals,
): List<LuaValue> {
    return try {
        when (val result = getter.get(receiver)) {
            is KLuaCoreCallResult.Success -> result.values.map { value ->
                value.toLuaValueOrNull(globals)
                    ?: throw LuaVmException("cannot return ${value.publicTypeName()} as Lua value")
            }
            is KLuaCoreCallResult.Yielded -> throw LuaYieldSignal(
                result.values.map { value ->
                    value.toLuaValueOrNull(globals)
                        ?: throw LuaVmException("cannot yield ${value.publicTypeName()} as Lua value")
                },
                result.continuation?.let { continuation ->
                    LuaYieldSignalContinuation { arguments -> continuePublicYield(continuation, arguments, globals) }
                },
            )
            is KLuaCoreCallResult.RuntimeError -> throw LuaVmException(
                result.message,
                luaFrames = result.nativeFrames.toNativeStackFrames(),
                errorObject = result.errorObject?.toLuaValueOrNull(globals),
                cause = result.cause,
            )
        }
    } catch (error: LuaVmException) {
        throw error
    } catch (yield: LuaYieldSignal) {
        throw yield
    } catch (error: RuntimeException) {
        throw LuaVmException(error.message ?: error::class.java.simpleName, cause = error)
    }
}

private fun <T : Any> callCoreUserDataSetter(
    receiver: T,
    setter: KLuaCoreUserDataSetter<T>,
    value: LuaValue,
    globals: KLuaCoreGlobals,
): List<LuaValue> {
    return try {
        when (val result = setter.set(receiver, toPublicValue(value, globals))) {
            is KLuaCoreCallResult.Success -> emptyList()
            is KLuaCoreCallResult.Yielded -> throw LuaYieldSignal(
                result.values.map { yieldedValue ->
                    yieldedValue.toLuaValueOrNull(globals)
                        ?: throw LuaVmException("cannot yield ${yieldedValue.publicTypeName()} as Lua value")
                },
                result.continuation?.let { continuation ->
                    LuaYieldSignalContinuation { arguments -> continuePublicYield(continuation, arguments, globals) }
                },
            )
            is KLuaCoreCallResult.RuntimeError -> throw LuaVmException(
                result.message,
                luaFrames = result.nativeFrames.toNativeStackFrames(),
                errorObject = result.errorObject?.toLuaValueOrNull(globals),
                cause = result.cause,
            )
        }
    } catch (error: LuaVmException) {
        throw error
    } catch (yield: LuaYieldSignal) {
        throw yield
    } catch (error: RuntimeException) {
        throw LuaVmException(error.message ?: error::class.java.simpleName, cause = error)
    }
}

private fun callPublicLuaFunction(
    function: LuaValue,
    arguments: List<KLuaCoreValue>,
    globals: KLuaCoreGlobals,
    isYieldable: Boolean = true,
    limits: KLuaCoreExecutionLimits = KLuaCoreExecutionLimits(),
): KLuaCoreCallResult {
    val tableCache = if (arguments.any { value -> value is KLuaCoreValue.TableValue }) {
        IdentityHashMap<KLuaCoreValue.TableValue, LuaTable>()
    } else {
        null
    }
    val luaArguments = arguments.map { value ->
        value.toLuaValueOrNull(globals, tableCache)
            ?: return KLuaCoreCallResult.RuntimeError("cannot pass ${value.publicTypeName()} as Lua argument")
    }
    return try {
        val vm = LuaVm(
            globals.table,
            globals.environment,
            metatables = globals.vmMetatableProvider,
            instructionLimit = limits.instructionLimit,
        )
        val result = vm.callWithYieldability(function, luaArguments, isYieldable).toCoreCallResult(vm, globals)
        syncLuaTablesToPublic(luaArguments, arguments, globals)
        result
    } catch (error: LuaVmException) {
        syncLuaTablesToPublic(luaArguments, arguments, globals)
        KLuaCoreCallResult.RuntimeError(
            error.message ?: "runtime error",
            error.rootCause(),
            errorObject = error.errorObject?.let { toPublicValue(it, globals) },
        )
    }
}

private fun syncLuaTablesToPublic(
    luaArguments: List<LuaValue>,
    publicArguments: List<KLuaCoreValue>,
    globals: KLuaCoreGlobals,
) {
    val tableCache = IdentityHashMap<LuaTable, KLuaCoreValue.TableValue>()
    for (index in luaArguments.indices) {
        val luaTable = luaArguments[index] as? LuaTable ?: continue
        val publicTable = publicArguments.getOrNull(index) as? KLuaCoreValue.TableValue ?: continue
        tableCache[luaTable] = publicTable
    }
    for ((luaTable, publicTable) in tableCache.entries.toList()) {
        val entries = luaTable.rawEntries().map { (key, value) ->
            toPublicValue(key, globals, tableCache) to toPublicValue(value, globals, tableCache)
        }
        publicTable.fields.clear()
        publicTable.fields.putAll(entries)
        publicTable.metatable = luaTable.metatable?.let { metatable ->
            toPublicValue(metatable, globals, tableCache) as KLuaCoreValue.TableValue
        }
        publicTable.sourceTable = luaTable
    }
}

private fun LuaExecutionResult.toCoreCallResult(
    vm: LuaVm,
    globals: KLuaCoreGlobals,
): KLuaCoreCallResult {
    return when (this) {
        is LuaExecutionResult.Returned -> KLuaCoreCallResult.Success(values.map { value -> toPublicValue(value, globals) })
        is LuaExecutionResult.Yielded -> KLuaCoreCallResult.Yielded(
            values.map { value -> toPublicValue(value, globals) },
            KLuaCoreContinuation { arguments ->
                val luaArguments = arguments.map { value ->
                    value.toLuaValueOrNull(globals)
                        ?: return@KLuaCoreContinuation KLuaCoreCallResult.RuntimeError("cannot pass ${value.publicTypeName()} as Lua argument")
                }
                try {
                    vm.resumeYieldable(luaArguments).toCoreCallResult(vm, globals)
                } catch (error: LuaVmException) {
                    KLuaCoreCallResult.RuntimeError(
                        error.message ?: "runtime error",
                        error.rootCause(),
                        errorObject = error.errorObject?.let { toPublicValue(it, globals) },
                    )
                }
            },
        )
        LuaExecutionResult.DebugSuspended -> KLuaCoreCallResult.RuntimeError(
            "debugger suspension requires a coroutine",
        )
    }
}
