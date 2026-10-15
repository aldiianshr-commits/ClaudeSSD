plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "id.cloudssd.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "id.cloudssd.app"
        // Xiaomi Pad 6 dirilis dengan Android 13 (MIUI 14) dan diperbarui ke HyperOS (Android 14/15).
        // minSdk 30 (Android 11) memberi akses StorageVolume.getDirectory & callback volume.
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            // Tanpa minify agar mudah ditelusuri; APK dipasang manual (sideload).
            isMinifyEnabled = false
            // Untuk rilis sungguhan, buat keystore sendiri (lihat docs/BUILD.md).
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "DebugProbesKt.bin")
    }
    lint {
        // Wake lock tanpa batas waktu dipakai dengan sengaja selama server berjalan (dapat dimatikan pengguna).
        disable += setOf("Wakelock", "WakelockTimeout", "BatteryLife")
    }
}

dependencies {
    implementation(project(":core"))
}
