
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.novavpn.feature.settings"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        // Sunk with the release version the user actually receives: CI
        // passes -PversionName (see .github/workflows), so the version shown
        // on the Settings screen always matches the tagged release instead
        // of a hardcoded placeholder.
        buildConfigField(
            "String",
            "VERSION_NAME",
            "\"${project.findProperty("versionName") ?: "1.0.0"}\""
        )
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:common"))
    implementation(project(":core:ui"))

    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.bundles.coroutines)
    debugImplementation(libs.compose.ui.tooling)
}
