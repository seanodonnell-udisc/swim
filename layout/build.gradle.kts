import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
}

kotlin {
    jvmToolchain(17)

    androidLibrary {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        namespace = "io.github.seanodonnelludisc.swim.layout"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    jvm()
    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    macosX64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
