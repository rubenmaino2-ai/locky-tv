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
        versionCode = 7
        versionName = "0.6.1"

        // Link de donaciones (por ejemplo https://ceneka.net/tu-pagina).
        // Vacío = el botón "Donar" no aparece. Fase 2: completar y listo.
        buildConfigField("String", "DONATION_URL", "\"\"")
    }

    // Firma fija: así cada APK nuevo se instala ENCIMA del anterior sin desinstalar.
    // La clave NO está en el código: GitHub la pone desde sus "secretos" al compilar
    // (LOCKY_KEYSTORE_B64 y LOCKY_KEYSTORE_PASSWORD). Así nadie puede firmar un Locky falso.
    val keystorePath = System.getenv("LOCKY_KEYSTORE_PATH")
    val keystorePassword = System.getenv("LOCKY_KEYSTORE_PASSWORD")
    signingConfigs {
        create("locky") {
            if (keystorePath != null && keystorePassword != null) {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = "locky"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        debug {
            if (keystorePath != null) signingConfig = signingConfigs.getByName("locky")
        }
        release {
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("locky")
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
