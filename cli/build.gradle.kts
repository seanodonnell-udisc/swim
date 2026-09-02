plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    macosArm64 {
        binaries.executable {
            baseName = "swim"
            entryPoint = "swim.cli.main"
        }
    }
    macosX64 {
        binaries.executable {
            baseName = "swim"
            entryPoint = "swim.cli.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.core)
            implementation(libs.clikt)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
