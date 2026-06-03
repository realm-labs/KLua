# KLua Implementation Milestones

**Project:** KLua  
**Runtime:** Pure Kotlin Lua implementation for JVM 17+  
**Primary goals:** Lua-like compatibility, Java/Kotlin-friendly embedding, good performance, and future JVM JIT optimization potential.

---

## Milestone Overview

Recommended milestone sequence:

```text
M0  Project foundation
M1  Lexer and parser
M2  AST and compiler skeleton
M3  Minimal bytecode VM
M4  Expressions, locals, branches, loops
M5  Functions, calls, returns, varargs
M6  Tables
M7  Closures and upvalues
M8  Metatables and metamethods
M9  Low-level LuaState API
M10 High-level embedding API
M11 Userdata and JVM interop
M12 Standard library
M13 Coroutines
M14 Error handling, tracebacks, and debug metadata
M15 Debug hooks and source-level debugger
M16 DAP adapter and debug tooling
M17 Script packaging and bytecode loading
M18 Sandbox and game-server limits
M19 Performance pass 1
M20 Multi-version compatibility hardening
M21 v1.0 release
M22 Optional JVM bytecode compiler
```

The most important rule:

```text
Do not optimize too early.
Build a correct, testable interpreter first.
```

---

## M0: Project Foundation

### Goal

Create the Gradle project and establish coding, testing, and benchmarking foundations.

### Tasks

- Create multi-module Gradle project.
- Configure Kotlin JVM target 17.
- Add test framework.
- Add JMH benchmark module.
- Add code formatting/linting.
- Add CI build.
- Define package naming convention.
- Define internal vs public API boundaries.

### Suggested modules

```text
klua-core
klua-api
klua-kotlin
klua-stdlib
klua-compat
klua-debug
klua-dap
klua-jmh
klua-tests
```

### Success criteria

- Project builds on JVM 17.
- Unit tests run in CI.
- JMH benchmark module can execute a dummy benchmark.
- Public packages and internal packages are separated.

### Risks

- Exposing internal runtime types too early.
- Making the API Kotlin-only and awkward for Java users.

---

## M1: Lexer and Parser

### Goal

Parse Lua-like source code into an AST.

### Tasks

- Implement lexer/tokenizer.
- Support comments.
- Support identifiers and keywords.
- Support integer and floating-point literals.
- Support string literals.
- Support operators and punctuation.
- Implement recursive-descent or Pratt parser.
- Parse expressions with correct precedence.
- Parse statements and blocks.

### Syntax to support first

```lua
local x = 1 + 2 * 3
if x > 3 then
    return x
else
    return 0
end
```

### Success criteria

- Can parse simple chunks.
- Can produce stable AST snapshots.
- Syntax errors include source name, line, and column.

### Tests

- Token golden tests.
- AST golden tests.
- Syntax error tests.

### Risks

- Getting Lua expression precedence wrong.
- Poor error messages that make scripts hard to debug.

---

## M2: AST and Compiler Skeleton

### Goal

Compile AST into a basic function prototype and bytecode listing.

### Tasks

- Define AST model.
- Define `Prototype` structure.
- Define bytecode instruction encoding.
- Define opcode enum.
- Implement constant pool.
- Implement local variable tracking.
- Implement simple register allocation.
- Emit bytecode for constants and returns.
- Add bytecode disassembler for debugging.

### First target source

```lua
return 42
```

### Expected output shape

```text
LOAD_INT R0 42
RETURN   R0 1
```

### Success criteria

- Compiler emits bytecode for simple return expressions.
- Bytecode can be printed in readable form.
- Compiler tests compare source to expected bytecode.

### Risks

- Register allocation becoming too complicated too early.
- No bytecode disassembler, making debugging painful.

---

## M3: Minimal Bytecode VM

### Goal

Execute simple bytecode chunks.

### Tasks

- Implement `LuaValue` model.
- Implement `LuaStack`.
- Implement `CallFrame`.
- Implement VM loop.
- Implement basic opcodes:

```text
LOAD_NIL
LOAD_BOOL
LOAD_INT
LOAD_FLOAT
LOAD_K
MOVE
RETURN
```

### First target

```lua
return 1
```

### Success criteria

- VM executes compiled chunks.
- Return values are observable from tests.
- Runtime errors include basic source info.

### Tests

- Return nil.
- Return boolean.
- Return integer.
- Return float.
- Return string constant.

