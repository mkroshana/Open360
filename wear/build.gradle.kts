// The watch app: the camera remote with the watch's own GPS, sharing :core with the phone app
import java.io.File
import java.util.Properties

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
        applicationId = "com.mkroshana.open360"
        minSdk = 30
        targetSdk = 35
        versionCode = 20001
        versionName = "0.2.0-beta.1"
        missingDimensionStrategy("distribution", "play")
    }

    val keystoreProps = Properties().apply {
        val propsFile = rootProject.file("keystore.properties")
        if (propsFile.isFile) {
            propsFile.inputStream().use { load(it) }
        }
    }
    fun resolveSigningValue(envVar: String, propKey: String): String? =
        providers.environmentVariable(envVar).orNull?.takeIf { it.isNotBlank() }
            ?: keystoreProps.getProperty(propKey)?.takeIf { it.isNotBlank() }

    val keystorePath = resolveSigningValue("OPEN360_KEYSTORE_PATH", "storeFile")?.trim()
    val keystorePassword = resolveSigningValue("OPEN360_KEYSTORE_PASSWORD", "storePassword")
    val keyAliasValue = (resolveSigningValue("OPEN360_KEYSTORE_ALIAS", "keyAlias")
        ?: resolveSigningValue("OPEN360_KEY_ALIAS", "keyAlias"))?.trim()
    val keyPasswordValue = resolveSigningValue("OPEN360_KEY_PASSWORD", "keyPassword")
    val keystoreFile = keystorePath?.let { path ->
        val candidate = File(path)
        if (candidate.isAbsolute) candidate else rootProject.file(path)
    }?.takeIf { it.isFile }

    val releaseSigningConfig = if (
        keystoreFile != null &&
        keystorePassword != null &&
        keyAliasValue != null &&
        keyPasswordValue != null
    ) {
        signingConfigs.maybeCreate("release").apply {
            storeFile = keystoreFile
            storePassword = keystorePassword
            keyAlias = keyAliasValue
            keyPassword = keyPasswordValue
        }
    } else {
        null
    }

    buildTypes {
        // The build to install on the watch: Compose in a debug build is too slow there, with
        // 42% of frames late while scrolling against 1.6% for this one (2026-10-22). Keep release
        // builds unsigned by default; supply release credentials or pass
        // -Popen360LocalWearDebugSign=true for local watch testing against a debug-signed phone app.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigningConfig != null) {
                signingConfig = releaseSigningConfig
            } else if (providers.gradleProperty("open360LocalWearDebugSign").orNull == "true") {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
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
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.protolayout.material)
    implementation(libs.wear.watchface.complications)
    implementation(libs.androidx.concurrent.futures)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material.icons.extended)

    // Reads the phone app's connection settings
    implementation(libs.play.services.wearable)
    // A newer Fragment than the 1.2.4 Play services brings in, which fails the release lint check
    // for the ActivityResult permission requests
    implementation(libs.androidx.fragment)

    testImplementation(libs.junit)
}
