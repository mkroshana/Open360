// The camera link, GPS and sensor code shared by the phone app and the watch app
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.open360.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
        }
        create("foss") {
            dimension = "distribution"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    api(libs.androidx.core.splashscreen)
    api(libs.androidx.lifecycle.viewmodel.ktx)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    // The Wear OS Data Layer, over which the phone and watch apps control the camera through each other
    "playImplementation"(libs.play.services.wearable)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
