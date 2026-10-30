import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Projek Firebase untuk kongsi lokasi rider (tidak masuk git): dbUrl=..., apiKey=...
val firebaseProps = Properties().apply {
    val f = rootProject.file("firebase.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Kunci tandatangan release (tidak masuk git). Semua APK update mesti guna kunci yang sama.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.jagarider"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jagarider"
        minSdk = 26
        targetSdk = 36
        versionCode = 23
        versionName = "3.2"

        // Repo GitHub (owner/nama) tempat semak update — tetapkan dalam gradle.properties
        val repo = (project.findProperty("jagarider.updateRepo") as String?).orEmpty()
        buildConfigField("String", "UPDATE_REPO", "\"$repo\"")
        buildConfigField("String", "FIREBASE_DB", "\"${firebaseProps.getProperty("dbUrl", "").trimEnd('/')}\"")
        buildConfigField("String", "FIREBASE_KEY", "\"${firebaseProps.getProperty("apiKey", "")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Hampir semua guna Android standard supaya ringan. Pengecualian (v2.6) untuk QR kumpulan rider:
    // pengimbas QR Google (UI kamera disediakan Play services, tiada kebenaran kamera) + ZXing untuk jana QR.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.google.zxing:core:3.5.3")
    // v3.1: sensor gerakan (Activity Recognition) untuk auto rekod ride — hampir tiada bateri berbanding GPS
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
