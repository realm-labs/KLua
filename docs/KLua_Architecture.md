# KLua Architecture

**Project:** KLua  
**Goal:** A pure Kotlin Lua runtime for JVM 17+, with a C-Lua-like low-level API and an `mlua`-style high-level embedding API.

---

## 1. Design Summary

KLua should be designed as a **Lua-compatible runtime on the JVM**, not as a direct port of PUC Lua internals.

Recommended direction:

```text
Lua source
  -> lexer/parser
  -> AST
  -> KLua compiler
  -> custom register bytecode
  -> Kotlin/JVM interpreter
  -> optional JVM bytecode compiler later
```

The first production-quality version should focus on:

```text
Correct semantics
Clear embedding API
Good JVM integration
Source-level debugging support
Stable bytecode format
Benchmark-driven optimization
```

Do **not** start by generating JVM bytecode. Start with a correct custom bytecode interpreter, then add JVM-specific optimizations after the language core is stable.

---

## 2. Lua 5.5 Target

Supported source-language target:

```text
Lua 5.5-compatible source language,
but not binary-compatible with official .luac bytecode.
```

KLua intentionally supports one Lua language target: Lua 5.5. Older Lua versions and LuaJIT-like modes are not project goals. This keeps the compiler, VM, standard library, debug semantics, and embedding API from carrying compatibility branches before v1.

This means KLua should aim to support:

- Lua 5.5 syntax and runtime behavior.
- Tables, functions, closures, varargs, multiple returns.
- Metatables and metamethods.
- Coroutines.
- Standard libraries where appropriate.
- A Lua-like stack API for embedders.

But KLua does **not** initially need to support:

- Loading official PUC Lua bytecode.
- Native Lua C modules.
- Exact C API binary compatibility.
- Every debug-hook edge case in v1.
- Older Lua source versions.
- LuaJIT-specific behavior.

This keeps the implementation practical while still feeling like real Lua to script authors.

### 2.1 No Public Version Selection

Do not add public source-version selectors or old-version compatibility flags.

`LuaConfig` is for runtime options, such as debug-library availability or future sandbox/performance settings:

```kotlin
data class LuaConfig(
    val debugEnabled: Boolean = true,
)
```

Recommended rule:

```text
One LuaState = Lua 5.5 semantics.
```

If a Lua 5.5 feature is missing, track it as a conformance gap. Do not introduce a compatibility profile to explain or preserve the gap.

### 2.2 One Internal Bytecode

Recommended pipeline:

```text
Lua 5.5 source
    -> lexer/parser/compiler
    -> one KLua internal bytecode format
    -> one KLua VM runtime
```

Compiled prototypes do not carry a language-version field. KLua has one source target, so version validation belongs at the future bytecode-package boundary rather than on every in-memory prototype:

```kotlin
class Prototype(
    val code: IntArray,
    val constants: Array<LuaValue>,
    val nested: Array<Prototype>,
    val debug: DebugInfo?,
    val flags: PrototypeFlags,
)
```

The VM should execute one semantic path. Areas that require particular care for Lua 5.5 conformance include:

```text
global lookup
number conversion
integer division
bitwise behavior
vararg behavior
coroutine/protected-call yielding
stdlib function behavior
debug API behavior
```

### 2.4 Environment Handling

KLua should implement the modern lexical environment model:

```text
_ENV is a lexical upvalue
global name x lowers to _ENV["x"]
```

Recommended internal model:

```kotlin
class LuaClosure(
    val proto: Prototype,
    val upvalues: Array<Upvalue>,
)
```

Compiler lowering:

```text
global name -> _ENV["name"]
```

Do not add `getfenv`, `setfenv`, function environment tables, or other old-version environment compatibility behavior.

### 2.5 Number Model

Implement Lua 5.5 integer/float representation directly.

```kotlin
sealed interface LuaNumber : LuaValue

@JvmInline
value class LuaInteger(val value: Long) : LuaNumber

@JvmInline
value class LuaFloat(val value: Double) : LuaNumber
```

The implementation should support integer subtype behavior, bitwise operators, and floor division as Lua 5.5 conformance requirements.

### 2.6 Standard Library Installation

Standard libraries should be installed for the single Lua 5.5 target.

```kotlin
object LuaStdlib {
    fun install(state: LuaState) {
        installBase(state)
        installTable(state)
        installString(state)
        installMath(state)
        installCoroutine(state)
        installUtf8(state)
        installDebug(state)
        installPackage(state)
    }
}
```

Lua 5.5 library behavior to track:

