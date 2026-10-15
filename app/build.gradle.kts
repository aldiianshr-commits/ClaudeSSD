plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Kunci tanda tangan tetap (opsional) dari variabel lingkungan, mis. diisi GitHub Actions dari Secrets.
// Tanpa kunci tetap, setiap build di mesin baru memakai kunci debug acak sehingga pembaruan APK
// tidak bisa dipasang di atas versi lama.
val stableKeystore: String? = System.getenv("CLOUDSSD_KEYSTORE")?.takeIf { file(it).exists() }

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

    signingConfigs {
        if (stableKeystore != null) {
            create("stable") {
                storeFile = file(stableKeystore)
                storePassword = System.getenv("CLOUDSSD_KEYSTORE_PASSWORD")
                keyAlias = "cloudssd"
                keyPassword = System.getenv("CLOUDSSD_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfigs.findByName("stable")?.let { signingConfig = it }
        }
        release {
            // Tanpa minify agar mudah ditelusuri; APK dipasang manual (sideload).
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("stable") ?: signingConfigs.getByName("debug")
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
