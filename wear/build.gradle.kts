// The watch app: the camera remote with the watch's own GPS, sharing :core with the phone app
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.open360.wear"
    compileSdk = 37

    defaultConfig {
        // The phone app's: the Data Layer that hands over its connection settings only joins
        // apps with the same package name, signed with the same key
        applicationId = "com.open360"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        // The build to install on the watch: Compose in a debug build is too slow there, with
        // 42% of frames late while scrolling against 1.6% for this one (2026-10-22). Signed with
        // the debug key, as the phone app's debug build is, since the Data Layer that hands over
        // the connection settings only joins apps signed with the same key.
        release {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
    // A newer Fragment than the 1.2.4 Play services brings in, which fails the release lint check
    // for the ActivityResult permission requests
    implementation(libs.androidx.fragment)

    testImplementation(libs.junit)
}
