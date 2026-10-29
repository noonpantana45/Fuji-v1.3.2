plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.fujicam.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fujicam.app"
        minSdk = 29          // Android 10 ขึ้นไป
        targetSdk = 35
        versionCode = 7
        versionName = "1.3.2"
    }

    // กุญแจเซ็นแอพตายตัว ทำให้อัปเดตทับเวอร์ชันเก่าได้ (ใช้ติดตั้งเอง ไม่เหมาะกับการลง Play Store)
    signingConfigs {
        create("fujicam") {
            storeFile = file("fujicam.keystore")
            storePassword = "fujicam2026"
            keyAlias = "fujicam"
            keyPassword = "fujicam2026"
            storeType = "pkcs12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fujicam")
        }
        debug {
            signingConfig = signingConfigs.getByName("fujicam")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val camerax = "1.4.1"
    val media3 = "1.5.1"

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // กล้อง
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // ใส่ฟิลเตอร์ให้วิดีโอ
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
}
