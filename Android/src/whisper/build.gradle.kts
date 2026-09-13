import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
}

android {
    namespace = "com.google.ai.edge.gallery.whisper"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 30
        // arm64-v8a only -- see the note in smollm/build.gradle.kts.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}
