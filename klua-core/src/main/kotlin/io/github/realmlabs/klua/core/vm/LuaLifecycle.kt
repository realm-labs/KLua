package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.value.LuaClosure
import io.github.realmlabs.klua.core.value.LuaNil
import io.github.realmlabs.klua.core.value.LuaNativeFunction
import io.github.realmlabs.klua.core.value.LuaString
import io.github.realmlabs.klua.core.value.LuaTable
import io.github.realmlabs.klua.core.value.LuaUserData
import io.github.realmlabs.klua.core.value.LuaValue
import java.lang.ref.WeakReference
import java.util.IdentityHashMap

/** Deterministic ownership for Lua values that have been marked for finalization. */
internal class LuaLifecycle(
    private val userDataMetatable: (Any) -> LuaTable?,
    private val userDataValues: (Any) -> List<LuaValue>,
    private val globalRoots: () -> List<LuaValue>,
) {
    private val pending = IdentityHashMap<Any, FinalizableEntry>()
    private val virtualMachines = mutableListOf<WeakReference<LuaVm>>()
    private var nextSequence = 0L
    private var closing = false
    private var runningFinalizers = false

    fun register(vm: LuaVm) {
        virtualMachines += WeakReference(vm)
    }

    fun markTable(table: LuaTable, metatable: LuaTable?, explicit: Boolean = true) {
        mark(table, table, metatable, explicit)
    }

    fun markUserData(userData: LuaUserData, metatable: LuaTable?, explicit: Boolean = true) {
        mark(userData.value, userData, metatable, explicit)
    }

    private fun mark(
        identity: Any,
        value: LuaValue,
        metatable: LuaTable?,
        explicit: Boolean,
    ) {
        if (closing || metatable == null || metatable.rawGet(GC_KEY) == LuaNil) {
            return
        }
        val current = pending[identity]
        if (current == null || explicit && current.running) {
            pending[identity] = FinalizableEntry(identity, value, ++nextSequence)
        }
    }

    fun collect(
        roots: List<LuaValue>,
        callFinalizer: (function: LuaValue, target: LuaValue) -> String?,
        reportWarning: (String) -> Unit = {},
    ): List<String> {
        if (closing || runningFinalizers) {
            return emptyList()
        }
        val trace = ReachabilityTrace(userDataMetatable, userDataValues)
        trace.mark(allRoots() + roots)
        val originalWeakValueTables = trace.weakValueTableIdentities()
        trace.clearWeakValues(trace.weakValueTables())
        val selected = pending.values
            .filterNot { entry -> trace.isIdentityReachable(entry.identity) }
            .sortedByDescending { entry -> entry.sequence }
        trace.mark(selected.map { entry -> entry.value })
        trace.clearWeakKeys()
        trace.clearWeakValues(
            trace.weakValueTables().filterNot { table -> originalWeakValueTables.containsKey(table) },
        )
        return run(selected, callFinalizer, reportWarning)
    }

    fun close(
        callFinalizer: (function: LuaValue, target: LuaValue) -> String?,
        reportWarning: (String) -> Unit = {},
    ): List<String> {
        if (closing) {
            return emptyList()
        }
        closing = true
        val selected = pending.values.sortedByDescending { entry -> entry.sequence }
        return run(selected, callFinalizer, reportWarning).also { pending.clear() }
    }

    private fun run(
        selected: List<FinalizableEntry>,
        callFinalizer: (function: LuaValue, target: LuaValue) -> String?,
        reportWarning: (String) -> Unit,
    ): List<String> {
        val warnings = mutableListOf<String>()
        runningFinalizers = true
        try {
            for (entry in selected) {
                if (pending[entry.identity] !== entry) {
                    continue
                }
                entry.running = true
                val metatable = when (val target = entry.value) {
                    is LuaTable -> target.metatable
                    is LuaUserData -> userDataMetatable(target.value)
                    else -> null
                }
                val finalizer = metatable?.rawGet(GC_KEY) ?: LuaNil
                if (finalizer != LuaNil) {
                    callFinalizer(finalizer, entry.value)?.let { message ->
                        val warning = "error in __gc ($message)"
                        warnings += warning
                        reportWarning(warning)
                    }
                }
                if (pending[entry.identity] === entry) {
                    pending.remove(entry.identity)
                }
            }
        } finally {
            runningFinalizers = false
        }
        return warnings
    }

    private fun allRoots(): List<LuaValue> = buildList {
        addAll(globalRoots())
        val iterator = virtualMachines.iterator()
        while (iterator.hasNext()) {
            val vm = iterator.next().get()
            if (vm == null) {
                iterator.remove()
            } else {
                addAll(vm.lifecycleRoots())
            }
        }
    }

    private class FinalizableEntry(
        val identity: Any,
        val value: LuaValue,
        val sequence: Long,
        var running: Boolean = false,
    )
}