### Risks

- Treating top-level chunk differently from functions too early.
- Not preserving multiple return value design from the start.

---

## M4: Expressions, Locals, Branches, and Loops

### Goal

Support enough language features to run small programs.

### Tasks

- Arithmetic operators.
- Comparison operators.
- Boolean operators.
- Unary operators.
- Local variables.
- Assignment.
- `if/then/elseif/else/end`.
- `while` loops.
- `repeat/until` loops.
- Numeric `for` loops.
- Jump patching.

### Opcodes to add

```text
ADD
SUB
MUL
DIV
IDIV
MOD
POW
UNM
NOT
EQ
LT
LE
JMP
TEST
FOR_PREP
FOR_LOOP
```

### Target script

```lua
local sum = 0
for i = 1, 100 do
    sum = sum + i
end
return sum
```

### Success criteria

- Numeric loops work.
- Branches work.
- Local variables work.
- Arithmetic and comparisons pass behavior tests.

### Risks

- Incorrect truthiness: only `nil` and `false` are false in Lua.
- Incorrect integer/float behavior.
- Off-by-one errors in numeric `for` loops.

---

## M5: Functions, Calls, Returns, and Varargs

### Goal

Support Lua functions and normal function calls.

### Tasks

- Parse function declarations and expressions.
- Compile nested function prototypes.
- Implement `CALL`.
- Implement `RETURN` with multiple values.
- Implement `TAILCALL`, optional at first.
- Implement parameter binding.
- Implement varargs.
- Implement multiple return values.
- Implement function values.

### Target script

```lua
local function add(a, b)
    return a + b
end

return add(20, 22)
```

### Multiple return target

```lua
local function pair()
    return 1, 2
end

local a, b = pair()
return a + b
```

### Success criteria

- Functions can call other functions.
- Recursive functions work.
- Multiple returns work in common cases.
- Varargs work for simple functions.

### Risks

- Multiple return semantics are one of Lua's tricky parts.
- Tail call optimization can be postponed if needed.

---

## M6: Tables

### Goal

Implement Lua tables and table constructors.

### Tasks

- Implement `LuaTable`.
- Support array-style integer keys.
- Support string keys.
- Support general hash keys.
- Parse and compile table constructors.
- Implement `GET_TABLE` and `SET_TABLE`.
- Implement `GET_FIELD` and `SET_FIELD` for string keys.
- Implement length operator for simple arrays.
- Implement `pairs` and `ipairs` basics later with stdlib.

### Target script

```lua
local t = { x = 10, y = 20 }
t.z = t.x + t.y
return t.z
```

### Success criteria

- Table construction works.
- Field access works.
- Integer indexing works.
- Assigning `nil` deletes entries.

### Risks

- Lua table length behavior has edge cases.
- Using a normal `HashMap` is okay for v1 but may not be fast enough later.

---

## M7: Closures and Upvalues

### Goal

Support lexical closures correctly.

### Tasks

- Analyze captured local variables.
- Represent open upvalues.
- Close upvalues when stack frames return.
- Support nested closures.
- Support reading and writing captured variables.

### Target script

```lua
local function counter()
    local x = 0
    return function()
        x = x + 1
        return x
    end
end

local c = counter()
return c(), c(), c()
```

Expected result:

```text
1, 2, 3
```

### Success criteria

- Closures keep captured variables alive.
- Mutating captured variables works.
- Multiple closures sharing the same upvalue see the same value.

### Risks

- Accidentally capturing by value instead of by reference.
- Forgetting to close upvalues when frames return.

---

## M8: Metatables and Metamethods

### Goal

Make runtime behavior feel like real Lua.

### Tasks

- Implement metatable storage.
- Implement `setmetatable` and `getmetatable`.
- Implement `rawget`, `rawset`, `rawequal`.
- Implement `__index`.
- Implement `__newindex`.
- Implement `__call`.
- Implement arithmetic metamethods.
- Implement comparison metamethods.
- Implement `__len`.
- Implement `__tostring`.

### Target script

```lua
local proto = {
    get = function(self)
        return self.value
    end
}

local obj = setmetatable({ value = 42 }, { __index = proto })
return obj:get()
```

### Success criteria

- Prototype-style objects work.
- Operator overloads work.
- Raw operations bypass metatables.
- Metamethod lookup is correct in common cases.

