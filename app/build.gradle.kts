plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.mrz_native"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mrz_native"
        minSdk = 32
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)

    // CameraX
    implementation(libs.camera.core)
    implementation(libs.camera.camera2) // QUAN TRỌNG
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.camera.extensions)

    // ML Kit (on-device, bundled)
    implementation(libs.mlkit.text.recognition)

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

//    implementation(libs.appcompat)
//    implementation(libs.material)
//    implementation(libs.camera.view)
//    implementation(libs.camera.lifecycle)
//    implementation(libs.vision.common)
//    implementation(libs.play.services.mlkit.text.recognition.common)
//    implementation(libs.play.services.mlkit.text.recognition)
//    testImplementation(libs.junit)
//    androidTestImplementation(libs.ext.junit)
//    androidTestImplementation(libs.espresso.core)
}