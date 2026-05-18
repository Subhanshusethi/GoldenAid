plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.subhanshu.gemmacomp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.subhanshu.gemmacomp"
        minSdk = 24
        targetSdk = 35
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
    buildFeatures {
        compose = true
    }
    androidResources {
        noCompress += listOf("tflite", "task")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    configurations.all {
        exclude(group = "org.tensorflow", module = "tensorflow-lite-api")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // TensorFlow Lite / LiteRT (Unified for Gemma and YOLO)
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.support)
    implementation("com.google.android.gms:play-services-tflite-gpu:16.4.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // MediaPipe: To understand body posture (The "Pose" part)
    implementation("com.google.mediapipe:tasks-vision:0.10.35")

    // LiteRT: This is the specific engine to run Gemma on a phone
    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")

    // Accompanist: A helper to pop up those "Allow Camera?" boxes easily
    implementation("com.google.accompanist:accompanist-permissions:0.34.0")
}