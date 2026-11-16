plugins {
    id("com.android.application") version "8.10.0"
    id("org.jetbrains.kotlin.android") version "2.4.0"
}
android {
    namespace = "br.com.renan.painelhome"
    compileSdk = 36
    defaultConfig {
        applicationId = "br.com.renan.painelhome"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs.getByName("debug") {
        storeFile = file("${System.getProperty("user.home")}/.android/painel-casa-debug.keystore")
    }
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
dependencies {
    implementation("androidx.activity:activity-ktx:1.12.4")
    implementation("com.google.android.gms:play-services-home:17.1.0")
    implementation("com.google.android.gms:play-services-home-types:17.1.0")
    implementation("com.google.android.gms:play-services-auth:21.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
