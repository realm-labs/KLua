plugins {
    application
    kotlin("jvm") version "2.4.0"
}

val kluaVersion = providers.gradleProperty("kluaVersion").get()

dependencies {
    implementation("io.github.realmlabs.klua:klua-api:$kluaVersion")
    implementation("io.github.realmlabs.klua:klua-kotlin:$kluaVersion")
    implementation("io.github.realmlabs.klua:klua-stdlib:$kluaVersion")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("io.github.realmlabs.klua.consumer.JavaEmbeddingSmoke")
}

fun kluaModules(configurationName: String): Set<String> {
    return configurations[configurationName].resolvedConfiguration.resolvedArtifacts
        .filter { artifact -> artifact.moduleVersion.id.group == "io.github.realmlabs.klua" }
        .map { artifact -> artifact.name }
        .toSet()
}

val verifyPublishedScopes = tasks.register("verifyPublishedScopes") {
    group = "verification"
    description = "Checks the consumer compile/runtime classpaths resolved from staged POM scopes."

    doLast {
        val compileModules = kluaModules("compileClasspath")
        val runtimeModules = kluaModules("runtimeClasspath")
        check(compileModules == setOf("klua-api", "klua-kotlin", "klua-stdlib")) {
            "unexpected staged KLua compile classpath: $compileModules"
        }
        check(runtimeModules == setOf("klua-api", "klua-core", "klua-kotlin", "klua-stdlib")) {
            "unexpected staged KLua runtime classpath: $runtimeModules"
        }
    }
}

val runJavaSmoke = tasks.register<JavaExec>("runJavaSmoke") {
    group = "verification"
    dependsOn("classes", verifyPublishedScopes)
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.realmlabs.klua.consumer.JavaEmbeddingSmoke")
}

val runKotlinSmoke = tasks.register<JavaExec>("runKotlinSmoke") {
    group = "verification"
    dependsOn("classes", verifyPublishedScopes)
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.realmlabs.klua.consumer.KotlinEmbeddingSmoke")
}

tasks.register("verifyConsumer") {
    group = "verification"
    description = "Compiles and executes Java/Kotlin consumers using only staged KLua artifacts."
    dependsOn(runJavaSmoke, runKotlinSmoke)
}
