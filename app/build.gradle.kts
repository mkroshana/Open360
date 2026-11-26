import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.open360"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mkroshana.open360"
        minSdk = 26
        targetSdk = 36
        versionCode = 10001
        versionName = "0.2.0-beta.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "HAS_WEAR_DATA_LAYER", "true")
            buildConfigField("String", "DISTRIBUTION_CHANNEL", "\"play\"")
        }
        create("foss") {
            dimension = "distribution"
            buildConfigField("boolean", "HAS_WEAR_DATA_LAYER", "false")
            buildConfigField("String", "DISTRIBUTION_CHANNEL", "\"foss\"")
        }
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
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigningConfig != null) {
                signingConfig = releaseSigningConfig
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
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.exifinterface)
    // Hands the connection settings to the watch app
    "playImplementation"(libs.play.services.wearable)
    "playImplementation"(libs.wear.remote.interactions)
    // Replaces Fragment 1.2.4 brought in by play-services-wearable, fixing InvalidFragmentVersionForActivityResult lint check
    "playImplementation"(libs.androidx.fragment)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))

    debugImplementation(libs.androidx.compose.ui.tooling)
}
