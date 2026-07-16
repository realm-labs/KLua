package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.value.LuaClosure
import io.github.realmlabs.klua.core.value.LuaNil
import io.github.realmlabs.klua.core.value.LuaString
import io.github.realmlabs.klua.core.value.LuaTable
import io.github.realmlabs.klua.core.value.LuaUserData
import io.github.realmlabs.klua.core.value.LuaValue
import java.lang.ref.WeakReference
import java.util.IdentityHashMap

/** Deterministic ownership for Lua values that have been marked for finalization. */
internal class LuaLifecycle(
    private val userDataMetatable: (Any) -> LuaTable?,
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
        val reachable = reachableIdentities(allRoots() + roots)
        val selected = pending.values
            .filterNot { entry -> reachable.containsKey(entry.identity) }
            .sortedByDescending { entry -> entry.sequence }
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

    private fun reachableIdentities(roots: List<LuaValue>): IdentityHashMap<Any, Boolean> {
        val reachable = IdentityHashMap<Any, Boolean>()
        val visited = IdentityHashMap<LuaValue, Boolean>()

        fun visit(value: LuaValue) {
            if (visited.put(value, true) != null) {
                return
            }
            when (value) {
                is LuaTable -> {
                    reachable[value] = true
                    value.metatable?.let(::visit)
                    value.rawEntries().forEach { (key, fieldValue) ->
                        visit(key)
                        visit(fieldValue)
                    }
                }
                is LuaUserData -> {
                    reachable[value.value] = true
                    userDataMetatable(value.value)?.let(::visit)
                }
                is LuaClosure -> {
                    value.upvalues.forEach { upvalue -> visit(upvalue.value) }
                    value.globals?.let(::visit)
                    value.environment?.value?.let(::visit)
                }
                else -> Unit
            }
        }

        roots.forEach(::visit)
        return reachable
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

private val GC_KEY = LuaString("__gc")
