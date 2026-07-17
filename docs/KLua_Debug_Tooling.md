# KLua Debug Tooling

KLua debug tooling is split across three modules:

- `klua-debug` owns debugger state, breakpoints, stepping decisions, frame views, variable views, and userdata display adapters.
- `klua-dap` adapts those debug primitives to Debug Adapter Protocol-shaped requests and `Content-Length` framed JSON messages.
- `klua-tools` contains the command-line debugger runner core, the `klua --debug <script.lua> [args...]` command-loop wrapper, and the `klua --compile <script.lua> <output.kluac>` bytecode package compiler.

The CLI and transport-independent DAP session consume live VM stop contexts. The `klua` application distribution now includes a standalone stdio adapter that connects DAP framing to a launch-owned `LiveDapSession`.

For runtime creation, callbacks, userdata, and coroutine entry points, start with [the embedding guide](KLua_Embedding_Guide.md). Disable debugger attachment for untrusted execution as described in [the sandbox guide](KLua_Sandbox_and_Standard_Library.md).

## Bytecode Compiler

The tooling wrapper can compile a Lua source script into KLua's internal bytecode package format:

```text
klua --compile <script.lua> <output.kluac>
```

The output is a KLua-owned `.kluac` package, not an official PUC Lua `.luac` file. It carries the fixed KLua/Lua 5.5 format markers used by the runtime validator and can be loaded through the public bytecode APIs.

Current behavior:

- the input script is parsed and compiled through the public `Lua` facade;
- syntax errors are reported without writing a package;
- successful compilation writes a bytecode package that can be passed to `Lua.loadBytecode`, `LuaState.loadBytecode`, `Lua.loadBytecodeResource`, `LuaState.loadBytecodeResource`, or the CLI debugger runner when the program path ends in `.kluac`.

## CLI Debugger

The CLI wrapper accepts:

```text
klua --debug <script.lua> [args...]
```

Supported commands:

```text
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

Current behavior:

- `break <file>:<line>` registers a source-line breakpoint in the `BreakpointManager`.
- `run` loads the configured source or `.kluac` package as a coroutine-capable top-level chunk, passes the script arguments, and runs until completion, yield, runtime error, or a live breakpoint stop.
- `continue`, `next`, `step`, and `out` resume the suspended VM through `LiveDebugSession`; control commands issued before `run` or when no debugger stop exists report an error.
- `bt` and `locals` render the live suspended Lua frames and the selected top frame's local variables.
- `print <expr>` evaluates against the stopped frame's scalar locals, upvalues, and globals in a fresh read-only snapshot runtime. With no stopped frame it retains the top-level expression behavior.
- A `LuaConfig` with `debugEnabled = false` rejects debugger attachment, including through the CLI runner.

## DAP Integration

Start the standalone adapter with:

```text
klua --dap
```

The process reserves stdout for `Content-Length` framed DAP messages, reads requests from stdin, and reports fatal host errors on stderr. A client sends `initialize`, then a `launch` request containing `program`, optional `cwd`, `args`, and `stopOnEntry`; after breakpoints are configured, `configurationDone` starts the registered Lua coroutine. Relative program paths are resolved against `cwd`, but the JVM process working directory is not changed. Source and KLua `.kluac` programs are supported. The standalone process deliberately rejects `attach`; embedding hosts can still construct `LiveDapSession` for already-owned runtimes.

The adapter exits cleanly on `disconnect` or input EOF. An editor extension can launch it directly as a stdio debug adapter; KLua does not bundle an editor-specific extension.

The `klua-dap` library provides:

`klua-dap` currently provides:

- typed request and response models for `initialize`, `launch`, `attach`, `setBreakpoints`, `configurationDone`, `continue`, `pause`, `next`, `stepIn`, `stepOut`, `threads`, `stackTrace`, `scopes`, `variables`, and `evaluate`;
- a dependency-free JSON value parser/stringifier;
- generic DAP request, response, and event envelopes;
- a `Content-Length` framed JSON stream and connection layer;
- a `DapWireSession` bridge from JSON request envelopes to typed session responses and debugger events;
- a `LiveDapSession` that shares breakpoint definitions across registered coroutines while retaining independent pause/step state for each DAP thread;
- live `continue`, `next`, `stepIn`, and `stepOut` execution, plus VM-backed `threads`, `stackTrace`, `scopes`, `variables`, and read-only frame evaluation;
- `stopped`, thread lifecycle, and final `terminated` event bodies that can be drained by an adapter host. Initial execution is started by the host with `LiveDapSession.start`; subsequent DAP control requests resume the selected thread.

The VS Code launch example in `docs/examples/vscode/launch.json` describes the adapter-facing launch shape. A VS Code extension or local adapter contribution must map type `klua` to the installed `klua --dap` command.

## VS Code Example

Use the example from an extension or adapter contribution that launches `klua --dap`:

```json
{
  "version": "0.2.0",
  "configurations": [
    {
      "name": "KLua: Launch Script",
      "type": "klua",
      "request": "launch",
      "program": "${workspaceFolder}/scripts/main.lua",
      "cwd": "${workspaceFolder}",
      "args": [],
      "stopOnEntry": true
    }
  ]
}
```
