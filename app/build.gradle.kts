plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.stocktracker"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.stocktracker"
        minSdk = 24; targetSdk = 34
        // CI sets GITHUB_RUN_NUMBER, so every build has a higher versionCode (required for updates)
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + versionCode
    }
    // Permanent signing key. The key file itself (app/stock-release.p12) is NOT in the repo:
    // the workflow restores it from the GitHub secret KEYSTORE_BASE64. Without the file, Gradle falls back to the debug key.
    signingConfigs {
        create("fixed") {
            storeFile = file("stock-release.p12")
            storePassword = "W5ukcTs3mTWCz7gloTnM"
            keyAlias = "stock"
            keyPassword = "W5ukcTs3mTWCz7gloTnM"
            storeType = "pkcs12"
        }
    }
    buildTypes {
        getByName("debug") {
            if (file("stock-release.p12").exists()) signingConfig = signingConfigs.getByName("fixed")
        }
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
