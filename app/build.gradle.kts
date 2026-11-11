plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.silo.chargemonitor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.silo.chargemonitor"
        minSdk = 26
        targetSdk = 35
        // CI passes the GitHub Actions run number so every published build is a newer version.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = 100 + (build ?: 0)
        versionName = if (build != null) "1.$build" else "1.0-dev"
        buildConfigField("String", "UPDATE_BASE_URL",
            "\"https://github.com/silo226-cloud/BatteryCharger/releases/download/latest\"")
    }
    buildFeatures { buildConfig = true }

    // Updates only install over the old app when every build is signed with the same key.
    // CI decodes it from the SIGNING_KEYSTORE_BASE64 secret; local builds fall back to the debug key.
    val keystorePath = System.getenv("SIGNING_KEYSTORE_FILE")
    signingConfigs {
        if (keystorePath != null) create("release") {
            storeFile = file(keystorePath)
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "batterycharger"
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: System.getenv("SIGNING_STORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