```text
global declarations
named vararg tables
table.create
<const>
<close>
warn
to-be-closed variables
```

### 2.7 KLua Bytecode Compatibility

Do not support official PUC `.luac` binary chunks at first.

KLua should have its own bytecode package format:

```text
.kluac
  magic: KLua
  KLua bytecode format version
  fixed Lua 5.5 source marker
  source map/debug info
  function prototypes
  constants
```

Header example:

```kotlin
data class KLuaChunkHeader(
    val magic: Int,
    val kluaBytecodeVersion: Int,
    val sourceLanguage: String,
    val flags: Int,
)
```

Loading rule:

```text
LuaState can load supported KLua bytecode packages that carry the fixed Lua 5.5 source marker.
Unsupported KLua bytecode format versions are rejected before execution.
```

### 2.8 No Compatibility Dialects

Avoid a random bag of feature flags:

```kotlin
LuaConfig(
    enableLua51Globals = true,
    enableLuaJitExtensions = true,
)
```

That creates a dialect that is hard to document and test.

Prefer implementing Lua 5.5 semantics directly and documenting missing behavior as conformance gaps.

---

## 3. High-Level Module Layout

Recommended Gradle modules:

```text
klua-core
  Internal runtime implementation:
  lexer, parser, AST, compiler, bytecode, VM, values, tables, closures.

klua-api
  Stable Java-friendly public API:
  LuaState, Lua, LuaTable, LuaFunction, LuaUserData, LuaException.

klua-kotlin
  Kotlin convenience layer:
  extension functions, DSL helpers, reified type conversions.

klua-stdlib
  Standard libraries:
  base, table, string, math, utf8, coroutine, package, debug-lite.

klua-debug
  Lua-level debugging runtime:
  debug metadata, tracebacks, hooks, breakpoints, stepping, variable inspection.

klua-dap
  Optional Debug Adapter Protocol integration:
  VS Code / IDE debugger bridge over the internal KLua debug API.

klua-tools
  CLI tools:
  compiler, bytecode package validator, REPL, command-line debugger.

klua-jmh
  JMH benchmarks.

klua-tests
  Language conformance tests, golden tests, integration tests.
```

Keep `klua-core` mostly internal. The stable API should live in `klua-api`, with Kotlin-specific sugar in `klua-kotlin`. Debugging should be implemented as a real runtime subsystem, not as a thin wrapper around the JVM debugger.

---

## 4. Public API Layers

KLua should provide two public API levels:

```text
1. Low-level LuaState stack API
2. High-level Lua embedding API
```

### 4.1 Low-Level LuaState API

This API should feel familiar to users of Lua's C API or LuaJ-style APIs.

Example Java usage:

```java
LuaState L = LuaState.create();
L.openLibs();

L.load("return 1 + 2", "example.lua");
LuaStatus status = L.pcall(0, 1);

if (status.isOk()) {
    long result = L.toInteger(-1);
    System.out.println(result);
} else {
    System.err.println(L.toString(-1));
}
```

Recommended API shape:

```kotlin
class LuaState private constructor() {
    companion object {
        @JvmStatic
        fun create(): LuaState = LuaState()
    }

    fun getTop(): Int
    fun setTop(index: Int)
    fun pop(count: Int)

    fun pushNil()
    fun pushBoolean(value: Boolean)
    fun pushInteger(value: Long)
    fun pushNumber(value: Double)
    fun pushString(value: String)
    fun pushFunction(fn: LuaJavaFunction)

    fun type(index: Int): LuaType
    fun isNil(index: Int): Boolean
    fun isNumber(index: Int): Boolean
    fun isString(index: Int): Boolean

    fun toBoolean(index: Int): Boolean
    fun toInteger(index: Int): Long
    fun toNumber(index: Int): Double
    fun toString(index: Int): String?

    fun getGlobal(name: String): LuaType
    fun setGlobal(name: String)

    fun getField(index: Int, key: String): LuaType
    fun setField(index: Int, key: String)

    fun load(source: String, chunkName: String = "chunk"): LuaStatus
    fun pcall(nArgs: Int, nResults: Int): LuaStatus
}
```

Java friendliness rules:

- Use `@JvmStatic` for factories.
- Use `@JvmOverloads` for default parameters exposed to Java.
- Avoid exposing Kotlin function types in core Java APIs.
- Avoid exposing `kotlin.Result`, `KClass`, `Sequence`, `Flow`, or `suspend` in core public APIs.
- Prefer Java functional interfaces and explicit result classes.

---

### 4.2 High-Level Embedding API

The high-level API should feel closer to `mlua`: safe, ergonomic, and embedding-friendly.

