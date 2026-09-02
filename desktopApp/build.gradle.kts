import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(17)

    dependencies {
        implementation(projects.shared)
        implementation(compose.desktop.currentOs)
    }
}

compose.desktop {
    application {
        mainClass = "swim.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "Swim"
            packageVersion = "1.0.0"
            macOS {
                bundleID = "io.github.seanodonnelludisc.swim"
                appCategory = "public.app-category.developer-tools"
            }
        }
    }
}