### Risks

- Infinite recursion in `__index` / `__newindex`.
- Incorrect method call sugar `obj:method(x)` behavior.

---

## M9: Low-Level LuaState API

### Goal

Expose a C-Lua-like stack API for Java/Kotlin embedders.

### Tasks

- Implement `LuaState.create()`.
- Implement stack inspection and manipulation.
- Implement push methods.
- Implement conversion methods.
- Implement `load` and `pcall`.
- Implement globals API.
- Implement field access API.
- Implement native function registration.
- Add Java examples.

### Target Java usage

```java
LuaState L = LuaState.create();
L.openLibs();
L.load("return 1 + 2", "test.lua");
LuaStatus status = L.pcall(0, 1);
long result = L.toInteger(-1);
```

### Success criteria

- Java users can load and call Lua.
- Java users can register functions.
- Stack API is documented.
- Errors can be handled without exceptions through `LuaStatus`.

### Risks

- Kotlin-specific API shapes leaking into Java-facing API.
- Stack index behavior not matching Lua expectations.

---

## M10: High-Level Embedding API

### Goal

Provide ergonomic `mlua`-style APIs.

### Tasks

- Implement `Lua` facade.
- Implement `LuaChunk`.
- Implement `LuaTableApi`.
- Implement `LuaFunctionApi`.
- Implement `LuaReturn` and `LuaCallContext`.
- Implement typed conversion interface.
- Provide Kotlin DSL extensions in `klua-kotlin`.
- Provide Java-friendly functional interfaces in `klua-api`.

### Target Kotlin usage

```kotlin
val lua = Lua.create()
lua.globals["add"] = lua.function { a: Long, b: Long -> a + b }
val result: Long = lua.load("return add(1, 2)").eval()
```

### Target Java usage

```java
Lua lua = Lua.create();
lua.globals().set("add", LuaFunction.of(ctx -> {
    return LuaReturn.of(ctx.getLong(0) + ctx.getLong(1));
}));
long result = lua.load("return add(1, 2)").evalLong();
```

### Success criteria

- Simple embedding is pleasant in Java and Kotlin.
- High-level API throws structured exceptions.
- Low-level API remains available.

### Risks

- Overloading the high-level API with too many generic features too early.
- Reflection-based conversions becoming hidden performance traps.

---

## M11: Userdata and JVM Interop

### Goal

Allow Lua scripts to safely use Java/Kotlin host objects.

### Tasks

- Implement `LuaUserData`.
- Implement manual type registration.
- Implement method binding.
- Implement property binding.
- Implement cached invocation adapters.
- Implement optional annotation binding.
- Add userdata metatables.
- Add conversion rules for common JVM types.

### Target Java usage

```java
lua.registerType(Player.class, type -> {
    type.method("getLevel", (player, ctx) -> LuaReturn.of(player.getLevel()));
    type.method("addExp", (player, ctx) -> {
        player.addExp(ctx.getLong(0));
        return LuaReturn.none();
    });
});
```

### Target Lua usage

```lua
player:addExp(100)
return player:getLevel()
```

### Success criteria

- Lua can call host methods.
- Host objects retain identity.
- Runtime avoids reflection per call.
- Type errors are clear.

### Risks

- Expensive reflection in hot loops.
- Ambiguous overloaded method resolution.
- Accidentally exposing unsafe host APIs.

---

## M12: Standard Library

### Goal

Implement the useful Lua standard libraries.

### Tasks

Implement base library:

```text
assert
error
print
type
tostring
tonumber
select
pcall
xpcall
pairs
ipairs
next
rawequal
rawget
rawset
setmetatable
getmetatable
```

Implement table library:

```text
insert
remove
concat
sort
pack
unpack
move
```

Implement string library:

```text
len
sub
byte
char
find
match
gsub
gmatch
format
lower
upper
rep
reverse
```

Implement math library:

```text
abs
floor
ceil
sin
cos
tan
min
max
random
randomseed
```

Implement utf8 library after string basics.

### Success criteria

- Common Lua scripts using base/table/string/math run.
- Dangerous libraries are opt-in.
- Standard library functions have tests.

### Risks

- Pattern matching and `string.format` can take significant effort.
- `table.sort` must handle comparator edge cases.

---

## M13: Coroutines

### Goal

Implement Lua coroutine behavior without JVM threads.

### Tasks

