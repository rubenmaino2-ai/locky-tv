plugins {
    id("com.android.application")   // trae Kotlin incorporado (herramientas de Android 9)
}

android {
    namespace = "ar.locky.tv"
    compileSdk = 37
    ndkVersion = "30.0.14904198"   // la misma del motor: sirve para achicar sus partes en C

    defaultConfig {
        applicationId = "ar.locky.tv"
        minSdk = 23          // Android 6.0: lo pide el motor de VPN integrado
        targetSdk = 34
        versionCode = 8
        versionName = "0.7.0"

        // Link de donaciones (por ejemplo https://ceneka.net/tu-pagina).
        // Vacío = el botón "Donar" no aparece. Fase 2: completar y listo.
        buildConfigField("String", "DONATION_URL", "\"\"")

        // El motor de VPN viene en un solo "sabor": sin pantallas propias.
        missingDimensionStrategy("implementation", "skeleton")
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
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
    packaging {
        jniLibs {
            // OpenVPN corre como un programa aparte: Android tiene que poder "desempaquetarlo".
            useLegacyPackaging = true
            // Partes del motor que Locky no usa (OpenVPN 3 y test de cifrado).
            excludes += listOf("**/libovpn3.so", "**/libosslspeedtest.so")
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":vpncore"))   // motor de VPN integrado
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.zxing:core:3.5.3") // genera los códigos QR
}
