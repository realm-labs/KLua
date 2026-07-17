dependencies {
    implementation(project(":klua-api"))
    implementation(project(":klua-debug"))
}

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()
}
