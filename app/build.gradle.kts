plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.novavpn.app"
    compileSdk = 34

    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.novavpn.app"
        minSdk = 26
        targetSdk = 34
        versionCode = project.findProperty("versionCode")?.toString()?.toIntOrNull() ?: 1
        versionName = project.findProperty("versionName")?.toString() ?: "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            // arm64-v8a always. armeabi-v7a is opt-in for one-off 32-bit builds:
            //   ./gradlew assembleDebug -Parmv7=true
            // When building FOR armv7 (tag contains 'armv7'), ship ONLY the
            // 32-bit ABI — a low-end 32-bit device shouldn't carry a 34MB
            // arm64 xray it can never run (halves the APK and install size).
            val armv7Only = project.findProperty("armv7")?.toString()?.toBoolean() == true
            val abis = mutableListOf("arm64-v8a")
            if (armv7Only) {
                abis.clear()
                abis += "armeabi-v7a"
            }
            abiFilters += abis
        }

        externalNativeBuild {
            cmake {
                // Pure C, no C++ needed
                cFlags += listOf("-std=c11")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign the minified build with the debug key so the release APK
            // is installable without a private signing keystore (pre-release
            // artifact for low-end devices; NOT for Play Store).
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Store .so files UNCOMPRESSED in the APK (extractNativeLibs=false).
            // On install, Android mmaps them directly instead of decompressing
            // + extracting ~70MB to disk — the #1 fix for installs that hang
            // or fail on low-end devices with limited RAM/storage.
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // Project modules
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":engine:api"))
    implementation(project(":engine:xray"))
    implementation(project(":network"))
    implementation(project(":statistics"))
    implementation(project(":logging"))
    implementation(project(":subscription"))

    // Features
    implementation(project(":feature:home"))
    implementation(project(":feature:subscriptions"))
    implementation(project(":feature:servers"))
    implementation(project(":feature:statistics"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:logs"))

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.compose.ui.tooling)

    // Hilt
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)

    // Coroutines
    implementation(libs.bundles.coroutines)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.datastore.preferences)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Logging
    implementation(libs.timber)

    // Testing
    testImplementation(libs.bundles.testing)
    androidTestImplementation(platform(libs.compose.bom))
}