Example Kotlin usage:

```kotlin
val lua = Lua.create()
lua.openLibs()

lua.globals["add"] = lua.function { a: Long, b: Long ->
    a + b
}

val result: Long = lua.load("return add(20, 22)").eval()
println(result)
```

Example Java usage:

```java
Lua lua = Lua.create();
lua.openLibs();

lua.globals().set("add", LuaFunction.of(ctx -> {
    long a = ctx.getLong(0);
    long b = ctx.getLong(1);
    return LuaReturn.of(a + b);
}));

long result = lua.load("return add(20, 22)").evalLong();
```

Recommended high-level classes:

```text
Lua
  Main runtime facade.

LuaChunk
  Loaded source or bytecode chunk.

LuaTableApi
  Safe wrapper around LuaTable.

LuaFunctionApi
  Callable Lua function wrapper.

LuaUserDataApi
  Wrapper for host objects.

LuaReturn
  Return values from host functions.

LuaCallContext
  Arguments and runtime context for host calls.

LuaConverter<T>
  Conversion between JVM values and Lua values.
```

---

## 5. Runtime Value Model

### 5.1 Simple v1 Model

Start with a simple object model that is easy to debug:

```kotlin
sealed interface LuaValue

data object LuaNil : LuaValue

data class LuaBool(val value: Boolean) : LuaValue
data class LuaInt(val value: Long) : LuaValue
data class LuaFloat(val value: Double) : LuaValue
data class LuaString(val value: String) : LuaValue

class LuaTable : LuaValue
class LuaClosure : LuaValue
class LuaNativeFunction : LuaValue
class LuaUserData(val value: Any) : LuaValue
class LuaThread : LuaValue
```

This is not the final fastest representation, but it makes the first implementation much easier.

### 5.2 Optimized Later Model

Once semantics are stable, replace boxed values on hot VM stacks with tagged slots.

Possible structure:

```kotlin
class LuaStackSlots(size: Int) {
    val tags = IntArray(size)
    val longs = LongArray(size)
    val doubles = DoubleArray(size)
    val refs = arrayOfNulls<Any>(size)
}
```

Recommended tags:

```text
NIL
BOOLEAN
INTEGER
FLOAT
STRING
TABLE
CLOSURE
NATIVE_FUNCTION
USERDATA
THREAD
```

Benefits:

- Fewer allocations for numbers and booleans.
- Better cache locality.
- Faster VM dispatch for arithmetic.
- Better chance for JVM JIT to optimize hot loops.

Do not implement this first unless profiling proves the simple model is too slow.

---

## 6. String Representation

Start with JVM `String`, then add string interning and byte-oriented behavior where needed.

Lua strings are byte strings, while JVM strings are Unicode UTF-16. This mismatch matters.

Recommended staged design:

```text
Stage 1:
  LuaString wraps Kotlin String.
  Good enough for language bring-up and common scripts.

Stage 2:
  LuaString stores ByteArray plus cached hash.
  Add UTF-8 helpers in utf8 library.

Stage 3:
  Intern short/common strings.
  Intern metamethod names and identifiers.
```

Important interned names:

```text
__index
__newindex
__call
__add
__sub
__mul
__div
__idiv
__mod
__pow
__unm
__eq
__lt
__le
__len
__tostring
__pairs
__ipairs
__gc
__close
```

---

## 7. Table Architecture

Lua table performance is one of the most important parts of KLua.

Use a hybrid table:

```text
LuaTable
  array part: optimized for positive integer keys starting at 1
  hash part: optimized for strings, non-array integers, booleans, userdata keys
  metatable: optional LuaTable
  shape/version: optional later optimization for inline caches
```

Initial implementation:

```kotlin
class LuaTable : LuaValue {
    private var array: Array<LuaValue?> = arrayOfNulls(8)
    private val hash: MutableMap<LuaKey, LuaValue> = LinkedHashMap()
    var metatable: LuaTable? = null
}
```

Later optimized implementation:

```text
Custom hash map
  open addressing
  special fast path for LuaString keys
  special fast path for integer keys
  nil assignment deletes keys
  raw get/set methods
  table get/set methods with metamethod handling
```

Core operations:

```kotlin
fun rawGet(key: LuaValue): LuaValue
fun rawSet(key: LuaValue, value: LuaValue)
fun get(key: LuaValue, state: LuaState): LuaValue
fun set(key: LuaValue, value: LuaValue, state: LuaState)
```

Table invariants:

