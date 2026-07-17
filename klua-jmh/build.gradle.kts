plugins {
    id("me.champeau.jmh")
}

dependencies {
    implementation(project(":klua-api"))
    implementation(project(":klua-debug"))
    implementation(project(":klua-stdlib"))
}
