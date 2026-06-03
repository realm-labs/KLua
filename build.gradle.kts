plugins {
    kotlin("jvm") version "2.3.21" apply false
    id("me.champeau.jmh") version "0.7.2" apply false
}

allprojects {
    group = "io.github.realmlabs.klua"
    version = "0.1.0-SNAPSHOT"
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
}