private class ReachabilityTrace(
    private val userDataMetatable: (Any) -> LuaTable?,
    private val userDataValues: (Any) -> List<LuaValue>,
) {
    private val reachableIdentities = IdentityHashMap<Any, Boolean>()
    private val visited = IdentityHashMap<LuaValue, Boolean>()
    private val weakValues = IdentityHashMap<LuaTable, Boolean>()
    private val weakKeys = IdentityHashMap<LuaTable, Boolean>()

    fun mark(roots: List<LuaValue>) {
        roots.forEach(::visit)
        convergeEphemerons()
    }

    fun isIdentityReachable(identity: Any): Boolean = reachableIdentities.containsKey(identity)

    fun weakValueTables(): List<LuaTable> = weakValues.keys.toList()

    fun weakValueTableIdentities(): IdentityHashMap<LuaTable, Boolean> =
        IdentityHashMap<LuaTable, Boolean>().also { identities ->
            weakValues.keys.forEach { table -> identities[table] = true }
        }

    fun clearWeakValues(tables: List<LuaTable>) {
        tables.forEach { table ->
            table.rawEntries().forEach { (key, value) ->
                if (isClearable(value) && !isReachable(value)) {
                    table.rawSet(key, LuaNil)
                }
            }
        }
    }

    fun clearWeakKeys() {
        weakKeys.keys.forEach { table ->
            table.rawEntries().forEach { (key, _) ->
                if (isClearable(key) && !isReachable(key)) {
                    table.rawSet(key, LuaNil)
                }
            }
        }
    }

    private fun visit(value: LuaValue) {
        if (visited.put(value, true) != null) {
            return
        }
        when (value) {
            is LuaTable -> visitTable(value)
            is LuaUserData -> {
                reachableIdentities[value.value] = true
                userDataMetatable(value.value)?.let(::visit)
                userDataValues(value.value).forEach(::visit)
            }
            is LuaClosure -> {
                value.upvalues.forEach { upvalue -> visit(upvalue.value) }
                value.globals?.let(::visit)
                value.environment?.value?.let(::visit)
            }
            else -> Unit
        }
    }

    private fun visitTable(table: LuaTable) {
        reachableIdentities[table] = true
        table.metatable?.let(::visit)
        when (weakMode(table)) {
            WeakMode.STRONG -> table.rawEntries().forEach { (key, value) ->
                visit(key)
                visit(value)
            }
            WeakMode.WEAK_VALUES -> {
                weakValues[table] = true
                table.rawEntries().keys.forEach(::visit)
            }
            WeakMode.WEAK_KEYS -> weakKeys[table] = true
            WeakMode.ALL_WEAK -> {
                weakKeys[table] = true
                weakValues[table] = true
            }
        }
    }

    private fun convergeEphemerons() {
        var changed: Boolean
        do {
            val before = visited.size
            weakKeys.keys.toList().forEach { table ->
                if (weakValues.containsKey(table)) {
                    return@forEach
                }
                table.rawEntries().forEach { (key, value) ->
                    if (!isClearable(key) || isReachable(key)) {
                        visit(value)
                    }
                }
            }
            changed = visited.size != before
        } while (changed)
    }

    private fun isReachable(value: LuaValue): Boolean {
        return when (value) {
            is LuaTable -> reachableIdentities.containsKey(value)
            is LuaUserData -> reachableIdentities.containsKey(value.value)
            is LuaClosure,
            is LuaNativeFunction,
            -> visited.containsKey(value)
            else -> true
        }
    }
}

private enum class WeakMode {
    STRONG,
    WEAK_VALUES,
    WEAK_KEYS,
    ALL_WEAK,
}

private fun weakMode(table: LuaTable): WeakMode {
    val mode = (table.metatable?.rawGet(MODE_KEY) as? LuaString)
        ?.value
        ?.substringBefore('\u0000')
        ?: return WeakMode.STRONG
    val weakKeys = 'k' in mode
    val weakValues = 'v' in mode
    return when {
        weakKeys && weakValues -> WeakMode.ALL_WEAK
        weakKeys -> WeakMode.WEAK_KEYS
        weakValues -> WeakMode.WEAK_VALUES
        else -> WeakMode.STRONG
    }
}

private fun isClearable(value: LuaValue): Boolean {
    return value is LuaTable || value is LuaUserData || value is LuaClosure || value is LuaNativeFunction
}

private val GC_KEY = LuaString("__gc")
private val MODE_KEY = LuaString("__mode")
