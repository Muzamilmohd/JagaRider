import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
        versionCode = 3
        versionName = "1.2"

        // Repo GitHub (owner/nama) tempat semak update â€” tetapkan dalam gradle.properties
        val repo = (project.findProperty("jagarider.updateRepo") as String?).orEmpty()
        buildConfigField("String", "UPDATE_REPO", "\"$repo\"")
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
    // Tiada library luar â€” semua guna Android standard supaya ringan.
}
