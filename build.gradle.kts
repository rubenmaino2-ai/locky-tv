plugins {
    // Versión 9 de las herramientas de Android: la pide el motor de VPN integrado.
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
    // Las herramientas 9 ya traen Kotlin adentro; esto solo fija qué versión de Kotlin usan.
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
}