- Implement `LuaThread`.
- Implement coroutine state.
- Implement `coroutine.create`.
- Implement `coroutine.resume`.
- Implement `coroutine.yield`.
- Implement `coroutine.status`.
- Implement `coroutine.running`.
- Implement `coroutine.wrap`.
- Decide yield rules across native calls.

### Target script

```lua
local co = coroutine.create(function()
    coroutine.yield(1)
    coroutine.yield(2)
    return 3
end)

local _, a = coroutine.resume(co)
local _, b = coroutine.resume(co)
local _, c = coroutine.resume(co)
return a, b, c
```

Expected result:

```text
1, 2, 3
```

### Success criteria

- Yield/resume works across Lua frames.
- Coroutine errors are reported correctly.
- Non-yieldable host calls are detected.

### Risks

- Yield across native calls is difficult.
- Exception-based yield is simple but may hurt performance if overused.

---


## M14: Error Handling, Tracebacks, and Debug Metadata

### Goal

Make runtime errors useful and prepare the VM for source-level debugging.

This milestone is not the full debugger yet. It creates the foundation that makes a debugger possible.

### Tasks

- Track source names and stable source IDs.
- Track line numbers and optional columns.
- Add `pc -> line` mapping to every compiled prototype.
- Add valid breakpoint line metadata.
- Track local variable names, stack slots, and lifetime ranges.
- Track upvalue names.
- Track Lua call stack frames separately from JVM stack frames.
- Track host/native frames when Lua calls Kotlin/Java functions.
- Implement structured `LuaException`.
- Implement `pcall` and `xpcall` behavior.
- Implement readable Lua tracebacks.
- Implement minimal `debug.traceback()` and `debug.getinfo()`.
- Add source maps for bytecode packages.
- Add debug metadata serialization hooks, even if packaging is implemented later.

### Recommended structures

```kotlin
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

### Example error output

```text
Runtime error: attempt to call nil value 'foo'
stack traceback:
  scripts/skill.lua:17: in function cast
  [Kotlin]: combat.applyDamage
  scripts/main.lua:42: in main chunk
```

### Success criteria

- Errors identify source file and line.
- Stack traces include Lua frames.
- Stack traces can include useful Java/Kotlin host call frames.
- Function prototypes carry enough metadata for breakpoints and local inspection later.
- Local variable metadata can be queried in tests.
- Debug metadata survives compile/load round trips.
- Bytecode packages can optionally include or strip debug metadata.
- Java/Kotlin host exceptions are wrapped clearly.

### Tests

- Error line number tests.
- Stack traceback golden tests.
- Local variable lifetime tests.
- Upvalue name tests.
- Host exception wrapping tests.
- Debug metadata serialization tests.

### Risks

- Poor errors make the language feel unfinished even if the VM is correct.
- Missing local/upvalue metadata will make the later debugger much harder.
- Debug metadata can increase bytecode size; it should be optional or strippable for production.
- Mapping source lines incorrectly will make breakpoints feel unreliable.
- Full debug access can expose unsafe capabilities in sandboxed environments.

---

## M15: Debug Hooks and Source-Level Debugger

### Goal

Implement a Lua source-level debugger inside KLua.

The JVM debugger should be used to debug KLua's Kotlin runtime. KLua's own debugger should be used to debug Lua code.

### Tasks

- Add `DebugState` to `LuaThread` or VM execution context.
- Add debug events: `CALL`, `RETURN`, `LINE`, `COUNT`, `TAIL_CALL`, `EXCEPTION`.
- Add Lua-style debug hooks.
- Add a `BreakpointManager` keyed by `sourceId + line`.
- Support source-line breakpoints.
- Support conditional breakpoints.
- Support logpoints later, optional.
- Add pause and continue support.
- Add step into.
- Add step over.
- Add step out.
- Add `DebugController` as the main internal debugger API.
- Add `DebugFrameView` for stack frame inspection.
- Add local variable inspection.
- Add upvalue inspection.
- Add global inspection.
- Add debugger-safe value rendering for tables and userdata.
- Add expression evaluation in a selected stack frame.
- Add read-only eval mode.
- Add restricted unsafe eval mode for trusted tooling.
- Add coroutine/thread awareness.
- Add tests for breakpoints, stepping, and inspection.

### Recommended core types

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

sealed class StepMode {
    data object None : StepMode()
    data object Into : StepMode()
    data class Over(val startDepth: Int) : StepMode()
    data class Out(val targetDepth: Int) : StepMode()
}

class DebugController {
    fun pause(thread: LuaThread)
    fun resume(thread: LuaThread)
    fun setBreakpoint(sourceId: String, line: Int): Breakpoint
    fun clearBreakpoint(sourceId: String, line: Int)
    fun stepInto(thread: LuaThread)
    fun stepOver(thread: LuaThread)
    fun stepOut(thread: LuaThread)
    fun stackTrace(thread: LuaThread): List<DebugFrameView>
    fun evaluate(frame: DebugFrameView, expression: String): LuaValue
}
```

