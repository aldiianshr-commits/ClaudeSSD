// Modul inti tanpa dependensi Android: server HTTP, API, autentikasi, penyimpanan, antarmuka web.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// Heap kecil: membuktikan file besar dialirkan (streaming), tidak dimuat ke RAM.
tasks.test {
    maxHeapSize = "96m"
    testLogging { events("passed", "failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// Menjalankan CloudSSD di PC untuk mencoba antarmuka web:
//   ./gradlew :core:run --args="--root /path/folder --data ./cloudssd-data --lan"
application {
    mainClass.set("id.cloudssd.core.DevMainKt")
}
