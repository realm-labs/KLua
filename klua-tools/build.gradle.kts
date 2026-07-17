import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

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
    description = "Runs installed compile, debug, and DAP smokes on JDK 17 and JDK 21."
    dependsOn("installDist")

    val javaToolchains = project.extensions.getByType<JavaToolchainService>()
    val runtimeLaunchers = listOf(17, 21).associateWith { version ->
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(version))
        }
    }

    doLast {
        val installDirectory = layout.buildDirectory.dir("install/klua").get().asFile
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val launcher = installDirectory.resolve(if (windows) "bin/klua.bat" else "bin/klua")
        check(launcher.isFile) { "missing installed KLua launcher: $launcher" }

        fun command(vararg arguments: String): List<String> {
            return if (windows) {
                listOf("cmd.exe", "/d", "/c", launcher.absolutePath) + arguments
            } else {
                listOf(launcher.absolutePath) + arguments
            }
        }

        fun dapFrame(json: String): ByteArray {
            val body = json.toByteArray(Charsets.UTF_8)
            val header = "Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
            return header + body
        }

        fun process(javaHome: File, vararg arguments: String, mergeError: Boolean = false): Process {
            return ProcessBuilder(command(*arguments))
                .redirectErrorStream(mergeError)
                .also { builder -> builder.environment()["JAVA_HOME"] = javaHome.absolutePath }
                .start()
        }

        for ((version, launcherProvider) in runtimeLaunchers) {
            val javaHome = launcherProvider.get().metadata.installationPath.asFile
            val smokeDirectory = temporaryDir.resolve("jdk$version").also { it.mkdirs() }
            val source = smokeDirectory.resolve("answer.lua").also { it.writeText("return 40 + 2") }
            val bytecode = smokeDirectory.resolve("answer.kluac")

            val compile = process(
                javaHome,
                "--compile",
                source.absolutePath,
                bytecode.absolutePath,
                mergeError = true,
            )
            val compileOutput = compile.inputStream.bufferedReader().readText()
            check(compile.waitFor() == 0) { "JDK $version installed compiler failed: $compileOutput" }
            check(bytecode.isFile && bytecode.length() > 0L) {
                "JDK $version installed compiler produced no bytecode package"
            }

            val debug = process(javaHome, "--debug", bytecode.absolutePath, mergeError = true)
            debug.outputStream.bufferedWriter().use { input ->
                input.appendLine("run")
                input.appendLine("quit")
            }
            val debugOutput = debug.inputStream.bufferedReader().readText()
            check(debug.waitFor() == 0) { "JDK $version installed debugger failed: $debugOutput" }
            check("ok: completed 42" in debugOutput) {
                "JDK $version installed debugger returned unexpected output: $debugOutput"
            }

            val dap = process(javaHome, "--dap")
            dap.outputStream.use { input ->
                input.write(
                    dapFrame(
                        """{"seq":1,"type":"request","command":"initialize","arguments":{"adapterID":"klua"}}""",
                    ),
                )
                input.write(dapFrame("""{"seq":2,"type":"request","command":"disconnect","arguments":{}}"""))
            }
            val dapOutput = dap.inputStream.readAllBytes().toString(Charsets.UTF_8)
            val dapError = dap.errorStream.bufferedReader().readText()
            check(dap.waitFor() == 0) { "JDK $version installed DAP host failed: $dapError" }
            check("\"command\":\"initialize\"" in dapOutput && "\"command\":\"disconnect\"" in dapOutput) {
                "JDK $version installed DAP host returned unexpected output: $dapOutput $dapError"
            }
            logger.lifecycle("Verified installed KLua compile/debug/DAP launcher on JDK $version at $javaHome")
        }
    }
}

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()
}
