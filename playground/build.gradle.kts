plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    // The Compose Multiplatform Gradle plugin already puts hot-reload on the classpath; a version here fails configuration.
    id("org.jetbrains.compose.hot-reload")
}

kotlin {
    jvmToolchain(17)

    dependencies {
        implementation(projects.layout)
        implementation(compose.desktop.currentOs)
    }
}

compose.desktop {
    application {
        mainClass = "swim.playground.MainKt"
    }
}