### Debugger behavior

```text
breakpoint:
  stop when current sourceId + line matches an enabled breakpoint.

step into:
  stop on the next Lua source line, including inside function calls.

step over:
  stop on the next Lua source line when call depth <= original depth.

step out:
  stop after returning to a shallower call depth.
```

### Expression evaluation design

```text
selected frame
  -> create temporary debug environment
  -> compile expression as `return (<expr>)`
  -> execute with access to locals, upvalues, and globals
```

Start with expression-only eval. Full statement eval should be admin-only or disabled by default.

### Success criteria

- A script can stop at a breakpoint.
- A script can step into, over, and out.
- The debugger can show current source line.
- Locals, upvalues, and globals are inspectable.
- Debug expression evaluation works for simple expressions.
- Coroutines appear as debuggable Lua threads.
- Debug checks are cheap or disabled in normal fast execution.

### Tests

- Breakpoint hit tests.
- Conditional breakpoint tests.
- Step into/over/out tests.
- Local/upvalue/global inspection tests.
- Debug expression eval tests.
- Coroutine breakpoint tests.
- Fast-mode test proving debug hooks do not run when disabled.

### Risks

- Checking breakpoints on every instruction can hurt performance.
- Debug hooks can slow down the interpreter if always enabled.
- Eval-in-frame can mutate server state if not restricted.
- Breakpoint line mapping is tricky for multi-line expressions and generated code.
- Coroutine debugging can get confusing if pause/resume policy is unclear.

---

## M16: DAP Adapter and Debug Tooling

### Goal

Expose KLua debugging to real developer tools.

The recommended long-term route is a Debug Adapter Protocol-style adapter. The adapter should translate IDE debugger requests into calls to KLua's internal `DebugController`.

### Tasks

- Create `klua-dap` module.
- Create `klua-cli` debug runner.
- Create a simple JSON message transport.
- Implement launch and attach modes.
- Implement `initialize`.
- Implement `setBreakpoints`.
- Implement `configurationDone`.
- Implement `continue`, `pause`, `next`, `stepIn`, and `stepOut`.
- Implement `threads` for Lua coroutines.
- Implement `stackTrace` for Lua frames.
- Implement `scopes` for locals/upvalues/globals.
- Implement `variables` with paging for large tables.
- Implement `evaluate` using frame expression evaluation.
- Add debugger display adapters for userdata.
- Add documentation for editor integration.
- Add example VS Code launch configuration.

### Mapping

```text
DAP setBreakpoints -> BreakpointManager
DAP continue       -> DebugController.resume()
DAP next           -> StepMode.Over
DAP stepIn         -> StepMode.Into
DAP stepOut        -> StepMode.Out
DAP stackTrace     -> LuaThread.frames
DAP scopes         -> locals / upvalues / globals
DAP variables      -> DebugFrameView and debug display adapters
DAP evaluate       -> evalInFrame()
```

### CLI debugger MVP

```text
klua --debug script.lua

commands:
  break <file>:<line>
  run
  continue
  next
  step
  out
  bt
  locals
  print <expr>
  quit
```

### Success criteria

- A script can be launched under the CLI debugger.
- Breakpoints and stepping work through the debug controller.
- A DAP client can set breakpoints and step through Lua code.
- A DAP client can request stack frames and variables.
- Coroutines appear as debugger threads.
- Large tables do not freeze the debugger UI.
- Debugger can be disabled completely in production builds or production profiles.

### Risks

- DAP details can distract from core VM work.
- Variable expansion for huge tables can be expensive.
- IDE integration should not block the core runtime release.
- Debugger protocol tests are important because manual testing is slow.
- Debugger tooling should not leak unsafe debug capabilities into production.

