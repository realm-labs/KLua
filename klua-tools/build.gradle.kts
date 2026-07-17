plugins {
    application
}

dependencies {
    implementation(project(":klua-api"))
    implementation(project(":klua-dap"))
    implementation(project(":klua-debug"))
}

application {
    mainClass.set("io.github.realmlabs.klua.tools.DebugCliMain")
    applicationName = "klua"
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}

distributions {
    main {
        distributionBaseName.set("klua")
        contents {
            from(rootProject.file("LICENSE"))
            from(rootProject.file("README.md"))
            into("docs") {
                from(rootProject.file("docs/KLua_Debug_Tooling.md"))
                from(rootProject.file("docs/KLua_Release_Contract.md"))
            }
        }
    }
}

val verifyInstallDist = tasks.register("verifyInstallDist") {
    group = "verification"
    description = "Runs the installed KLua launcher through bytecode compile, debug, and DAP smoke scenarios."
    dependsOn("installDist")

    doLast {
        val installDirectory = layout.buildDirectory.dir("install/klua").get().asFile
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val launcher = installDirectory.resolve(if (windows) "bin/klua.bat" else "bin/klua")
        check(launcher.isFile) { "missing installed KLua launcher: $launcher" }

        val smokeDirectory = temporaryDir.resolve("smoke").also { it.mkdirs() }
        val source = smokeDirectory.resolve("answer.lua").also { it.writeText("return 40 + 2") }
        val bytecode = smokeDirectory.resolve("answer.kluac")

        fun command(vararg arguments: String): List<String> {
            return if (windows) {
                listOf("cmd.exe", "/d", "/c", launcher.absolutePath) + arguments
            } else {
                listOf(launcher.absolutePath) + arguments
            }
        }

        val compile = ProcessBuilder(command("--compile", source.absolutePath, bytecode.absolutePath))
            .redirectErrorStream(true)
            .start()
        val compileOutput = compile.inputStream.bufferedReader().readText()
        check(compile.waitFor() == 0) { "installed compiler failed: $compileOutput" }
        check(bytecode.isFile && bytecode.length() > 0L) { "installed compiler produced no bytecode package" }

        val debug = ProcessBuilder(command("--debug", bytecode.absolutePath))
            .redirectErrorStream(true)
            .start()
        debug.outputStream.bufferedWriter().use { input ->
            input.appendLine("run")
            input.appendLine("quit")
        }
        val debugOutput = debug.inputStream.bufferedReader().readText()
        check(debug.waitFor() == 0) { "installed debugger failed: $debugOutput" }
        check("ok: completed 42" in debugOutput) { "installed debugger returned unexpected output: $debugOutput" }

        fun dapFrame(json: String): ByteArray {
            val body = json.toByteArray(Charsets.UTF_8)
            val header = "Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
            return header + body
        }

        val dap = ProcessBuilder(command("--dap")).start()
        dap.outputStream.use { input ->
            input.write(dapFrame("""{"seq":1,"type":"request","command":"initialize","arguments":{"adapterID":"klua"}}"""))
            input.write(dapFrame("""{"seq":2,"type":"request","command":"disconnect","arguments":{}}"""))
        }
        val dapOutput = dap.inputStream.readAllBytes().toString(Charsets.UTF_8)
        val dapError = dap.errorStream.bufferedReader().readText()
        check(dap.waitFor() == 0) { "installed DAP host failed: $dapError" }
        check("\"command\":\"initialize\"" in dapOutput && "\"command\":\"disconnect\"" in dapOutput) {
            "installed DAP host returned unexpected output: $dapOutput $dapError"
        }
    }
}

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()
}
