# Locky TV 🐩🔒

App para Android TV que **abre la app que elijas a través de una VPN gratuita**, todo con el control remoto. Solo esa app pasa por la VPN: el resto del TV sigue navegando normal.

## Cómo funciona

1. Abrís Locky. Mientras ves la bienvenida, baja la lista de servidores públicos de **VPN Gate** (red de voluntarios de la Universidad de Tsukuba, Japón). Si no responde, usa la última lista guardada.
2. Elige los mejores para tu casa: les hace un ping desde el TV, prefiere países cercanos, mira velocidad y carga, castiga 1 hora a los que fallan y prioriza el último que anduvo bien.
3. Muestra las apps instaladas en el TV (la última que usaste aparece primera). Elegís una.
4. Si es una app que suele bloquear VPNs (Netflix, Disney+, Prime Video, bancos…), te avisa y te deja abrirla con o sin VPN.
5. Conecta la VPN **solo para esa app**, mide la velocidad real y la abre. Si la conexión es lenta, te ofrece probar otro servidor.
6. Al volver a Locky con "Atrás": cuenta regresiva de 5 segundos y corta la VPN.

El motor de VPN viene **adentro de Locky**: es el de la app libre **OpenVPN for Android** (de Arne Schwabe, licencia GPL v2), en su versión sin pantallas. No hace falta instalar nada más.

## Instalación en el TV

1. **Descargá el APK de Locky**: en este repo, pestaña **Releases → Locky TV (última versión) → `locky-tv.apk`**.
2. **Pasalo al TV** de alguna de estas formas:
   - Con un pendrive y un explorador de archivos en el TV.
   - Con la app **"Send Files to TV"** (en el celular/PC y en el TV).
   - Con la app **Downloader**, si subís el APK a un link público.
3. Al instalar, el TV va a pedir permitir **"orígenes desconocidos"** para esa app: aceptá.
4. La primera vez que abras una app con VPN, Android te pide **un permiso** para que Locky cree la VPN: aceptá.

Las versiones nuevas se instalan encima de la anterior, sin desinstalar.

## Compilación

Cada cambio que se sube a `main` se compila solo con GitHub Actions (`.github/workflows/build.yml`), y el APK aparece en **Releases → Locky TV (última versión)**. Los cambios en otras ramas publican un APK aparte, **Locky TV (PRUEBA)**, para probar sin tocar la versión buena.

El motor de VPN es un submódulo de git (`vpncore/ics-openvpn`, fijado a una versión oficial). Para clonar el repo con todo: `git clone --recursive`.

## Mapa del código

| Archivo | Qué hace |
|---|---|
| `app/src/main/java/ar/locky/tv/MainActivity.kt` | Pantalla y flujo completo (conectar → elegir app) |
| `.../vpn/VpnGateClient.kt` | Descarga y ordena los servidores de VPN Gate |
| `.../vpn/ServerPicker.kt` | Elige el mejor servidor (ping, cercanía, castigos) |
| `.../util/SpeedTest.kt` | Mide la velocidad real por la VPN |
| `.../VpnTimerService.kt` | Temporizador que apaga la VPN |
| `.../vpn/VpnEngine.kt` | El "enchufe" común para motores de VPN |
| `.../vpn/LockyVpnEngine.kt` | Maneja el motor integrado (una VPN por app) |
| `.../apps/VpnBlockers.kt` | Apps que suelen bloquear VPNs (para avisar) |
| `vpncore/` | El motor de VPN (OpenVPN for Android sin pantallas) |
| `.../apps/TvApps.kt` | Lista y abre las apps instaladas |
| `.../util/QrCode.kt` | Genera códigos QR |
| `app/src/main/res/values/colors.xml` | Colores de la app |

## Donaciones (Fase 2)

El botón **"Apoyar"** está programado pero oculto. Para activarlo, en `app/build.gradle.kts` completá:

```kotlin
buildConfigField("String", "DONATION_URL", "\"https://ceneka.net/tu-pagina\"")
```

La app muestra un QR con ese link para donar desde el celular.

## Avisos

- Los servidores de VPN Gate son de voluntarios: pueden ser lentos o caerse, y su operador puede ver a qué te conectás (lo que va por HTTPS sigue cifrado).
- Algunas plataformas de streaming bloquean las VPN o lo prohíben en sus términos.
- El APK se firma con una clave privada que está en los secretos del repo (no en el código).

## Licencia

Locky es software libre bajo la **GPL v2** (ver `LICENSE`), porque incluye el motor de [OpenVPN for Android](https://github.com/schwabe/ics-openvpn) de Arne Schwabe, que tiene esa licencia.
