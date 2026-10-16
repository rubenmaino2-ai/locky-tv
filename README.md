# Locky TV 🐩🔒

App para Android TV que **conecta una VPN gratuita y después abre la app que elijas** del TV, todo con el control remoto.

## Cómo funciona

1. Abrís Locky.
2. Descarga la lista de servidores públicos de **VPN Gate** (red de voluntarios de la Universidad de Tsukuba, Japón). Si no responde, usa la última lista guardada.
3. Elige los mejores para tu casa: les hace un ping desde el TV, prefiere países cercanos, mira velocidad y carga, castiga 1 hora a los que fallan y prioriza el último que anduvo bien.
4. Prueba hasta que uno conecta y mide la velocidad real (se muestra arriba).
5. Muestra las apps instaladas en el TV. Elegís una y se abre (la última que usaste aparece primera).
6. Arriba siempre ves si la VPN está conectada, con botones para cambiar de servidor o desconectar.

La conexión VPN la hace la app gratuita **OpenVPN for Android** (`de.blinkt.openvpn`). Locky le pasa el servidor y le da las órdenes; vos no necesitás abrirla nunca.

## Instalación en el TV

1. **Instalá "OpenVPN for Android"** desde la tienda del TV. Si no aparece, Locky muestra un código QR para bajarla desde el celular.
2. **Descargá el APK de Locky**: en este repo, pestaña **Releases → Locky TV (última versión) → `locky-tv.apk`**.
3. **Pasalo al TV** de alguna de estas formas:
   - Con un pendrive y un explorador de archivos en el TV.
   - Con la app **"Send Files to TV"** (en el celular/PC y en el TV).
   - Con la app **Downloader**, si subís el APK a un link público.
4. Al instalar, el TV va a pedir permitir **"orígenes desconocidos"** para esa app: aceptá.
5. La primera vez que abras Locky te va a pedir **dos permisos** (controlar OpenVPN y crear la VPN): aceptá los dos.

Las versiones nuevas se instalan encima de la anterior, sin desinstalar.

## Compilación

Cada cambio que se sube a `main` se compila solo con GitHub Actions (`.github/workflows/build.yml`), y el APK aparece en **Releases**.

## Mapa del código

| Archivo | Qué hace |
|---|---|
| `app/src/main/java/ar/locky/tv/MainActivity.kt` | Pantalla y flujo completo (conectar → elegir app) |
| `.../vpn/VpnGateClient.kt` | Descarga y ordena los servidores de VPN Gate |
| `.../vpn/ServerPicker.kt` | Elige el mejor servidor (ping, cercanía, castigos) |
| `.../util/SpeedTest.kt` | Mide la velocidad real por la VPN |
| `.../VpnTimerService.kt` | Temporizador que apaga la VPN |
| `.../vpn/OpenVpnEngine.kt` | Le da órdenes a OpenVPN for Android |
| `.../apps/TvApps.kt` | Lista y abre las apps instaladas |
| `.../util/QrCode.kt` | Genera códigos QR |
| `app/src/main/res/values/colors.xml` | Colores de la app |
| `app/src/main/aidl/...` | "Contrato" para hablar con OpenVPN for Android |

## Donaciones (Fase 2)

El botón **"Apoyar"** está programado pero oculto. Para activarlo, en `app/build.gradle.kts` completá:

```kotlin
buildConfigField("String", "DONATION_URL", "\"https://ceneka.net/tu-pagina\"")
```

La app muestra un QR con ese link para donar desde el celular.

## Avisos

- Los servidores de VPN Gate son de voluntarios: pueden ser lentos o caerse, y su operador puede ver a qué te conectás (lo que va por HTTPS sigue cifrado).
- Algunas plataformas de streaming bloquean las VPN o lo prohíben en sus términos.
- La clave de firma (`app/locky-debug.keystore`) es solo para pruebas. Antes de distribuir la app conviene crear una clave privada propia.
