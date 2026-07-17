import java.util.jar.JarFile
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar

plugins {
    kotlin("jvm") version "2.4.0" apply false
    id("me.champeau.jmh") version "0.7.2" apply false
}

val kluaGroup = providers.gradleProperty("klua.group").get()
val kluaVersion = providers.gradleProperty("klua.version").get()
val releaseModuleNames = listOf(
    "klua-core",
    "klua-api",
    "klua-kotlin",
    "klua-stdlib",
    "klua-debug",
    "klua-dap",
    "klua-tools",
)

allprojects {
    group = kluaGroup
    version = kluaVersion
}

subprojects {
    plugins.apply("java-library")
    plugins.apply("org.jetbrains.kotlin.jvm")

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
        jvmToolchain(17)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.11.3"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"("org.jetbrains.kotlin:kotlin-test")
    }

    if (name in releaseModuleNames) {
        plugins.apply("maven-publish")

        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
        }

        tasks.withType<Jar>().configureEach {
            manifest.attributes(
                "Automatic-Module-Name" to "$kluaGroup.${project.name.removePrefix("klua-")}",
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version,
            )
        }

        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("mavenJava") {
                    from(components["java"])
                    pom {
                        name.set(project.name)
                        description.set("KLua ${project.name.removePrefix("klua-")} module")
                    }
                }
            }
        }
    }
}

val expectedProjectDependencyScopes = mapOf(
    "klua-core" to emptyMap(),
    "klua-api" to mapOf("klua-core" to "runtime"),
    "klua-kotlin" to mapOf("klua-api" to "compile"),
    "klua-stdlib" to mapOf("klua-api" to "compile", "klua-core" to "runtime"),
    "klua-debug" to mapOf("klua-api" to "compile", "klua-core" to "runtime"),
    "klua-dap" to mapOf("klua-debug" to "runtime"),
    "klua-tools" to mapOf("klua-api" to "runtime", "klua-debug" to "runtime"),
)

val releaseArtifacts = tasks.register("releaseArtifacts") {
    group = "build"
    description = "Builds the local KLua release JARs, source JARs, and Maven POMs without publishing them."
    dependsOn(
        releaseModuleNames.flatMap { moduleName ->
            listOf(
                ":$moduleName:jar",
                ":$moduleName:sourcesJar",
                ":$moduleName:generatePomFileForMavenJavaPublication",
            )
        },
    )
}

val verifyReleaseArtifacts = tasks.register("verifyReleaseArtifacts") {
    group = "verification"
    description = "Verifies KLua release coordinates, manifests, source archives, and project dependency scopes."
    dependsOn(releaseArtifacts)

    doLast {
        val xmlFactory = DocumentBuilderFactory.newInstance()
        for (moduleName in releaseModuleNames) {
            val module = project(":$moduleName")
            val binaryJar = module.layout.buildDirectory.file("libs/$moduleName-$kluaVersion.jar").get().asFile
            val sourcesJar = module.layout.buildDirectory.file("libs/$moduleName-$kluaVersion-sources.jar").get().asFile
            val pomFile = module.layout.buildDirectory.file("publications/mavenJava/pom-default.xml").get().asFile

            check(binaryJar.isFile) { "missing release JAR: $binaryJar" }
            check(sourcesJar.isFile) { "missing release sources JAR: $sourcesJar" }
            check(pomFile.isFile) { "missing generated POM: $pomFile" }

            JarFile(binaryJar).use { jar ->
                val attributes = checkNotNull(jar.manifest).mainAttributes
                check(attributes.getValue("Automatic-Module-Name") == "$kluaGroup.${moduleName.removePrefix("klua-")}") {
                    "unexpected Automatic-Module-Name in $binaryJar"
                }
                check(attributes.getValue("Implementation-Version") == kluaVersion) {
                    "unexpected Implementation-Version in $binaryJar"
                }
            }
            JarFile(sourcesJar).use { jar ->
                check(jar.entries().asSequence().any { entry -> entry.name.endsWith(".kt") }) {
                    "release sources JAR contains no Kotlin sources: $sourcesJar"
                }
            }

            val document = xmlFactory.newDocumentBuilder().parse(pomFile)
            fun elementText(parent: org.w3c.dom.Element, name: String): String? {
                return parent.getElementsByTagName(name).item(0)?.textContent?.trim()
            }
            val root = document.documentElement
            check(elementText(root, "groupId") == kluaGroup) { "unexpected groupId in $pomFile" }
            check(elementText(root, "artifactId") == moduleName) { "unexpected artifactId in $pomFile" }
            check(elementText(root, "version") == kluaVersion) { "unexpected version in $pomFile" }

            val actualScopes = buildMap {
                val dependencies = document.getElementsByTagName("dependency")
                for (index in 0 until dependencies.length) {
                    val dependency = dependencies.item(index) as org.w3c.dom.Element
                    if (elementText(dependency, "groupId") == kluaGroup) {
                        put(
                            checkNotNull(elementText(dependency, "artifactId")),
                            checkNotNull(elementText(dependency, "scope")),
                        )
                    }
                }
            }
            check(actualScopes == expectedProjectDependencyScopes.getValue(moduleName)) {
                "unexpected KLua dependency scopes in $pomFile: $actualScopes"
            }
        }
    }
}

releaseModuleNames.forEach { moduleName ->
    project(":$moduleName").tasks.named("check").configure {
        dependsOn(verifyReleaseArtifacts)
    }
}
