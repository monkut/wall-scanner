plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.monkut.wallscanner"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.monkut.wallscanner"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        noCompress += listOf("tflite", "task") // the hold model, SAM and the pose model are memory-mapped from assets
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.ar:core:1.57.0")
    implementation("androidx.camera:camera-core:1.4.2") // climb video: preview, recording, frames for the pose model
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-video:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.mediapipe:tasks-vision:0.10.29") // pose landmarks (assets/pose_landmarker_lite.task) to find attempts in a session
    implementation("com.google.ai.edge.litert:litert:1.4.2") // hold detector (assets/holds.tflite, see tools/holds_model); TF Lite 2.16 cannot run its int8-weight transpose_conv

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // android.jar's org.json is a stub on the JVM; RouteStore reads and writes sends.json with it
}
