plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.facedetectionapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.facedetectionapp"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Required for Room
        javaCompileOptions {
            annotationProcessorOptions {
                arguments["room.schemaLocation"] = "$projectDir/schemas"
            }
        }
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

    buildFeatures {
        viewBinding = true
    }

    // Avoid conflicts with TFLite native libs
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
        }
        // ONNX Runtime bundles some native libs that may duplicate
        jniLibs {
            pickFirsts += "**/libonnxruntime.so"
        }
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity:1.9.0")
    implementation("androidx.fragment:fragment:1.8.0")
    implementation(libs.constraintlayout)

    // Material Design 3
    implementation("com.google.android.material:material:1.12.0")

    // CameraX
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // ML Kit Face Detection
    implementation("com.google.mlkit:face-detection:16.1.7")

    // TensorFlow Lite (live-camera MobileFaceNet fallback)
    // Note: tensorflow-lite-support is excluded — it has an internal namespace conflict.
    // We build ByteBuffers manually in MobileFaceNetModel.java, so support library is not needed.
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    // ── ONNX Runtime ─────────────────────────────────────────────────────────
    // Used for InsightFace SCRFD detector (det_500m.onnx) and ArcFace recognizer (w600k_mbf.onnx)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.3")

    // Room Database (persistent embeddings & attendance)
    implementation("androidx.room:room-runtime:2.6.1")
    annotationProcessor("androidx.room:room-compiler:2.6.1")

    // RecyclerView for attendance history
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // CardView
    implementation("androidx.cardview:cardview:1.0.0")

    // Glide for image loading
    implementation("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor("com.github.bumptech.glide:glide:4.16.0")

    // Gson for Room TypeConverter (float[] serialization)
    implementation("com.google.code.gson:gson:2.10.1")

    // OkHttp for networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.8.3")

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}