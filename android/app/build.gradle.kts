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
        noCompress += "tflite" // the hold model is memory-mapped from assets
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.ar:core:1.57.0")
    implementation("com.google.ai.edge.litert:litert:1.4.2") // hold detector (assets/holds.tflite, see tools/holds_model); TF Lite 2.16 cannot run its int8-weight transpose_conv

    testImplementation("junit:junit:4.13.2")
}