- `nil` values are not stored as present entries.
- Integer key `1` maps to array index `0` internally.
- NaN cannot be a valid table key.
- `rawGet` and `rawSet` must bypass metatables.
- Normal `get` and `set` must honor `__index` and `__newindex`.

---

## 8. Bytecode and VM

KLua should use a custom register VM similar in spirit to Lua.

### 8.1 Function Prototype

```kotlin
class Prototype(
    val sourceName: String,
    val code: IntArray,
    val constants: Array<LuaValue>,
    val nested: Array<Prototype>,
    val upvalues: Array<UpvalueDesc>,
    val lineInfo: IntArray,
    val maxStackSize: Int,
    val numParams: Int,
    val isVararg: Boolean,
)
```

### 8.2 Instruction Encoding

Use `Int` instructions first.

Possible formats:

```text
ABC:
  opcode: 8 bits
  A:      8 bits
  B:      8 bits
  C:      8 bits

ABx:
  opcode: 8 bits
  A:      8 bits
  Bx:     16 bits

AsBx:
  opcode: 8 bits
  A:      8 bits
  sBx:    signed 16 bits
```

You can widen later if needed.

### 8.3 Core Opcodes

Start with:

```text
MOVE
LOAD_NIL
LOAD_BOOL
LOAD_INT
LOAD_FLOAT
LOAD_K
GET_GLOBAL
SET_GLOBAL
GET_TABLE
SET_TABLE
GET_FIELD
SET_FIELD
NEW_TABLE
ADD
SUB
MUL
DIV
IDIV
MOD
POW
UNM
NOT
LEN
EQ
LT
LE
JMP
TEST
CALL
TAILCALL
RETURN
CLOSURE
GET_UPVALUE
SET_UPVALUE
VARARG
FOR_PREP
FOR_LOOP
TFOR_PREP
TFOR_CALL
TFOR_LOOP
```

Add specialized opcodes after correctness:

```text
ADD_INT
ADD_FLOAT
GET_FIELD_CACHED
SET_FIELD_CACHED
GET_GLOBAL_CACHED
CALL_KNOWN
LOAD_SMALL_INT
```

---

## 9. VM Execution Model

Use explicit call frames:

```kotlin
class CallFrame(
    val closure: LuaClosure,
    val proto: Prototype,
    var pc: Int,
    val base: Int,
    val returnBase: Int,
    val expectedResults: Int,
)
```

VM state:

```kotlin
class LuaVm {
    val stack: LuaStack
    val frames: ArrayDeque<CallFrame>
    val registry: LuaTable
    val globals: LuaTable
    var instructionBudget: Long
}
```

Execution loop:

```kotlin
while (true) {
    val frame = currentFrame
    val instruction = frame.proto.code[frame.pc++]

    when (opcode(instruction)) {
        OP_MOVE -> executeMove(instruction)
        OP_LOAD_K -> executeLoadK(instruction)
        OP_CALL -> executeCall(instruction)
        OP_RETURN -> if (executeReturn(instruction)) break
        else -> error("unknown opcode")
    }
}
```

Hot-path rules:

- Avoid allocations per opcode.
- Avoid Kotlin lambdas inside the interpreter loop.
- Avoid exceptions for normal control flow.
- Keep opcode handlers small.
- Use arrays, not collections, in VM internals.
- Keep classes final where possible.
- Cache constants, globals, and metamethod names.

---

## 10. Closures and Upvalues

Lua closures require capturing variables by reference.

Recommended model:

```kotlin
sealed interface Upvalue {
    fun get(): LuaValue
    fun set(value: LuaValue)
}

class OpenUpvalue(
    val stack: LuaStack,
    val index: Int,
) : Upvalue

class ClosedUpvalue(
    private var value: LuaValue,
) : Upvalue
```

When a stack frame returns, close all open upvalues that point into that frame.

```text
Before return:
  upvalue -> stack slot

After return:
  upvalue -> heap cell containing copied value
```

This is essential for code like:

```lua
local function counter()
    local x = 0
    return function()
        x = x + 1
        return x
    end
end
```

---

## 11. Error Handling

Provide two modes:

```text
Low-level API:
  pcall returns LuaStatus and pushes error object.

High-level API:
  exec/eval/call throw LuaException.
```

Internal errors can use exceptions, but avoid using exceptions for normal fast-path behavior.

Recommended exception types:

```text
LuaException
LuaSyntaxException
LuaRuntimeException
LuaTypeException
LuaStackOverflowException
LuaInstructionLimitException
LuaYieldException       internal only
```

Error object should include:

```text
message
source name
line number
call stack
optional cause
```

---


## 12. Debugging Architecture

