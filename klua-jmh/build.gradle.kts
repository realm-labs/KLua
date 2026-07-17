import org.gradle.api.tasks.JavaExec

plugins {
    id("me.champeau.jmh")
}

dependencies {
    implementation(project(":klua-api"))
    implementation(project(":klua-debug"))
    implementation(project(":klua-stdlib"))
}

tasks.register<JavaExec>("checkPerformanceRegression") {
    group = "verification"
    description = "Compares JMH timing and GC CSVs with the accepted v1 JDK 17 baseline."
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.realmlabs.klua.jmh.PerformanceRegressionChecker")

    doFirst {
        val timingCsv = providers.gradleProperty("klua.performance.timingCsv").orNull
            ?: throw GradleException("set -Pklua.performance.timingCsv=<JMH timing CSV>")
        val gcCsv = providers.gradleProperty("klua.performance.gcCsv").orNull
            ?: throw GradleException("set -Pklua.performance.gcCsv=<JMH -prof gc CSV>")
        args(
            layout.projectDirectory.file("baselines/v1-jdk17.csv").asFile.absolutePath,
            rootProject.file(timingCsv).absolutePath,
            rootProject.file(gcCsv).absolutePath,
        )
    }
}
