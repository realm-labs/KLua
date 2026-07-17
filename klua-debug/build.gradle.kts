dependencies {
    api(project(":klua-api"))
    implementation(project(":klua-core"))
}

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()
}