KLua should treat debugging as a first-class runtime feature, not as something delegated to the JVM debugger.

A JVM debugger can inspect the Kotlin implementation of KLua, such as the interpreter loop, bytecode dispatch, and table implementation. It cannot automatically understand Lua source lines, Lua locals, Lua upvalues, Lua coroutines, or Lua stack frames. KLua therefore needs its own Lua-level debugger built into the VM.

Recommended mental model:

```text
JVM debugger:
  Debugs KLua's Kotlin implementation.

KLua debugger:
  Debugs Lua code running inside KLua.

Debug Adapter Protocol layer:
  Connects KLua debugging to editors and IDEs.
```

### 12.1 Compiler Debug Metadata

Every compiled function prototype should optionally contain debug metadata:

```kotlin
class Prototype(
    val code: IntArray,
    val constants: Array<LuaValue>,
    val nested: Array<Prototype>,
    val maxStackSize: Int,
    val debug: DebugInfo?
)

class DebugInfo(
    val sourceName: String,
    val sourceId: String,
    val lineByPc: IntArray,
    val columnByPc: IntArray?,
    val localVars: Array<LocalVarInfo>,
    val upvalueNames: Array<String>,
    val validBreakpointLines: IntArray
)

class LocalVarInfo(
    val name: String,
    val slot: Int,
    val startPc: Int,
    val endPc: Int
)
```

Minimum required mappings:

```text
program counter -> source line
source line -> possible breakpoint PCs
local name -> stack slot and lifetime range
upvalue index -> upvalue name
function prototype -> source name and source id
```

This metadata is also needed for readable stack traces and bytecode package source maps.

### 12.2 Lua Stack Frames

The VM should expose Lua-level stack frames separately from JVM stack frames.

Internal frame shape:

```kotlin
class CallFrame(
    val closure: LuaClosure,
    var pc: Int,
    val base: Int,
    val top: Int,
    val returnBase: Int
)
```

Debug helpers:

```kotlin
fun CallFrame.currentLine(): Int
fun CallFrame.sourceName(): String
fun CallFrame.functionName(): String
```

Example traceback:

```text
Runtime error: attempt to index nil value 'player'
stack traceback:
  scripts/skill.lua:18: in function calculateDamage
  scripts/skill.lua:42: in function onCast
  scripts/server.lua:7: in main chunk
```

### 12.3 Debug Hooks

KLua should support Lua-style debug hooks internally:

```kotlin
enum class DebugEvent {
    CALL,
    RETURN,
    LINE,
    COUNT,
    TAIL_CALL,
    EXCEPTION
}

fun interface LuaDebugHook {
    fun onDebugEvent(thread: LuaThread, event: DebugEvent, frame: CallFrame)
}
```

Debug state:

```kotlin
class DebugState {
    var hook: LuaDebugHook? = null
    var hookMask: Int = 0
    var hookCount: Int = 0
    var instructionCounter: Int = 0

    val breakpoints = BreakpointManager()
    var stepMode: StepMode = StepMode.None
}
```

When debugging is disabled, the VM hot path should be almost unaffected. A practical implementation can use two loops:

```text
runFast():
  normal execution, minimal debug checks

runDebug():
  line hooks, count hooks, breakpoints, stepping, pause support
```

### 12.4 Breakpoints and Stepping

Breakpoints should be stored by source id and source line:

```kotlin
data class Breakpoint(
    val sourceId: String,
    val line: Int,
    val condition: LuaFunction? = null,
    val logMessage: String? = null,
    var enabled: Boolean = true
)
```

Stepping modes:

```kotlin
sealed class StepMode {
    data object None : StepMode()
    data object Into : StepMode()
    data class Over(val startDepth: Int) : StepMode()
    data class Out(val targetDepth: Int) : StepMode()
}
```

Behavior:

```text
Step into:
  stop on the next Lua source line, even inside a called Lua function.

Step over:
  stop on the next Lua source line when call depth is less than or equal to the original depth.

Step out:
  stop after returning to a shallower call depth.
```

### 12.5 Variable Inspection

Expose locals, upvalues, and globals through an internal debug view:

```kotlin
class DebugFrameView(
    private val thread: LuaThread,
    private val frameIndex: Int
) {
    fun sourceName(): String
    fun currentLine(): Int
    fun functionName(): String

    fun locals(): List<DebugVariable>
    fun upvalues(): List<DebugVariable>
    fun globals(): List<DebugVariable>

    fun getLocal(name: String): LuaValue?
    fun setLocal(name: String, value: LuaValue): Boolean
}
```

