// The clock app, built as a static library (libapp.a) whose only C export is
// ec_app_main(); backends/Makefile links it with a backend into one executable.
// Identical in shape to embedded-compose's apps/demo and apps/matrix: this is
// an out-of-tree app, built with APP_DIR=<this dir> ./ec build <board>.
plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    linuxArm64 {
        binaries.staticLib {
            baseName = "app"
        }
    }
    sourceSets {
        val linuxArm64Main by getting {
            dependencies {
                implementation(project(":core:ui"))
            }
        }
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
    }
}
