/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.FileInputStream
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  // Note: set apply to true to enable google-services (requires google-services.json).
  alias(libs.plugins.google.services) apply false
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.protobuf)
  alias(libs.plugins.hilt.application)
  alias(libs.plugins.oss.licenses)
  alias(libs.plugins.ksp)
}

android {
  namespace = "com.google.ai.edge.gallery"
  compileSdk { this.version = release(37) { minorApiLevel = 0 } }

  defaultConfig {
    applicationId = "com.google.aiedge.gallery"
    minSdk = 31
    targetSdk = 37
    versionCode = 43
    versionName = "1.0.19"

    // Needed for HuggingFace auth workflows.
    // Use the scheme of the "Redirect URLs" in HuggingFace app.
    manifestPlaceholders["appAuthRedirectScheme"] =
        "REPLACE_WITH_YOUR_REDIRECT_SCHEME_IN_HUGGINGFACE_APP"
    manifestPlaceholders["applicationName"] = "com.google.ai.edge.gallery.GalleryApplication"
    manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"

    buildConfigField("String", "FEEDBACK_API_KEY", "\"\"")

    // Expected SHA-256 digest of the release signing certificate, checked by
    // SignatureVerifier at startup to detect repackaging/re-signing. Empty by
    // default: only the "release" build type below overrides this, and only
    // when keystore.properties is present (see the signing block below). A
    // fresh clone or CI build has no keystore.properties, falls back to the
    // debug signing config, and must not carry this expectation -- otherwise
    // every such build would log a mismatch that looks like a tamper alarm
    // but is not one.
    buildConfigField("String", "TRUSTED_SIGNING_CERT_SHA256", "\"\"")

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // arm64-v8a only. The native modules set this too, but that governs only
    // what they compile; prebuilt AAR dependencies (MediaPipe, LiteRT, androidx)
    // ship every ABI and are packaged regardless. Without this the APK carries
    // ~108 MB of armeabi-v7a/x86/x86_64 code that no target device executes --
    // and the LiteRT/QNN accelerator libraries in jniLibs are arm64-v8a only, so
    // those variants could not reach an NPU even on a device that ran them.
    ndk {
      abiFilters += listOf("arm64-v8a")
    }
  }

  // Sign release builds with the project keystore when keystore.properties is present, so a build
  // installs over an existing one. Without it, fall back to the debug key (CI, fresh clones).
  // keystore.properties is gitignored; see DEVELOPMENT.md.
  val ksPropsFile =
      rootProject.file("keystore.properties").takeIf { it.exists() }
          ?: file("keystore.properties").takeIf { it.exists() }
  val ksProps =
      ksPropsFile?.let { f -> Properties().apply { load(FileInputStream(f)) } }

  signingConfigs {
    create("release") {
      if (ksProps != null) {
        storeFile = file(ksProps.getProperty("storeFile"))
        storePassword = ksProps.getProperty("storePassword")
        keyAlias = ksProps.getProperty("keyAlias")
        keyPassword = ksProps.getProperty("keyPassword")
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig =
          if (ksProps != null) signingConfigs.getByName("release")
          else signingConfigs.getByName("debug")
      // Only set the expected cert digest when this build is actually signed
      // with our release keystore. When keystore.properties is absent (fresh
      // clone, CI) this build type falls back to the debug signing config
      // above, so the digest must stay empty -- see the defaultConfig field.
      if (ksProps != null) {
        buildConfigField(
            "String",
            "TRUSTED_SIGNING_CERT_SHA256",
            "\"1aeb98ca8785dd7b997a6a5ae887fff1feb9e75c268ddcae6b9328d8016b9f87\"",
        )
      }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isReturnDefaultValues = true } }
  // API server (ported from api-server branch): resource-merge pickFirsts.
  // io.netty.versions.properties was Netty-only and is removed now that the
  // embedded server uses the CIO engine. INDEX.LIST and *.kotlin_module are
  // not Netty-specific (other Kotlin/JVM dependencies can emit them too) and
  // are left in place.
  packaging {
    // Compresses .so in the APK (~2.6:1); Android extracts them into nativeLibraryDir at install.
    jniLibs.useLegacyPackaging = true
    resources {
      pickFirsts += "META-INF/INDEX.LIST"
      pickFirsts += "META-INF/*.kotlin_module"
    }
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_11)
    freeCompilerArgs.addAll("-Xskip-metadata-version-check")
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.compose.navigation)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlin.reflect)
  implementation(libs.material.icon.extended)
  implementation(libs.androidx.work.runtime)
  implementation(libs.androidx.datastore)
  implementation(libs.com.google.code.gson)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.webkit)
  implementation(libs.litertlm)
  implementation(libs.commonmark)
  implementation(libs.richtext)
  implementation(libs.tflite)
  implementation(libs.tflite.gpu)
  implementation(libs.tflite.support)
  implementation(libs.camerax.core)
  implementation(libs.camerax.camera2)
  implementation(libs.camerax.lifecycle)
  implementation(libs.camerax.view)
  implementation(libs.openid.appauth)
  implementation(libs.androidx.splashscreen)
  implementation(libs.protobuf.javalite)
  implementation(libs.protobuf.kotlin.lite)
  implementation(libs.hilt.android)
  implementation(libs.hilt.navigation.compose)
  implementation(libs.play.services.oss.licenses)
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.messaging)
  implementation(libs.androidx.exifinterface)
  implementation(libs.androidx.documentfile)
  implementation(libs.moshi.kotlin)
  ksp(libs.hilt.android.compiler)
  ksp("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.0")
  annotationProcessor("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.0")

  // Box: Biometric authentication (StrongBox)
  implementation(libs.androidx.biometric)
  // Box: FragmentActivity is referenced directly by MainActivity (BiometricPrompt requires it);
  // declared explicitly rather than relying on it arriving transitively via androidx.biometric.
  implementation(libs.androidx.fragment.ktx)

  // Box: llama.cpp native inference module for GGUF models
  implementation(project(":smollm"))

  // Box: stable-diffusion.cpp native inference for image generation
  implementation(project(":stablediffusion"))
  implementation(project(":whisper"))

  // Box: Material 3 adaptive navigation
  implementation(libs.androidx.material3.adaptive.navigation.suite)
  implementation(libs.androidx.material3.window.size)
  testImplementation(libs.junit)
  testImplementation(libs.ktor.server.test.host)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.hilt.android.testing)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
  ksp(libs.moshi.kotlin.codegen)
  implementation(libs.mlkit.genai.prompt)
  implementation(libs.mcp.kotlin.sdk)
  implementation(libs.ktor.client.android)
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.cio)
  implementation(libs.ktor.server.content.negotiation)
  implementation(libs.ktor.serialization.kotlinx.json)
  implementation(libs.ktor.server.cors)
  implementation(libs.ktor.server.status.pages)
  implementation(libs.tink.android)
  implementation(libs.tasks.vision)
  implementation(libs.mlkit.text.recognition)
}

configurations.all {
  resolutionStrategy.force("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.0")
}

protobuf {
  protoc { artifact = "com.google.protobuf:protoc:4.26.1" }
  generateProtoTasks {
    all().forEach { task ->
      task.builtins {
        create("java") { option("lite") }
        create("kotlin") { option("lite") }
      }
    }
  }
}

// OSS Licenses plugin uses groovy.util.XmlSlurper which was moved to groovy-xml in Groovy 4.x
// (Gradle 9+). The plugin (0.10.6) hasn't been updated for this. Disable the broken task;
// the OssLicensesMenuActivity still compiles — it just won't have license data at runtime.
tasks.configureEach {
  if (name.endsWith("OssLicensesTask")) enabled = false
}
