plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
val versionMap = rootProject.file("version.properties").takeIf { it.exists() }
    ?.readLines()?.mapNotNull { l ->
        val p = l.split("=", limit = 2)
        if (p.size == 2) p[0].trim() to p[1].trim() else null
    }?.toMap() ?: emptyMap()
val appVersion = "${versionMap["major"] ?: "1"}.${versionMap["minor"] ?: "0"}.${versionMap["patch"] ?: "0"}"

android {
    namespace = "com.jondude.anzu"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jondude.anzu"
        minSdk = 31
        targetSdk = 34
        versionCode = runNumber
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        create("anzu") {
            storeFile = rootProject.file("keystore/anzu.jks")
            storePassword = "anzuone"
            keyAlias = "anzu"
            keyPassword = "anzuone"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("anzu")
        }
        debug {
            signingConfig = signingConfigs.getByName("anzu")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    lint { abortOnError = false; checkReleaseBuilds = false }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Offline speech recognition (fetched by ci/fetch-deps.sh)
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
}
