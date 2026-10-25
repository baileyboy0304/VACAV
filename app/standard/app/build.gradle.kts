import java.io.FileInputStream
import java.util.Properties
import kotlin.toString

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

tasks.register("printVersionName") {
    group = "custom"
    description = "Output version name for use in env vars"
    doLast {
        println(android.defaultConfig.versionName)
    }
}

android {

    namespace = "com.msp1974.vacompanion"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mph1970.vacavision"
        minSdk = 25
        targetSdk = 36
        versionName = "0.2.3-upstream-prototype.10"
        versionCode = 16
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "release.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            ndk {
                abiFilters.add("arm64-v8a")
                abiFilters.add("armeabi-v7a")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Local builds without the release keystore are signed with the debug key so they
            // can be sideloaded (and installed over a debug build) directly.
            signingConfig = if (System.getenv("KEYSTORE_FILE") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            ndk {
                abiFilters.add("arm64-v8a")
                abiFilters.add("armeabi-v7a")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // Backports java.time and java.nio.file to API 25 (Fire OS / Android 7.1)
        isCoreLibraryDesugaringEnabled = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val name = "VACA_Vision-standard-${variant.outputs.first().versionName.get()}-${variant.name}.apk"
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)?.outputFileName?.set(name)
        }
    }
}


dependencies {
    implementation(libs.androidx.media3.exoplayer)

    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.core.splashscreen)
    implementation (libs.androidx.material.icons.extended)
    implementation (libs.androidx.preference.ktx)
    implementation (libs.timber)
    implementation (libs.semver)
    implementation (libs.androidx.webkit)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.compose)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.ktor.network)
    implementation(libs.ktor.client.core) // Ktor-Core
    implementation(libs.ktor.client.android) // Ktor-Engine
    implementation(libs.ktor.serialization.kotlinx.json) // Ktor- To work with Serialization
    implementation(libs.ktor.client.content.negotiation) // Serialization
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.mlkit.face.detection)
    implementation(libs.kotlinx.coroutines.play.services) // Task.await() for ML Kit
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
}

android { sourceSets.getByName("main").kotlin.directories.add("../../shared/src/main/java") }

android {
    sourceSets.getByName("main").kotlin.directories.add("../../shared-output/src/main/java")
    sourceSets.getByName("main").kotlin.directories.add("../../shared-output/sendspin-protocol/src/main/kotlin")
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    ksp("com.squareup.moshi:moshi-kotlin-codegen:1.15.1")
}