---
## M17: Script Packaging and Bytecode Loading

### Goal

Support production deployment without parsing all source at startup.

### Tasks

- Define KLua bytecode file format.
- Add version header.
- Add constant pool serialization.
- Add prototype serialization.
- Add debug info serialization.
- Add bytecode validator.
- Add compiler CLI.
- Add Gradle plugin later, optional.
- Add package loader.

### Package flow

```text
source scripts
  -> compile during build
  -> klua bytecode package
  -> validate checksum/signature
  -> load in server runtime
```

### Success criteria

- Runtime can load compiled bytecode.
- Bytecode version mismatch is detected.
- Source mode and bytecode mode produce same results.

### Risks

- Freezing bytecode format too early.
- Loading untrusted bytecode without validation.

---

## M18: Sandbox and Game-Server Limits

### Goal

Make KLua safe for game server scripting.

### Tasks

- Implement instruction budget.
- Implement optional memory accounting.
- Implement module whitelist.
- Implement stdlib whitelist.
- Disable dangerous APIs by default.
- Add execution timeout integration point.
- Add deterministic random option.
- Add host permission model.

### Example API

```kotlin
lua.load(script)
    .withInstructionLimit(100_000)
    .exec()
```

### Success criteria

- Infinite loops can be stopped.
- Scripts cannot access OS/files unless allowed.
- Host APIs can be selectively exposed.

### Risks

- Wall-clock timeouts are harder than instruction budgets.
- Memory limits are approximate on the JVM unless carefully accounted.

---

## M19: Performance Pass 1

### Goal

Optimize based on benchmark data, not guesses.

### Tasks

- Add JMH benchmark suite.
- Benchmark parse/compile time.
- Benchmark VM numeric loops.
- Benchmark function calls.
- Benchmark table get/set.
- Benchmark metamethod calls.
- Benchmark Lua -> JVM calls.
- Benchmark JVM -> Lua calls.
- Optimize table representation.
- Optimize VM stack representation if needed.
- Add string interning.
- Add fast paths for common arithmetic.
- Add inline caches for field/global access.
- Cache host method bindings.

### Benchmark categories

```text
fib
binary trees
numeric for loop
table field get/set
closure counter
method call
host function call
string concat
coroutine yield/resume
debug hook overhead
breakpoint stepping
```

### Success criteria

- Benchmarks are stable and automated.
- Table access and calls improve measurably.
- No compatibility regressions.

### Risks

- Optimizing without baseline numbers.
- Making the runtime harder to maintain for small gains.

---

## M20: Multi-Version Compatibility Hardening

### Goal

Move from “Lua-like” toward reliable Lua compatibility across named version profiles.

Default target remains Lua 5.4, but KLua should have an architecture that can support:

```text
Lua 5.1
Lua 5.2
Lua 5.3
Lua 5.4
Lua 5.5
LuaJIT-like compatibility mode
```

### Tasks

- Add `LuaVersion` enum.
- Add `LuaConfig` with selected language version.
- Add `LuaVersionProfile` interface.
- Add `LexerProfile`, `ParserProfile`, `SemanticsProfile`, `StdlibProfile`, and `ApiProfile`.
- Tag each compiled `Prototype` with its target `LuaVersion`.
- Make `LuaState` own exactly one version profile.
- Add `LuaProfiles.lua51()`, `lua52()`, `lua53()`, `lua54()`, `lua55()`, and `luajit21()` factory methods.
- Add version-aware syntax errors.
- Add version-specific standard library installers.
- Add version-aware bytecode package header checks.
- Add conformance test tasks per version profile.

### Recommended implementation order

```text
1. Lua 5.4 profile
   Keep as the default and strongest compatibility target.

2. Lua 5.3 profile
   Mostly reuse 5.4 runtime, but disable <const>, <close>, and 5.4-specific stdlib behavior.

3. Lua 5.2 profile
   Add/adjust bit32, _ENV behavior, package/debug differences, and coroutine/protected-call differences.

4. Lua 5.1 profile
   Add setfenv/getfenv, loadstring, module, old package behavior, and legacy function environments.

5. LuaJIT-like profile
   Start as Lua 5.1 plus selected LuaJIT ecosystem expectations.

6. Lua 5.5 profile
   Add global declarations, named vararg tables, table.create, readonly for-loop behavior, and other 5.5-specific behavior.
```

### Version-sensitive features to test

