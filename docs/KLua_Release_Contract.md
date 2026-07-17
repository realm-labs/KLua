# KLua Release and Compatibility Contract

This document defines the artifact and compatibility boundary being prepared for KLua v1. It does not announce a release or authorize publication. The current build remains `0.1.0-SNAPSHOT`; the v1 compatibility promise begins with the first `1.0.0` release.

## Coordinates and artifacts

The authoritative group and version are `klua.group` and `klua.version` in `gradle.properties`. Release builds produce these local Maven components:

| Artifact | Automatic module name | Contract |
| --- | --- | --- |
| `io.github.realmlabs.klua:klua-api` | `io.github.realmlabs.klua.api` | Stable Java and Kotlin embedding API. `LuaState`, `Lua`, configuration, host functions, userdata, coroutine, debug-thread, result, and exception types belong here. |
| `io.github.realmlabs.klua:klua-kotlin` | `io.github.realmlabs.klua.kotlin` | Stable Kotlin convenience extensions over `klua-api`. |
| `io.github.realmlabs.klua:klua-stdlib` | `io.github.realmlabs.klua.stdlib` | Stable standard-library installation entry points. Internal library implementations are not API. |
| `io.github.realmlabs.klua:klua-debug` | `io.github.realmlabs.klua.debug` | Supported source-debugging model and controller API. |
| `io.github.realmlabs.klua:klua-dap` | `io.github.realmlabs.klua.dap` | Supported DAP protocol, transport, and live-session API. |
| `io.github.realmlabs.klua:klua-tools` | `io.github.realmlabs.klua.tools` | Supported command-line parsing and runner API. The application distribution provides the `klua` launcher. |
| `io.github.realmlabs.klua:klua-core` | `io.github.realmlabs.klua.core` | Runtime implementation dependency. Its compiler, VM, bytecode, value, parser, AST, and core bridge types are not a compatibility surface for embedders. |

Every component produces a binary JAR, a sources JAR, and a generated Maven POM. Binary and source JARs carry the MIT license, while POMs carry the project URL, license, and SCM metadata. `klua-api` keeps `klua-core` at Maven runtime scope so Java consumers do not compile against runtime representations. `klua-examples`, `klua-jmh`, and `klua-tests` are verification modules rather than release components. No `klua-all` component exists, and no remote publication repository is configured.

`klua-tools` additionally produces `klua-<version>.zip` and `klua-<version>.tar` under `klua-tools/build/distributions`. Each archive contains Unix and Windows launchers, runtime dependencies, the MIT license, the README, and the tooling/release guides. `verifyInstallDist` runs the installed launcher through bytecode compile, debugger, and DAP framing smoke scenarios.

Run the local artifact contract with:

```text
./gradlew verifyReleaseArtifacts
```

The task checks all coordinates, filenames, `Automatic-Module-Name` and implementation-version manifest entries, source archives, and inter-module Maven dependency scopes. `releaseArtifacts` builds the same files without performing external publication.

The complete non-publishing release-candidate matrix is:

```text
./gradlew releaseCandidateCheck
```

It runs every test task, all supported-module ABI checks, Maven/distribution verification and executable smoke tests, and the JMH jar build.

## API compatibility

The checked-in `.api` files under each supported public module are the v1 ABI review baseline. `checkKotlinAbi`, and therefore each module's normal `check` lifecycle, fails when compiled public signatures diverge. `updateKotlinAbi` may be run only after an intentional API review; updating a dump is not by itself evidence that a change is compatible.

`klua-api` additionally enforces the Java boundary at compiled-bytecode level:

- externally callable members must not mention `klua-core`, Kotlin function types, reflection types, sequences, or coroutine types;
- implementation constructors and helpers are private or JVM-synthetic;
- a Java integration source compiles using `klua-api` alone on its compile classpath and exercises both low- and high-level entry points.

The ABI files protect binary signatures. Tests and the Lua conformance matrix protect behavior. Both are required: a signature-compatible change may still be rejected when it changes documented semantics.

## Versioning policy

Starting at `1.0.0`, KLua uses semantic versioning for the supported artifacts:

- patch releases preserve source and binary compatibility and contain fixes or compatible implementation changes;
- minor releases preserve binary compatibility and may add APIs or capabilities;
- breaking public API changes require a new major version and migration notes;
- security or correctness fixes may intentionally change erroneous behavior, but the release notes must identify the change;
- `klua-core` internals and the KLua bytecode package format are versioned separately from the embedding ABI. Serialized bytecode remains governed by its own format marker and validator.

JVM 17 is the minimum runtime and compilation target for v1. Java signatures use Java functional interfaces and explicit result types. Kotlin-only conveniences remain in `klua-kotlin`, except for JVM-synthetic continuation plumbing used between the API and standard-library modules; that plumbing is not Java-callable.

External publication, signing, repository credentials, release tagging, and a final `1.0.0` version change require an explicit release action and are outside this package.