Example debugger display:

```text
Locals:
  damage = 120
  target = <Player id=1001>
  crit = true

Upvalues:
  config = <table>

Globals:
  math = <table>
  print = <function>
```

For userdata, provide a display adapter so host applications control what the debugger shows:

```kotlin
interface LuaDebugDisplay {
    fun displayName(value: Any): String
    fun fields(value: Any): List<DebugVariable>
}
```

### 12.6 Expression Evaluation

A debugger should support evaluating simple expressions inside a selected Lua frame:

```lua
damage * 2
target.hp
player.inventory[1]
```

Implementation idea:

```text
selected frame
  -> build debug environment from locals + upvalues + globals
  -> compile expression as "return (<expression>)"
  -> execute in protected debug-eval mode
```

Recommended safety modes:

```text
read-only eval:
  expressions only, no mutation-oriented APIs

unsafe eval:
  expressions and function calls

admin eval:
  full debug chunks
```

Production servers should disable unsafe eval unless explicitly enabled by trusted tooling.


### 12.7 Host/Kotlin Frame Debugging

Lua tracebacks should show host calls when Lua enters Kotlin or Java code.

Example:

```text
stack traceback:
  scripts/skill.lua:24: in function onCast
  [Kotlin]: in function combat.applyDamage
  scripts/skill_runner.lua:8: in main chunk
```

Recommended internal shape:

```kotlin
class NativeCallFrame(
    val name: String,
    val ownerClass: String?,
    val sourceHint: String?
)
```

When a Lua script calls a host function, push a native frame before invoking the host adapter and pop it afterward. This makes errors from host code much easier to understand from the Lua side.

### 12.8 Debug Adapter Protocol Layer

The optional `klua-dap` module should bridge KLua's internal debugger to editor tooling.

Architecture:

```text
VS Code / IDE
  -> Debug Adapter Protocol JSON messages
  -> klua-dap
  -> KLua DebugController
  -> KLua VM
```

DAP operations should map to internal APIs:

```text
setBreakpoints -> BreakpointManager
continue       -> DebugController.resume()
pause          -> DebugController.pause()
next           -> StepMode.Over
stepIn         -> StepMode.Into
stepOut        -> StepMode.Out
stackTrace     -> LuaThread.callStack
scopes         -> locals / upvalues / globals
variables      -> DebugFrameView
evaluate       -> evalInFrame()
```

### 12.9 Coroutine Debugging

Lua coroutines should appear as debugger threads:

```text
Threads:
  main
  coroutine-1
  coroutine-2
```

Internal model:

```kotlin
class LuaThread {
    val id: Long
    val name: String?
    val frames: ArrayDeque<CallFrame>
    var status: LuaThreadStatus
    val debugState: DebugState
}
```

For the first implementation, pausing one debugged Lua thread may pause the whole KLua VM. Later versions can support pausing only the selected coroutine.

### 12.10 Lua Debug Library Exposure

Expose the debug library gradually:

```lua
debug.traceback()
debug.getinfo(level)
debug.getlocal(level, index)
debug.setlocal(level, index, value)
debug.getupvalue(func, index)
debug.setupvalue(func, index, value)
debug.upvalueid(func, index)
debug.upvaluejoin(func1, index1, func2, index2)
debug.getmetatable(value)
debug.setmetatable(value, metatable)
debug.getregistry()
debug.sethook(hook, mask, count)
debug.gethook()
```

Use profiles:

```text
Development:
  full debug library enabled

Test:
  traceback + getinfo enabled

Production:
  traceback only, or debug library disabled
```

The debug library can inspect and mutate runtime internals, so it should be disabled or restricted by default in sandboxed game-server environments.

---

## 13. Coroutines

Do not implement Lua coroutines with JVM threads.

Use VM-managed coroutine state:

```kotlin
class LuaThread : LuaValue {
    val stack: LuaStack
    val frames: ArrayDeque<CallFrame>
    var status: LuaThreadStatus
}
```

Possible statuses:

```text
NEW
RUNNING
SUSPENDED
NORMAL
DEAD
```

Yield/resume behavior:

```text
coroutine.resume(thread, args...)
  -> runs thread until return, error, or yield

coroutine.yield(values...)
  -> suspends current LuaThread
  -> returns yielded values to resumer
```

Initial limitation:

```text
Yield is allowed across Lua frames.
Yield across non-yieldable Kotlin/native calls is not allowed in v1.
```

This is acceptable and matches the kind of limitations many embeddable runtimes have.

---

## 14. Memory Management

Because KLua runs on the JVM, it should use JVM GC for ordinary Lua objects.