```text
_ENV vs setfenv/getfenv
global lookup and assignment
module/load/loadstring behavior
integer vs float behavior
bit32 vs bitwise operators
floor division
varargs and multiple returns
goto and labels
<const> and <close>
global declarations
named vararg tables
metatable edge cases
coroutine yield behavior
pcall/xpcall yield behavior
debug.getinfo/getlocal/getupvalue behavior
stdlib differences
```

### Test tasks

```text
./gradlew testLua51
./gradlew testLua52
./gradlew testLua53
./gradlew testLua54
./gradlew testLua55
./gradlew testLuaJitCompat
```

### Success criteria

- Lua 5.4 profile remains stable and is the default.
- Each compatibility profile has its own documented feature matrix.
- Official or reference-behavior tests are grouped by version.
- Known compatibility gaps are documented.
- Common Lua scripts run with minimal changes in the matching profile.
- Bytecode compiled for one version is not accidentally loaded into an incompatible state.

### Risks

- Lua versions differ in many small semantic details.
- Lua 5.1 environment behavior is meaningfully different from Lua 5.2+ `_ENV`.
- Compatibility flags can accidentally create undocumented KLua-only dialects.
- Exact compatibility can consume a lot of time if not scoped by profile and tests.

### Anti-goals for this milestone

Do not attempt these yet unless the core runtime is already stable:

```text
official PUC .luac binary loading
native Lua C module compatibility
perfect LuaJIT FFI compatibility
mixing multiple source-language versions inside one LuaState
```

---

## M21: v1.0 Release

### Goal

Release a stable, documented runtime.

### Required features

```text
Stable Java API
Stable Kotlin API
Low-level LuaState API
High-level embedding API
Tables
Closures
Metatables
Coroutines
Userdata
Core standard libraries
Bytecode package loading
Instruction budget
Clear errors and stack traces
Source-level debugger core
Breakpoints and stepping
Variable inspection
Benchmark results
Compatibility documentation
```

### Documentation checklist

- Getting started with Java.
- Getting started with Kotlin.
- Low-level API guide.
- High-level API guide.
- Userdata binding guide.
- Sandbox guide.
- Standard library reference.
- Compatibility notes.
- Performance guide.
- Debugging guide.
- Debug Adapter Protocol guide, if `klua-dap` is included.
- Migration notes for LuaJ-style users, if applicable.

### Success criteria

- Java and Kotlin examples are easy to copy.
- Public API is stable enough to support users.
- CI passes tests and benchmarks run.
- Release artifacts are published.

### Risks

- Calling it 1.0 before API stability.
- No clear compatibility policy.

---

## M22: Optional JVM Bytecode Compiler

### Goal

Experiment with compiling hot Lua functions into JVM bytecode.

### When to start

Only start this after:

```text
Interpreter is correct.
Bytecode format is stable.
Benchmarks exist.
Hot paths are known.
API is stable.
```

### Tasks

- Define lower-level IR.
- Add type feedback collection.
- Add guards and deoptimization strategy.
- Generate JVM bytecode for simple functions.
- Start with numeric loops and local variables.
- Fall back to interpreter for complex cases.
- Support source maps and stack traces.
- Benchmark against interpreter.

### Good first compilation target

```lua
local sum = 0
for i = 1, n do
    sum = sum + i
end
return sum
```

### Avoid compiling first

```text
functions using heavy metatables
functions using debug hooks
functions yielding through complex paths
functions with unpredictable globals
functions with highly dynamic call targets
```

### Success criteria

- JVM-compiled simple numeric code beats interpreter.
- Fallback to interpreter remains correct.
- Errors still have useful stack traces.

### Risks

- JVM bytecode generation is complex.
- Dynamic Lua semantics require many guards.
- The interpreter plus inline caches may already be good enough.

---


## Suggested Version Plan

### v0.1: Language Core MVP

```text
lexer/parser
compiler
VM
basic values
locals
branches
loops
functions
simple calls
basic tests
```

### v0.2: Lua Runtime Basics

```text
tables
closures/upvalues
multiple returns
varargs
basic metatables
partial base/table/math/string stdlib
```

### v0.3: Embedding MVP

```text
LuaState API
Lua high-level API
Java examples
Kotlin examples
native function registration
basic userdata
```

### v0.4: Runtime Completeness and Debug Metadata

