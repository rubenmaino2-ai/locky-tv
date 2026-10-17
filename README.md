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

📄 **Página de instalación (con QR):** https://rubenmaino2-ai.github.io/locky-tv/

🔗 **Link fijo del APK** (siempre la última versión):
`https://github.com/rubenmaino2-ai/locky-tv/releases/download/ultima/locky-tv.apk`

### Con la app Downloader (recomendado)

1. **Instalá "Downloader"** desde Google Play del TV ("Downloader by AFTVnews", ícono naranja). Es gratis.
2. **Permití que Downloader instale apps:** Configuración → Apps → Seguridad y restricciones → Fuentes desconocidas → activá *Downloader*. (El nombre del menú cambia un poco según la marca.)
3. **Abrí Downloader**, escribí el **código de Downloader** de Locky (está en la página de instalación) o el link fijo de arriba, y tocá *Go*.
4. Cuando termine de bajar, Android pregunta si querés instalar: **Instalar**.
5. **Si aparece "Google Play Protect"**: avisa porque Locky no viene de la tienda y Google no conoce al desarrollador. Tocá *Más detalles → Instalar igualmente*.
6. **Abrí Locky** desde la fila de apps del TV. La primera vez que abras una app con VPN, Android puede pedir un permiso para que Locky cree la VPN: aceptalo.

### Otras formas

- Bajá el APK con el celular (escaneando el QR de la página) y pasalo al TV con **"Send Files to TV"**.
- Con un pendrive y un explorador de archivos en el TV.

### Actualizar

Repetí el paso 3: el link y el código siempre apuntan a la última versión, y se instala encima sin perder nada.

> **Si actualizaste y la VPN no conecta** ("Ver detalles" muestra *requested job be persisted without holding RECEIVE_BOOT_COMPLETED*): reiniciá el TV desenchufándolo 10 segundos. Pasa solo una vez, al venir de la versión 0.7.0.

## Compilación

Cada cambio que se sube a `main` se compila solo con GitHub Actions (`.github/workflows/build.yml`), y el APK aparece en **Releases → Locky TV (última versión)**. Los cambios en otras ramas publican un APK aparte, **Locky TV (PRUEBA)**, para probar sin tocar la versión buena.

La página de instalación está en `docs/` (GitHub Pages, rama `main`, carpeta `/docs`). Para mostrar el código de Downloader, completar `data-codigo` en `docs/index.html`.

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