This means Lua cycles are collectable:

```lua
local a = {}
local b = {}
a.b = b
b.a = a
```

If `a` and `b` become unreachable from JVM roots, the JVM GC can collect them.

KLua still needs Lua-specific memory features:

```text
weak tables
userdata finalization
__gc metamethod behavior
__close variables
resource cleanup
memory accounting / limits
```

### 14.1 Weak Tables

Lua weak tables are not the same as ordinary JVM weak references. Plan them carefully.

Required modes:

```text
weak keys
weak values
weak keys and values
```

Possible implementation:

```text
Weak values:
  store WeakReference<LuaValue> as value.

Weak keys:
  custom weak-key map with identity/equality handling.

Both:
  custom map with weak key and weak value entries.
```

Weak tables can be postponed until after core language semantics, but do not ignore them if Lua compatibility matters.

---

## 15. Standard Library Design

Recommended default libraries:

```text
base
coroutine
table
string
math
utf8
package
debug-lite
```

Optional or restricted libraries:

```text
io
os
debug-full
```

For game/server embedding, dangerous APIs should be opt-in:

```text
os.execute
io.open
package.loadlib
debug access to internals
```

### 15.1 Native vs Lua-Written Standard Library

Use a hybrid approach:

```text
Native Kotlin:
  VM internals, table primitives, string primitives, math, userdata, package loader.

Lua source:
  convenience wrappers, helper functions, higher-level utilities.
```

Example:

```text
Native:
  table_raw_get
  table_raw_set
  string_sub
  string_byte
  math_sin

Lua-written:
  table.pack
  table.unpack wrapper
  string.startswith
  assert helpers
```

Compile Lua-written stdlib into KLua bytecode during build and embed it as a resource.

---

## 16. UserData and JVM Interop

KLua should support host objects as userdata.

Two binding styles:

```text
Manual binding API
Reflection/annotation binding API
```

### 16.1 Manual Binding

Java-friendly example:

```java
lua.registerType(Player.class, type -> {
    type.method("getLevel", (player, ctx) -> LuaReturn.of(player.getLevel()));
    type.method("addExp", (player, ctx) -> {
        player.addExp(ctx.getLong(0));
        return LuaReturn.none();
    });
});
```

Kotlin-friendly example:

```kotlin
lua.registerType<Player> {
    method("getLevel") { player -> player.level }
    method("addExp") { player, exp: Long -> player.addExp(exp) }
}
```

### 16.2 Reflection Binding

Annotation example:

```kotlin
class Player {
    @LuaMethod
    fun addExp(exp: Long) { ... }

    @LuaProperty
    val level: Int = 1
}
```

Performance rule:

```text
Reflection is allowed at registration time.
Reflection should not happen on every Lua call.
```

Use cached call adapters:

```text
registration
  -> inspect methods
  -> create MethodHandle or generated adapter
  -> runtime call uses cached adapter
```

---

## 17. Sandbox and Game-Server Features

For game/server usage, KLua should support runtime limits:

```text
instruction budget
wall-clock budget, optional
memory accounting
module whitelist
stdlib whitelist
disabled io/os/full-debug by default
host API permission model
restricted debug-eval permissions
```

Execution budget example:

```kotlin
lua.load(script)
    .withInstructionLimit(100_000)
    .exec()
```

When limit is exceeded:

```text
throw LuaInstructionLimitException
or return LuaStatus.INSTRUCTION_LIMIT_EXCEEDED
```

---

## 18. Performance Architecture

### 18.1 First Performance Goals

Optimize in this order:

```text
1. Table get/set
2. Function calls
3. Lua -> Kotlin calls
4. Numeric loops
5. Global access
6. String operations
7. Coroutine resume/yield
```

Game scripts usually spend a lot of time in table access, property access, and host API calls.

### 18.2 Inline Caches

Add inline caches after the VM is correct.

For example:

```lua
player.level
```

Naive path:

```text
hash lookup "level"
check metatable
maybe call __index
```

Cached path:

```text
check table shape/version
read cached slot
fallback if shape changed
```

Instruction-level cache:

```kotlin
class InlineCache {
    var shape: TableShape? = null
    var key: LuaString? = null
    var slot: Int = -1
}
```

Store cache array on `Prototype` or compiled function metadata.

### 18.3 Specialized Bytecode

After profiling, add specialized opcodes:

```text
ADD_INT
ADD_FLOAT
GET_FIELD_STRING
SET_FIELD_STRING
GET_GLOBAL_CACHED
CALL_NATIVE_FAST
```

