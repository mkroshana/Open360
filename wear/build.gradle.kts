// The watch app: the camera remote with the watch's own GPS, sharing :core with the phone app
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.open360.wear"
    compileSdk = 35

    defaultConfig {
        // The phone app's: the Data Layer that hands over its connection settings only joins
        // apps with the same package name, signed with the same key
        applicationId = "com.open360"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
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
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)
    // The camera link shown on the watch face, and the remote kept on screen when it dims
    implementation(libs.wear.ongoing)
    implementation(libs.androidx.wear)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material.icons.extended)

    // Reads the phone app's connection settings
    implementation(libs.play.services.wearable)

    testImplementation(libs.junit)
}
