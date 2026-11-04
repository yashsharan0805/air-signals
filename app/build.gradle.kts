plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.codex.fitbitairlab"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.codex.fitbitairlab"
        minSdk = 32
        targetSdk = 36
        versionCode = 5
        versionName = "0.5-preview"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.setSrcDirs(listOf("src"))
            res.setSrcDirs(listOf("res"))
            assets.setSrcDirs(listOf("assets"))
            jniLibs.setSrcDirs(listOf("../vendor/fitbit-air/android/src/main/jniLibs"))
        }
        getByName("androidTest").java.setSrcDirs(listOf("tests"))
    }
    buildFeatures { compose = true }
    signingConfigs {
        getByName("debug") {
            System.getenv("AIR_SIGNALS_KEYSTORE")?.let {
                storeFile = file(it)
                storePassword = System.getenv("AIR_SIGNALS_STORE_PASSWORD") ?: "android"
                keyAlias = "air-lab"
                keyPassword = System.getenv("AIR_SIGNALS_KEY_PASSWORD") ?: "android"
            }
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs.useLegacyPackaging = true; resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/INDEX.LIST") }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("io.reactivex.rxjava2:rxjava:2.2.21")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.jakewharton.timber:timber:5.0.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