Avoid adding too many opcodes before benchmarks exist.

### 18.4 Optional JVM Bytecode Compiler

Long-term optional pipeline:

```text
KLua bytecode / IR
  -> JVM bytecode generation
  -> generated class
  -> JVM JIT optimizes hot functions
```

Use this only after:

```text
interpreter is correct
bytecode format is stable
benchmarks exist
host API is stable
```

The JVM JIT optimizes JVM bytecode and hot Kotlin code. It does not automatically understand Lua semantics. Good performance still requires:

```text
stable call paths
specialized operations
inline caches
guards and fallbacks
cached host bindings
```

---

## 19. Testing Strategy

Use several test layers:

```text
Lexer tests
Parser tests
Compiler golden tests
VM instruction tests
Language behavior tests
Standard library tests
Interop tests
Coroutine tests
Debug metadata tests
Breakpoint and stepping tests
DAP adapter tests
Sandbox tests
JMH performance tests
```

Golden compiler test example:

```text
source.lua
  -> expected AST
  -> expected bytecode listing
  -> expected result
```

Interop tests should cover:

```text
Java calling Lua
Kotlin calling Lua
Lua calling Java/Kotlin functions
Userdata methods
Userdata properties
Error propagation across boundary
```


### 19.1 Lua 5.5 Conformance Test Matrix

Run conformance tests through the normal test task, grouped by runtime area:

```text
./gradlew test
```

Test layout:

```text
tests/
  lua55/
    official/
    klua-extra/
    stdlib/
    coroutine/
    debug/
    package/
```

CI strategy:

```text
Every commit:
  run unit and integration tests

Nightly:
  run full conformance, debugger, packaging, and benchmark smoke checks

Before release:
  run conformance, benchmarks, debugger tests, and package-load tests
```

Important rule:

```text
Lua 5.5 behavior is only real when it has tests or a documented conformance gap.
```


---

## 20. Packaging and Deployment

Recommended artifacts:

```text
io.github.realmlabs.klua:klua-core
io.github.realmlabs.klua:klua-api
io.github.realmlabs.klua:klua-stdlib
io.github.realmlabs.klua:klua-kotlin
io.github.realmlabs.klua:klua-all
```

`klua-all` can include everything for convenience.

For server deployment:

```text
Lua source files
  -> compile during build/CI
  -> KLua bytecode package
  -> optional checksum/signature
  -> load package at server startup
```

Development mode:

```text
load source directly
preserve source maps
rich error messages
hot reload
```

Production mode:

```text
load bytecode package
validate version/checksum
faster startup
stable deployment artifact
```

---

## 21. Recommended First MVP Architecture

The first serious MVP should include:

```text
Lexer/parser/compiler
Custom register bytecode VM
Simple LuaValue object model
Tables
Functions and closures
Varargs and multiple returns
Basic metatables: __index, __newindex, __call, arithmetic
Low-level LuaState API
High-level Lua API
Base/table/string/math partial stdlib
Source names, line info, and readable traceback
Minimal DebugController with pause/continue hooks
JMH benchmark setup
```

Avoid in the first MVP:

```text
JVM bytecode generation
full debug API and IDE debugger
official .luac loading
native C module compatibility
perfect weak table behavior
full Lua 5.5 edge-case coverage
```

This gives you a usable, testable foundation without getting stuck in advanced runtime details too early.

---

## 22. Long-Term Architecture Direction

Recommended evolution:

```text
v0.1:
  correct interpreter MVP

v0.2:
  embedding API, userdata, partial stdlib

v0.3:
  coroutines, metatables, better conformance

v0.4:
  debug metadata, tracebacks, Lua stack frame views

v0.5:
  source-level debugger: hooks, breakpoints, stepping, variable inspection

v0.6:
  script packaging, bytecode loading, sandbox limits

v0.7:
  performance pass: table, call, stack, inline caches, fast/debug VM loops

v0.8:
  optional Debug Adapter Protocol integration and CLI debugger

v0.9:
  Lua 5.5 conformance hardening and documented gap closure

v1.0:
  stable API, documented behavior, conformance suite, debugging guide

post-v1:
  optional JVM bytecode compiler and profiling-guided optimization
```

The best design principle:

```text
Make KLua correct and pleasant to embed first.
Make it fast second.
Make it clever third.
```


---

## References

These are useful compatibility references while implementing KLua:

- Lua version history: https://www.lua.org/versions.html
- Lua download/current release page: https://www.lua.org/download.html
- Lua reference manuals: https://www.lua.org/manual/
- Lua test suites: https://www.lua.org/tests/
