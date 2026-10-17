/*
 * Motor de VPN integrado en Locky.
 *
 * Es el mismo motor de la app "OpenVPN for Android" (ics-openvpn, de Arne Schwabe,
 * licencia GPL v2), en su versión "skeleton" (sin pantallas). El código original
 * está en la carpeta ics-openvpn (submódulo fijado a una versión oficial) y NO se
 * modifica: este archivo solo le dice a Gradle cómo compilarlo como librería.
 *
 * Para actualizar el motor: cambiar el submódulo a una versión nueva (tag vX.Y.Z).
 */
plugins {
    id("com.android.library")
}

val ics = file("ics-openvpn/main")

android {
    namespace = "de.blinkt.openvpn"   // el motor usa este nombre para sus recursos (R)
    compileSdk = 37
    ndkVersion = "30.0.14904198"      // la misma que usa el proyecto original

    defaultConfig {
        minSdk = 23

        // Usamos OpenVPN 2 (el clásico): es el que mejor se lleva con VPN Gate.
        buildConfigField("boolean", "openvpn3", "false")

        externalNativeBuild {
            cmake {
                // Solo las arquitecturas de los TV (ARM de 32 y 64 bits).
                abiFilters += listOf("armeabi-v7a", "arm64-v8a")
            }
        }
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    // El motor decide algunas cosas según el "sabor": "skeleton" = sin pantallas.
    flavorDimensions += "implementation"
    productFlavors {
        create("skeleton") { dimension = "implementation" }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile(File(ics, "src/main/AndroidManifest.xml"))
            java.directories.add(File(ics, "src/main/java").path)
            kotlin.directories.add(File(ics, "src/main/java").path)   // el motor tiene algún archivo Kotlin
            aidl.directories.add(File(ics, "src/main/aidl").path)
            res.directories.add(File(ics, "src/main/res").path)
            // El binario de OpenVPN para Android 6 a 8 (lo genera la compilación en C).
            assets.directories.add(File(ics, "build/ovpnassets").path)
        }
        maybeCreate("skeleton").apply {
            manifest.srcFile(File(ics, "src/skeleton/AndroidManifest.xml"))
            java.directories.add(File(ics, "src/skeleton/java").path)
            kotlin.directories.add(File(ics, "src/skeleton/java").path)
            res.directories.add(File(ics, "src/skeleton/res").path)
        }
    }

    externalNativeBuild {
        cmake {
            path = File(ics, "src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.10.0")
    implementation("androidx.core:core:1.19.0")
}