```text
fuller metatables
coroutines
better error handling
stack traces
pc-to-line mapping
local/upvalue debug metadata
more standard library functions
```

### v0.5: Source-Level Debugger

```text
debug hooks
breakpoints
step into / step over / step out
locals/upvalues/globals inspection
expression evaluation
coroutine-aware debugging
CLI debugger MVP
```

### v0.6: Production Embedding

```text
bytecode packaging
instruction limits
sandbox profiles
module loader
host API permissions
production debug profile
```

### v0.7: Performance Release

```text
JMH suite
table optimization
call optimization
host binding optimization
string interning
inline caches
fast-mode vs debug-mode VM loop
```

### v0.8: Tooling and Compatibility Release

```text
DAP adapter
expanded Lua 5.4 compatibility tests
weak table support
stdlib edge cases
coroutine edge cases
documented compatibility gaps
```

### v0.9: Multi-Version Compatibility Release

```text
LuaVersionProfile architecture
Lua 5.3 profile
Lua 5.2 profile
Lua 5.1 profile
LuaJIT-like compatibility profile
Lua 5.5 profile, if scoped tightly
version-specific stdlib installers
version-specific conformance test tasks
compatibility matrix documentation
```

### v1.0: Stable Release

```text
stable public API
stable bytecode package format, or clearly marked experimental
complete docs
compatibility matrix
performance guide
debugging guide
release artifacts
```

---

## Recommended Work Order for the First Month

A practical first-month target:

```text
Week 1:
  Project setup, lexer, parser for expressions/statements.

Week 2:
  AST, bytecode prototype, compiler for constants/locals/arithmetic/return.

Week 3:
  Minimal VM, stack, call frame, branches, loops.

Week 4:
  Functions, simple calls, initial LuaState API, golden tests.
```

Do not worry about full compatibility in the first month. The first goal is to prove the pipeline:

```text
source -> parser -> compiler -> bytecode -> VM -> result
```

---

## Recommended Definition of Done per Feature

For every feature, require:

```text
Parser test
Compiler bytecode test
VM behavior test
Error test
Debug metadata test when source location matters
Documentation snippet
Benchmark if feature is hot-path-related
```

Example for table field access:

```text
Parser:
  parses t.x and t["x"]

Compiler:
  emits GET_FIELD or GET_TABLE

VM:
  returns correct value

Error:
  nil field access error is clear

Benchmark:
  repeated t.x in loop
```

---

## Key Design Warnings

### 1. Do not expose internals as public API

Internal VM types will change. Public API should be a facade.

### 2. Do not use reflection on every host call

Use reflection at registration time, then cache adapters.

### 3. Do not rely on JVM threads for Lua coroutines

Use VM-managed coroutine state.

### 4. Do not chase official `.luac` compatibility early

Source compatibility matters more than binary compatibility.

### 5. Do not implement JVM bytecode generation before the interpreter is stable

Correctness first, optimization later.

### 6. Do not make Java users call Kotlin-shaped APIs

Expose Java-friendly classes, static factories, and functional interfaces.

### 7. Do not let debug mode slow down normal execution

Keep debug hooks, line checks, and breakpoint lookups disabled or isolated in a debug VM loop during normal execution.

### 8. Do not enable unsafe debug eval in production by default

Expression evaluation is powerful and can mutate game state if it can call arbitrary functions.

---

## Final Roadmap Summary

Build KLua in this order:

```text
1. Correct source parser.
2. Custom bytecode compiler.
3. Simple interpreter.
4. Tables, functions, closures.
5. Metatables.
6. Embedding APIs.
7. Userdata interop.
8. Standard library.
9. Coroutines.
10. Tracebacks and debug metadata.
11. Source-level debugger.
12. CLI/DAP debugging tools.
13. Packaging and sandboxing.
14. Benchmark-driven optimization.
15. Lua 5.4 compatibility hardening.
16. Multi-version profiles.
17. Stable v1.0.
18. Optional JVM bytecode compiler.
```

The guiding principle:

```text
KLua should be easy to embed first, correct second, fast third, and JVM-JIT-clever only after the foundation is strong.
```


---

## References

These are useful compatibility references while planning milestones:

- Lua version history: https://www.lua.org/versions.html
- Lua download/current release page: https://www.lua.org/download.html
- Lua reference manuals: https://www.lua.org/manual/
- Lua test suites: https://www.lua.org/tests/
