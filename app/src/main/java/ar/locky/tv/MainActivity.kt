package ar.locky.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import ar.locky.tv.apps.AppGridAdapter
import ar.locky.tv.apps.TvApp
import ar.locky.tv.apps.TvApps
import ar.locky.tv.util.NetworkUtil
import ar.locky.tv.util.QrCode
import ar.locky.tv.util.SpeedTest
import ar.locky.tv.vpn.OpenVpnEngine
import ar.locky.tv.vpn.RankedServer
import ar.locky.tv.vpn.ServerPicker
import ar.locky.tv.vpn.VpnGateClient
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Flujo de Locky:
 *  1. ¿Ya hay VPN activa? -> directo a la grilla de apps.
 *  2. ¿Está instalado "OpenVPN for Android"? Si no, explicamos cómo instalarlo.
 *  3. Pedimos los permisos (solo la primera vez).
 *  4. Bajamos la lista de VPN Gate y probamos servidores hasta que uno conecte.
 *  5. Mostramos las apps instaladas; al elegir una, se abre.
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_API = 101
        private const val REQ_VPN = 102
        private const val MAX_TRIES = 10                 // servidores a probar como máximo
        private const val CONNECT_TIMEOUT_MS = 20_000L   // espera por servidor
        private const val SPLASH_MIN_MS = 10_000L        // bienvenida con el logo de Locky
        private const val KEY_LAST_APP = "last_app"
        private const val KEY_RETURN_FROM = "return_from"   // app que abrimos y de la que se espera volver
        private const val RETURN_COUNTDOWN_S = 5            // segundos antes de cortar la VPN al volver
        private const val OPENVPN_STORE_URL =
            "https://play.google.com/store/apps/details?id=de.blinkt.openvpn"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var engine: OpenVpnEngine
    private lateinit var prefs: SharedPreferences

    private var flowJob: Job? = null
    private var monitorJob: Job? = null
    private var pendingPermission: CompletableDeferred<Boolean>? = null
    private var connectedCountry: String? = null
    private var connectedMbps: Double? = null
    private var speedJob: Job? = null
    private lateinit var btnTimer: Button
    private var apps: List<TvApp> = emptyList()

    private lateinit var statusChip: TextView
    private lateinit var btnReconnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnDonate: Button
    private lateinit var progressPanel: View
    private lateinit var progressTitle: TextView
    private lateinit var progressDetail: TextView
    private lateinit var messagePanel: View
    private lateinit var messageTitle: TextView
    private lateinit var messageBody: TextView
    private lateinit var messageQr: ImageView
    private lateinit var messageMascot: ImageView
    private lateinit var btnPrimary: Button
    private lateinit var btnSecondary: Button
    private lateinit var appsPanel: View
    private lateinit var appsGrid: GridView
    private lateinit var splashPanel: View
    private lateinit var splashTitle: TextView
    private lateinit var splashDetail: TextView
    private lateinit var splashSpinner: View

    /** Momento hasta el cual la bienvenida queda sí o sí en pantalla. */
    private var splashUntil = 0L
    private var splashHideJob: Job? = null

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        engine = OpenVpnEngine(this)
        prefs = getSharedPreferences("locky", MODE_PRIVATE)
        bindViews()

        btnReconnect.setOnClickListener { startFlow(forceNew = true) }
        btnDisconnect.setOnClickListener { disconnect() }
        btnTimer.setOnClickListener { cycleTimer() }
        btnDonate.visibility = if (BuildConfig.DONATION_URL.isNotBlank()) View.VISIBLE else View.GONE
        btnDonate.setOnClickListener { showDonation() }
        appsGrid.setOnItemClickListener { _, _, position, _ -> openApp(apps[position]) }

        // Bienvenida: Locky en pantalla completa como mínimo SPLASH_MIN_MS,
        // mientras por detrás ya se está conectando la VPN.
        // Excepción: si volvemos de una app que abrió Locky (y la VPN sigue prendida),
        // no hay bienvenida: onResume muestra directamente la cuenta regresiva.
        if (isReturningFromApp()) {
            splashPanel.visibility = View.GONE
        } else {
            splashUntil = SystemClock.elapsedRealtime() + SPLASH_MIN_MS
            splashPanel.requestFocus()
            startFlow(forceNew = false)
        }
    }

    override fun onResume() {
        super.onResume()
        val returnedFrom = prefs.getString(KEY_RETURN_FROM, null)
        if (returnedFrom != null) {
            prefs.edit().remove(KEY_RETURN_FROM).apply()
            if (NetworkUtil.isVpnActive(this)) {
                showReturnCountdown(returnedFrom)
                return
            }
        }
        if (appsPanel.visibility == View.VISIBLE) refreshStatus()
    }

    private fun isReturningFromApp() =
        prefs.getString(KEY_RETURN_FROM, null) != null && NetworkUtil.isVpnActive(this)

    override fun onDestroy() {
        scope.cancel()
        engine.release()
        super.onDestroy()
    }

    @Deprecated("Usamos la API clásica de Activity a propósito (sin librerías extra)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_API || requestCode == REQ_VPN) {
            pendingPermission?.complete(resultCode == RESULT_OK)
            pendingPermission = null
        }
    }

    // ---------------------------------------------------------------- flujo principal

    private fun startFlow(forceNew: Boolean) {
        flowJob?.cancel()
        monitorJob?.cancel()
        flowJob = scope.launch {
            try {
                connectFlow(forceNew)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError("Algo salió mal", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun connectFlow(forceNew: Boolean) {
        // 1. Si ya hay una VPN andando, no hace falta hacer nada.
        if (!forceNew && NetworkUtil.isVpnActive(this)) {
            showApps(connectedInfo = "Tu VPN ya estaba activa")
            return
        }

        // 2. El motor tiene que estar instalado.
        if (!engine.isInstalled()) {
            showEngineMissing()
            return
        }

        // 3. Conectarnos a OpenVPN for Android y pedir permisos (solo la primera vez).
        showProgress("Preparando…", "Conectando con OpenVPN for Android")
        val bound = withTimeoutOrNull(10_000) { engine.bind() } ?: false
        if (!bound) {
            showError(
                "No pude comunicarme con OpenVPN",
                "Abrí la app \"OpenVPN for Android\" una vez, volvé a Locky y tocá Reintentar."
            )
            return
        }
        engine.apiPermissionIntent(packageName)?.let { intent ->
            if (!askPermission(intent, REQ_API)) {
                showError("Falta un permiso", "Hay que permitir que Locky controle OpenVPN. Tocá Reintentar y aceptá.")
                return
            }
        }
        engine.vpnPermissionIntent()?.let { intent ->
            if (!askPermission(intent, REQ_VPN)) {
                showError("Falta un permiso", "Hay que aceptar el permiso de VPN de Android. Tocá Reintentar y aceptá.")
                return
            }
        }
        engine.listenStatus()

        if (forceNew) {
            engine.stop()
            waitUntilVpnDown()
        }

        // 4. Buscar servidores (si vpngate.net no responde, usa la última lista guardada).
        showProgress("Buscando servidores…", "Descargando la lista de VPN Gate")
        val list = try {
            VpnGateClient.fetchServers(File(cacheDir, "vpngate.csv"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

        // 5. Elegir los mejores para TU casa: ping real, cercanía, velocidad, castigos.
        val candidates: List<RankedServer> = if (list != null) {
            val origin = if (list.fromCache) " (lista guardada)" else ""
            showProgress("Eligiendo el mejor servidor…", "Midiendo la distancia a los servidores$origin")
            val ranked = ServerPicker.rank(this, list.servers)
            ranked.filter { it.alive }.take(MAX_TRIES).ifEmpty { ranked.take(MAX_TRIES) }
        } else {
            // Ni lista nueva ni guardada: probamos con el último que anduvo.
            val last = ServerPicker.lastGoodServer(this)
            if (last == null) {
                showError("No pude bajar la lista de servidores", "Revisá la conexión a internet del TV y tocá Reintentar.")
                return
            }
            listOf(RankedServer(last, ServerPicker.ping(last), 0))
        }

        // 6. Probar uno por uno hasta que conecte.
        for ((index, ranked) in candidates.withIndex()) {
            val server = ranked.server
            val serverInfo = "VPN Gate · ${server.hostName} · ${server.countryEs}"
            val pingText = if (ranked.alive) " · ${ranked.pingMs} ms" else ""
            showProgress(
                "Conectando…",
                "$serverInfo$pingText  (servidor ${index + 1} de ${candidates.size})"
            )
            engine.start(server.ovpnConfig)
            if (waitForConnection()) {
                ServerPicker.recordSuccess(this, server)
                connectedCountry = server.countryEs
                connectedMbps = null
                showApps(connectedInfo = serverInfo)
                return
            }
            ServerPicker.recordFailure(this, server)
            engine.stop()
            waitUntilVpnDown()
        }

        showError(
            "Ningún servidor respondió",
            "Probé ${candidates.size} servidores y no conectó ninguno. Suele pasar a ratos: probá de nuevo en unos minutos."
        )
    }

    private suspend fun waitForConnection(): Boolean {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < CONNECT_TIMEOUT_MS) {
            if (engine.isConnected() || NetworkUtil.isVpnActive(this)) return true
            if (engine.isAuthFailed()) return false
            delay(500)
        }
        return false
    }

    private suspend fun waitUntilVpnDown() {
        val start = SystemClock.elapsedRealtime()
        while (NetworkUtil.isVpnActive(this) && SystemClock.elapsedRealtime() - start < 8_000) {
            delay(500)
        }
        delay(1_000)
    }

    @Suppress("DEPRECATION")
    private suspend fun askPermission(intent: Intent, requestCode: Int): Boolean {
        val result = CompletableDeferred<Boolean>()
        pendingPermission = result
        try {
            startActivityForResult(intent, requestCode)
        } catch (e: ActivityNotFoundException) {
            pendingPermission = null
            return false
        }
        return result.await()
    }

    private fun disconnect() {
        scope.launch { disconnectVpn() }
    }

    /** Corta la VPN. Devuelve true si quedó efectivamente apagada. */
    private suspend fun disconnectVpn(): Boolean {
        VpnTimerService.cancel(this)   // si había temporizador, ya no hace falta
        val bound = withTimeoutOrNull(5_000) { engine.bind() } ?: false
        if (bound) engine.stop()
        connectedMbps = null
        delay(1_500)
        refreshStatus()
        val stillOn = NetworkUtil.isVpnActive(this)
        if (stillOn) {
            Toast.makeText(this, "No pude cortar la VPN (la prendió otra app)", Toast.LENGTH_LONG).show()
        }
        return !stillOn
    }

    // ---------------------------------------------------------------- volver de otra app

    private var countdownJob: Job? = null

    /**
     * Volviste a Locky desde la app que abriste: cuenta regresiva y se corta la VPN,
     * salvo que elijas "Seguir conectado".
     */
    private fun showReturnCountdown(packageName: String) {
        flowJob?.cancel()
        countdownJob?.cancel()
        val appName = TvApps.labelOf(this, packageName)
        showMessage(
            title = "¿Terminaste con $appName?",
            body = countdownText(RETURN_COUNTDOWN_S),
            primary = "Seguir conectado" to {
                countdownJob?.cancel()
                showApps(connectedInfo = "VPN activa")
            },
            secondary = "Desconectar ya" to { disconnectNow() }
        )
        refreshStatus()
        countdownJob = scope.launch {
            for (s in RETURN_COUNTDOWN_S downTo 1) {
                messageBody.text = countdownText(s)
                delay(1_000)
            }
            finishDisconnect()
        }
    }

    private fun disconnectNow() {
        countdownJob?.cancel()
        countdownJob = scope.launch { finishDisconnect() }
    }

    private fun countdownText(seconds: Int) =
        "Desconecto la VPN en $seconds…\nTocá \"Seguir conectado\" si vas a abrir otra app."

    private suspend fun finishDisconnect() {
        messageBody.text = "Desconectando…"
        if (disconnectVpn()) {
            showMessage(
                title = "Listo, VPN desconectada",
                body = "Tu TV volvió a navegar normal. ¡Hasta la próxima!",
                primary = "Salir" to { finish() },
                secondary = "Volver a conectar" to { startFlow(forceNew = false) }
            )
        } else {
            showApps(connectedInfo = "VPN activa")
        }
    }

    // ---------------------------------------------------------------- apps

    private fun showApps(connectedInfo: String) {
        splashTitle.text = "¡Conectado!"
        splashTitle.setTextColor(color(R.color.accent))
        splashDetail.text = connectedInfo
        splashSpinner.visibility = View.GONE
        apps = TvApps.load(this, prefs.getString(KEY_LAST_APP, null))
        appsGrid.adapter = AppGridAdapter(apps)
        showPanel(appsPanel)
        btnReconnect.visibility = View.VISIBLE
        releaseSplash(focusTarget = appsGrid)
        appsGrid.setSelection(0)
        startMonitor()
        if (connectedMbps == null) runSpeedTest()
    }

    /** Mide la velocidad real por la VPN y la muestra (bienvenida + indicador de arriba). */
    private fun runSpeedTest() {
        speedJob?.cancel()
        speedJob = scope.launch {
            delay(1_500)
            if (!NetworkUtil.isVpnActive(this@MainActivity)) return@launch
            val mbps = SpeedTest.measureMbps() ?: return@launch
            connectedMbps = mbps
            if (splashPanel.visibility == View.VISIBLE) {
                splashDetail.text = "${splashDetail.text}\nVelocidad: ${SpeedTest.describe(mbps)}"
            }
            refreshStatus()
            if (mbps < 4) {
                Toast.makeText(
                    this@MainActivity,
                    "Conexión lenta (${SpeedTest.describe(mbps)}). Podés tocar \"Cambiar servidor\".",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ---------------------------------------------------------------- temporizador de apagado

    private val timerOptions = listOf(30, 60, 120, 180)   // minutos

    /** Cada toque pasa a la siguiente opción: 30 min → 1 h → 2 h → 3 h → sin temporizador. */
    private fun cycleTimer() {
        val remaining = VpnTimerService.remainingMs(this)
        val currentMin = ((remaining + 59_999) / 60_000).toInt()
        val next = if (remaining <= 0) timerOptions.first()
            else timerOptions.firstOrNull { it > currentMin }
        if (next == null) {
            VpnTimerService.cancel(this)
            Toast.makeText(this, "Temporizador cancelado", Toast.LENGTH_SHORT).show()
        } else {
            VpnTimerService.start(this, next)
            Toast.makeText(this, "La VPN se apaga en ${VpnTimerService.format(next * 60_000L)}", Toast.LENGTH_SHORT).show()
        }
        refreshStatus()
    }

    private fun openApp(app: TvApp) {
        if (!NetworkUtil.isVpnActive(this)) {
            Toast.makeText(this, "La VPN no está conectada. Reconectando…", Toast.LENGTH_LONG).show()
            startFlow(forceNew = false)
            return
        }
        prefs.edit().putString(KEY_LAST_APP, app.packageName).apply()
        if (TvApps.launch(this, app.packageName)) {
            // Al volver a Locky (botón Atrás), onResume ofrece cortar la VPN.
            prefs.edit().putString(KEY_RETURN_FROM, app.packageName).apply()
        } else {
            Toast.makeText(this, "No pude abrir ${app.label}", Toast.LENGTH_LONG).show()
        }
    }

    /** Cada 3 segundos revisa si la VPN sigue arriba y actualiza el indicador. */
    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                refreshStatus()
                delay(3_000)
            }
        }
    }

    private fun refreshStatus() {
        val appsVisible = appsPanel.visibility == View.VISIBLE
        if (NetworkUtil.isVpnActive(this)) {
            val parts = mutableListOf("●  VPN conectada")
            connectedCountry?.let { parts += it }
            connectedMbps?.let { parts += if (it < 10) String.format("%.1f Mbps", it) else "${it.toInt()} Mbps" }
            statusChip.text = parts.joinToString(" · ")
            statusChip.setTextColor(color(if ((connectedMbps ?: 99.0) < 4) R.color.gold else R.color.accent))
            btnReconnect.text = getString(R.string.reconnect)
            btnDisconnect.visibility = if (appsVisible) View.VISIBLE else View.GONE
            val left = VpnTimerService.remainingMs(this)
            btnTimer.text = if (left > 0) "⏱ ${VpnTimerService.format(left)}" else "⏱ Apagar en…"
            btnTimer.visibility = if (appsVisible) View.VISIBLE else View.GONE
        } else {
            statusChip.text = "●  VPN desconectada"
            statusChip.setTextColor(color(R.color.danger))
            btnReconnect.text = "Conectar"
            btnDisconnect.visibility = View.GONE
            btnTimer.visibility = View.GONE
        }
    }

    // ---------------------------------------------------------------- pantallas

    private fun showPanel(panel: View) {
        for (p in listOf(progressPanel, messagePanel, appsPanel)) {
            p.visibility = if (p === panel) View.VISIBLE else View.GONE
        }
        if (panel !== appsPanel) {
            btnReconnect.visibility = View.GONE
            btnDisconnect.visibility = View.GONE
            btnTimer.visibility = View.GONE
        }
    }

    private fun showProgress(title: String, detail: String) {
        progressTitle.text = title
        progressDetail.text = detail
        statusChip.text = "●  Conectando…"
        statusChip.setTextColor(color(R.color.gold))
        showPanel(progressPanel)
        // Si la bienvenida está en pantalla, el progreso se ve ahí abajo del logo.
        splashTitle.text = title
        splashTitle.setTextColor(color(R.color.text))
        splashDetail.text = detail
        splashSpinner.visibility = View.VISIBLE
    }

    /**
     * Saca la bienvenida, pero recién cuando se cumplieron los 10 segundos.
     * Mientras tanto, el logo sigue en pantalla con el resultado ("¡Conectado!").
     */
    private fun releaseSplash(focusTarget: View) {
        if (splashPanel.visibility != View.VISIBLE) {
            focusTarget.requestFocus()
            return
        }
        splashHideJob?.cancel()
        splashHideJob = scope.launch {
            val remaining = splashUntil - SystemClock.elapsedRealtime()
            if (remaining > 0) delay(remaining)
            splashPanel.animate().alpha(0f).setDuration(500).withEndAction {
                splashPanel.visibility = View.GONE
                focusTarget.requestFocus()
            }.start()
        }
    }

    private fun showMessage(
        title: String,
        body: String,
        qr: String? = null,
        primary: Pair<String, () -> Unit>,
        secondary: Pair<String, () -> Unit>? = null
    ) {
        messageTitle.text = title
        messageBody.text = body
        if (qr != null) {
            messageQr.setImageBitmap(QrCode.bitmap(qr))
            messageQr.visibility = View.VISIBLE
            messageMascot.visibility = View.GONE   // no entran los dos en la pantalla
        } else {
            messageQr.visibility = View.GONE
            messageMascot.visibility = View.VISIBLE
        }
        btnPrimary.text = primary.first
        btnPrimary.setOnClickListener { primary.second() }
        if (secondary != null) {
            btnSecondary.text = secondary.first
            btnSecondary.setOnClickListener { secondary.second() }
            btnSecondary.visibility = View.VISIBLE
        } else {
            btnSecondary.visibility = View.GONE
        }
        statusChip.text = "●  VPN desconectada"
        statusChip.setTextColor(color(R.color.danger))
        showPanel(messagePanel)
        splashTitle.text = title
        splashTitle.setTextColor(color(R.color.danger))
        splashDetail.text = ""
        splashSpinner.visibility = View.GONE
        releaseSplash(focusTarget = btnPrimary)
    }

    private fun showError(title: String, body: String) {
        showMessage(title, body, primary = "Reintentar" to { startFlow(forceNew = false) })
    }

    private fun showEngineMissing() {
        showMessage(
            title = "Falta instalar \"OpenVPN for Android\"",
            body = "Locky usa esa app gratuita (de Arne Schwabe) para conectar la VPN. " +
                "Instalala desde la tienda del TV, o escaneá el código con el celular para ver dónde bajarla.",
            qr = OPENVPN_STORE_URL,
            primary = "Abrir tienda" to { openStore() },
            secondary = "Ya la instalé" to { startFlow(forceNew = false) }
        )
    }

    private fun openStore() {
        val attempts = listOf(
            Uri.parse("market://details?id=${OpenVpnEngine.PACKAGE}"),
            Uri.parse(OPENVPN_STORE_URL)
        )
        for (uri in attempts) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                return
            } catch (e: ActivityNotFoundException) {
                // probamos la siguiente opción
            }
        }
        Toast.makeText(this, "No encontré una tienda en este TV. Usá el código QR.", Toast.LENGTH_LONG).show()
    }

    private fun showDonation() {
        val url = BuildConfig.DONATION_URL
        val density = resources.displayMetrics.density
        val qrSize = (240 * density).toInt()
        val pad = (24 * density).toInt()

        val qrImage = ImageView(this).apply {
            setImageBitmap(QrCode.bitmap(url))
            layoutParams = LinearLayout.LayoutParams(qrSize, qrSize)
        }
        val text = TextView(this).apply {
            this.text = "Escaneá con el celular para colaborar:\n$url"
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(0, pad / 2, 0, 0)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            addView(qrImage)
            addView(text)
        }
        AlertDialog.Builder(this)
            .setTitle("Apoyá a Locky")
            .setView(box)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    // ---------------------------------------------------------------- utilidades

    @Suppress("DEPRECATION")
    private fun color(id: Int): Int = resources.getColor(id)

    private fun bindViews() {
        statusChip = findViewById(R.id.statusChip)
        btnReconnect = findViewById(R.id.btnReconnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnDonate = findViewById(R.id.btnDonate)
        progressPanel = findViewById(R.id.progressPanel)
        progressTitle = findViewById(R.id.progressTitle)
        progressDetail = findViewById(R.id.progressDetail)
        messagePanel = findViewById(R.id.messagePanel)
        messageTitle = findViewById(R.id.messageTitle)
        messageBody = findViewById(R.id.messageBody)
        messageQr = findViewById(R.id.messageQr)
        messageMascot = findViewById(R.id.messageMascot)
        btnPrimary = findViewById(R.id.btnPrimary)
        btnSecondary = findViewById(R.id.btnSecondary)
        appsPanel = findViewById(R.id.appsPanel)
        appsGrid = findViewById(R.id.appsGrid)
        btnTimer = findViewById(R.id.btnTimer)
        splashPanel = findViewById(R.id.splashPanel)
        splashTitle = findViewById(R.id.splashTitle)
        splashDetail = findViewById(R.id.splashDetail)
        splashSpinner = findViewById(R.id.splashSpinner)
    }
}
