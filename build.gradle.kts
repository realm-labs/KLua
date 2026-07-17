import java.util.jar.JarFile
import java.util.zip.ZipFile
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
val projectUrl = "https://github.com/realm-labs/KLua"
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
            from(rootProject.file("LICENSE")) {
                into("META-INF")
            }
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
                        url.set(projectUrl)
                        inceptionYear.set("2026")
                        licenses {
                            license {
                                name.set("MIT License")
                                url.set("https://opensource.org/license/mit")
                                distribution.set("repo")
                            }
                        }
                        scm {
                            connection.set("scm:git:https://github.com/realm-labs/KLua.git")
                            developerConnection.set("scm:git:ssh://git@github.com/realm-labs/KLua.git")
                            url.set(projectUrl)
                        }
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
    "klua-tools" to mapOf("klua-api" to "runtime", "klua-debug" to "runtime", "klua-dap" to "runtime"),
)

val releaseArtifacts = tasks.register("releaseArtifacts") {
    group = "build"
    description = "Builds local KLua Maven artifacts and executable distributions without publishing them."
    dependsOn(
        releaseModuleNames.flatMap { moduleName ->
            listOf(
                ":$moduleName:jar",
                ":$moduleName:sourcesJar",
                ":$moduleName:generatePomFileForMavenJavaPublication",
            )
        },
        ":klua-tools:distZip",
        ":klua-tools:distTar",
    )
}

val verifyReleaseArtifacts = tasks.register("verifyReleaseArtifacts") {
    group = "verification"
    description = "Verifies KLua artifact metadata, licenses, dependency scopes, distributions, and launcher smokes."
    dependsOn(releaseArtifacts, ":klua-tools:verifyInstallDist")

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
                check(jar.getEntry("META-INF/LICENSE") != null) { "release JAR has no MIT license: $binaryJar" }
            }
            JarFile(sourcesJar).use { jar ->
                check(jar.entries().asSequence().any { entry -> entry.name.endsWith(".kt") }) {
                    "release sources JAR contains no Kotlin sources: $sourcesJar"
                }
                check(jar.getEntry("META-INF/LICENSE") != null) {
                    "release sources JAR has no MIT license: $sourcesJar"
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
            check(elementText(root, "url") == projectUrl) { "unexpected project URL in $pomFile" }
            val licenses = document.getElementsByTagName("license")
            check(licenses.length == 1) { "expected one license in $pomFile" }
            val license = licenses.item(0) as org.w3c.dom.Element
            check(elementText(license, "name") == "MIT License") { "unexpected license name in $pomFile" }
            check(elementText(license, "url") == "https://opensource.org/license/mit") {
                "unexpected license URL in $pomFile"
            }
            val scm = document.getElementsByTagName("scm").item(0) as? org.w3c.dom.Element
            check(scm != null && elementText(scm, "url") == projectUrl) { "missing SCM metadata in $pomFile" }

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

        val distributionsDirectory = project(":klua-tools").layout.buildDirectory.dir("distributions").get().asFile
        val zipFile = distributionsDirectory.resolve("klua-$kluaVersion.zip")
        val tarFile = distributionsDirectory.resolve("klua-$kluaVersion.tar")
        check(zipFile.isFile && zipFile.length() > 0L) { "missing KLua ZIP distribution: $zipFile" }
        check(tarFile.isFile && tarFile.length() > 0L) { "missing KLua TAR distribution: $tarFile" }
        ZipFile(zipFile).use { zip ->
            val root = "klua-$kluaVersion"
            listOf(
                "$root/bin/klua",
                "$root/bin/klua.bat",
                "$root/LICENSE",
                "$root/README.md",
                "$root/docs/KLua_Debug_Tooling.md",
            ).forEach { entry ->
                check(zip.getEntry(entry) != null) { "distribution is missing $entry: $zipFile" }
            }
        }
    }
}

tasks.register("releaseCandidateCheck") {
    group = "verification"
    description = "Runs the complete local, non-publishing KLua release-candidate verification matrix."
    dependsOn(
        subprojects.map { subproject -> "${subproject.path}:test" },
        releaseModuleNames
            .filterNot { moduleName -> moduleName == "klua-core" }
            .map { moduleName -> ":$moduleName:checkKotlinAbi" },
        verifyReleaseArtifacts,
        ":klua-jmh:jmhJar",
    )
}

releaseModuleNames.forEach { moduleName ->
    project(":$moduleName").tasks.named("check").configure {
        dependsOn(verifyReleaseArtifacts)
    }
}
