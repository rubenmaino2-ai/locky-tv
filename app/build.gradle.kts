plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ar.locky.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "ar.locky.tv"
        minSdk = 21          // Android 5.0: cubre prácticamente todos los Android TV
        targetSdk = 34
        versionCode = 6
        versionName = "0.6.0"

        // Link de donaciones (por ejemplo https://ceneka.net/tu-pagina).
        // Vacío = el botón "Donar" no aparece. Fase 2: completar y listo.
        buildConfigField("String", "DONATION_URL", "\"\"")
    }

    // Firma fija: así cada APK nuevo se instala ENCIMA del anterior
    // sin tener que desinstalar en el TV.
    signingConfigs {
        create("locky") {
            storeFile = file("locky-debug.keystore")
            storePassword = "android"
            keyAlias = "locky"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("locky")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("locky")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        aidl = true   // para hablar con "OpenVPN for Android"
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.zxing:core:3.5.3") // genera los códigos QR
}
