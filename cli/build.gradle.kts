plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    macosArm64 {
        binaries.executable { entryPoint = "swim.cli.main" }
    }
    macosX64 {
        binaries.executable { entryPoint = "swim.cli.main" }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.core)
            implementation(libs.clikt)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
