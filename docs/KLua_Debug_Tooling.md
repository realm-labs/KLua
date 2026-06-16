# KLua Debug Tooling

KLua debug tooling is split across three modules:

- `klua-debug` owns debugger state, breakpoints, stepping decisions, frame views, variable views, and userdata display adapters.
- `klua-dap` adapts those debug primitives to Debug Adapter Protocol-shaped requests and `Content-Length` framed JSON messages.
- `klua-tools` contains the command-line debugger runner core, the `klua --debug <script.lua> [args...]` command-loop wrapper, and the `klua --compile <script.lua> <output.kluac>` bytecode package compiler.

The current implementation is a library-level foundation. It does not yet package a standalone executable DAP adapter process, and live VM stop contexts are still limited. `bt` and `locals` in the CLI runner therefore report that no Lua frame is suspended until VM-level stop-state integration is added.

## Bytecode Compiler

The tooling wrapper can compile a Lua source script into KLua's internal bytecode package format:

```text
klua --compile <script.lua> <output.kluac>
```

The output is a KLua-owned `.kluac` package, not an official PUC Lua `.luac` file. It carries the fixed KLua/Lua 5.5 format markers used by the runtime validator and can be loaded through the public bytecode APIs.

Current behavior:

- the input script is parsed and compiled through the public `Lua` facade;
- syntax errors are reported without writing a package;
- successful compilation writes a bytecode package that can be passed to `Lua.loadBytecode`, `LuaState.loadBytecode`, or the CLI debugger runner when the program path ends in `.kluac`.

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
- `run` loads and executes the configured script through the public `Lua` API, passing any script arguments. Paths ending in `.kluac` are read as KLua bytecode packages; other paths are read as Lua source.
- `continue`, `next`, `step`, and `out` update `DebugController` state.
- `print <expr>` evaluates a top-level expression by loading `return <expr>` through the public `Lua` API.
- `bt` and `locals` are command-compatible placeholders until suspended-frame integration is available.

## DAP Integration

`klua-dap` currently provides:

- typed request and response models for `initialize`, `launch`, `attach`, `setBreakpoints`, `configurationDone`, `continue`, `pause`, `next`, `stepIn`, `stepOut`, `threads`, `stackTrace`, `scopes`, `variables`, and `evaluate`;
- a dependency-free JSON value parser/stringifier;
- generic DAP request, response, and event envelopes;
- a `Content-Length` framed JSON stream and connection layer;
- a `DapWireSession` bridge from JSON request envelopes to typed session responses.

The VS Code launch example in `docs/examples/vscode/launch.json` describes the intended adapter-facing shape. The `debugServer` field assumes a future adapter host that exposes `DapMessageConnection` over a local socket.

## VS Code Example

Use the example as a starting point once a DAP host executable or server is available:

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
      "stopOnEntry": true,
      "debugServer": 8172
    }
  ]
}
```
