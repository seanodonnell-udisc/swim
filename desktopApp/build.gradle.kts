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
        implementation(libs.ktor.client.core)
        implementation(libs.ktor.client.okhttp)
        implementation(libs.kotlinx.coroutines.core)
    }
}

// Renders the shell offscreen to PNGs. See swim.desktop.Shot.
tasks.register<JavaExec>("shot") {
    mainClass.set("swim.desktop.ShotKt")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOfNotNull(
        project.findProperty("swim.shot.out") as String? ?: "build/shots",
        // -Pswim.shot.only=connect writes only the shots whose name carries that word.
        project.findProperty("swim.shot.only") as String?,
    )
}

// `run` and `shot` both take -Pswim.dev.autoload and -Pswim.insecureStorage.
// -Pswim.demoPrs sets the SWIM_DEMO_PRS environment variable, which turns demo mode on.
tasks.withType<JavaExec>().configureEach {
    listOf("swim.dev.autoload", "swim.insecureStorage", "swim.shot.positions").forEach { key ->
        (project.findProperty(key) as String?)?.let { systemProperty(key, it) }
    }
    val demoPrs = project.findProperty("swim.demoPrs") as String?
        ?: System.getenv("SWIM_DEMO_PRS")
    demoPrs?.let { environment("SWIM_DEMO_PRS", it) }
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
