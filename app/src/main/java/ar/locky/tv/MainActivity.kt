package ar.locky.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
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
import ar.locky.tv.apps.VpnBlockers
import ar.locky.tv.util.NetworkUtil
import ar.locky.tv.util.QrCode
import ar.locky.tv.util.SpeedTest
import ar.locky.tv.vpn.LockyVpnEngine
import ar.locky.tv.vpn.RankedServer
import ar.locky.tv.vpn.ServerPicker
import ar.locky.tv.vpn.VpnEngine
import ar.locky.tv.vpn.VpnGateClient
import ar.locky.tv.vpn.VpnServer
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Flujo de Locky (motor de VPN integrado, una VPN por app):
 *  1. Bienvenida con Locky mientras, por detrás, baja la lista de VPN Gate y elige
 *     los mejores servidores.
 *  2. Grilla de apps del TV. Elegís una.
 *  3. Si la app suele bloquear VPNs, avisa y te deja abrirla con o sin VPN.
 *  4. Conecta la VPN SOLO para esa app (y Locky, para medir la velocidad):
 *     el resto del TV sigue navegando directo.
 *  5. Mide la velocidad y abre la app.
 *  6. Al volver con "Atrás": cuenta regresiva y corta la VPN.
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_VPN = 102
        private const val MAX_TRIES = 10                 // servidores a probar como máximo
        private const val CONNECT_TIMEOUT_MS = 20_000L   // espera por servidor
        private const val SPLASH_MIN_MS = 10_000L        // bienvenida con el logo de Locky
        private const val SERVERS_MAX_AGE_MS = 15 * 60_000L   // la lista elegida vale 15 min
        private const val SLOW_MBPS = 4.0                // menos que esto: "conexión lenta"
        private const val KEY_LAST_APP = "last_app"
        private const val KEY_RETURN_FROM = "return_from"   // app que abrimos y de la que se espera volver
        private const val RETURN_COUNTDOWN_S = 5            // segundos antes de cortar la VPN al volver
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var engine: VpnEngine
    private lateinit var prefs: SharedPreferences

    private var flowJob: Job? = null
    private var monitorJob: Job? = null
    private var pendingPermission: CompletableDeferred<Boolean>? = null
    private var apps: List<TvApp> = emptyList()

    // Servidores ya elegidos (para no volver a bajar la lista con cada app).
    private var serversTask: Deferred<List<RankedServer>>? = null
    private var rankedServers: List<RankedServer> = emptyList()
    private var rankedAt = 0L

    // La conexión actual.
    private var currentServer: VpnServer? = null
    private var connectedMbps: Double? = null

    /** Qué hacer con "Atrás" cuando hay un mensaje en pantalla (null = salir de Locky). */
    private var onBackFromMessage: (() -> Unit)? = null

    private lateinit var statusChip: TextView
    private lateinit var btnTimer: Button
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
        engine = LockyVpnEngine(this)
        prefs = getSharedPreferences("locky", MODE_PRIVATE)
        bindViews()

        btnReconnect.setOnClickListener { changeServer() }
        btnDisconnect.setOnClickListener { disconnect() }
        btnTimer.setOnClickListener { cycleTimer() }
        btnDonate.visibility = if (BuildConfig.DONATION_URL.isNotBlank()) View.VISIBLE else View.GONE
        btnDonate.setOnClickListener { showDonation() }
        appsGrid.setOnItemClickListener { _, _, position, _ -> onAppChosen(apps[position]) }

        // Bienvenida: Locky en pantalla completa como mínimo SPLASH_MIN_MS,
        // mientras por detrás ya se están eligiendo los servidores.
        // Excepción: si volvemos de una app que abrió Locky (y la VPN sigue prendida),
        // no hay bienvenida: onResume muestra directamente la cuenta regresiva.
        if (isReturningFromApp()) {
            splashPanel.visibility = View.GONE
        } else {
            splashUntil = SystemClock.elapsedRealtime() + SPLASH_MIN_MS
            splashPanel.requestFocus()
            startup()
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
    override fun onBackPressed() {
        val back = onBackFromMessage
        if (messagePanel.visibility == View.VISIBLE && back != null) {
            back()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    @Deprecated("Usamos la API clásica de Activity a propósito (sin librerías extra)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            pendingPermission?.complete(resultCode == RESULT_OK)
            pendingPermission = null
        }
    }

    // ---------------------------------------------------------------- arranque

    /** Al abrir Locky: preparar los servidores y mostrar las apps. */
    private fun startup() {
        flowJob?.cancel()
        flowJob = scope.launch {
            // ¿Ya hay un túnel de Locky andando? Directo a la grilla.
            if (NetworkUtil.isVpnActive(this@MainActivity) && LockyVpnEngine.tunnelApp(this@MainActivity) != null) {
                currentServer = ServerPicker.lastGoodServer(this@MainActivity)
                showApps(info = "VPN activa")
                return@launch
            }

            showProgress("Buscando servidores…", "Descargando la lista de VPN Gate", chip = "●  Preparando…")
            // Esperamos lo que dure la bienvenida; si tarda más, seguimos por detrás.
            val wait = (splashUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 2_000
            val servers = withTimeoutOrNull(wait) { serversAsync().await() }
            val info = when {
                servers == null -> "Sigo buscando servidores… ya podés elegir una app"
                servers.isEmpty() -> "No pude bajar la lista de servidores: lo reintento al elegir una app"
                else -> "Servidores listos · elegí una app"
            }
            showApps(info)
        }
    }

    /**
     * Baja la lista de VPN Gate y elige los mejores servidores para TU casa
     * (ping real, cercanía, velocidad, castigos). Si ya está elegida y es reciente, la reusa.
     */
    private fun serversAsync(refresh: Boolean = false): Deferred<List<RankedServer>> {
        serversTask?.let { if (it.isActive) return it }
        val fresh = SystemClock.elapsedRealtime() - rankedAt < SERVERS_MAX_AGE_MS
        if (!refresh && fresh && rankedServers.isNotEmpty()) return CompletableDeferred(rankedServers)

        return scope.async {
            val list = try {
                VpnGateClient.fetchServers(File(cacheDir, "vpngate.csv"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val result = if (list != null) {
                val ranked = ServerPicker.rank(this@MainActivity, list.servers)
                ranked.filter { it.alive }.take(MAX_TRIES).ifEmpty { ranked.take(MAX_TRIES) }
            } else {
                // Ni lista nueva ni guardada: probamos con el último que anduvo.
                ServerPicker.lastGoodServer(this@MainActivity)
                    ?.let { listOf(RankedServer(it, ServerPicker.ping(it), 0)) }
                    ?: emptyList()
            }
            if (result.isNotEmpty()) {
                rankedServers = result
                rankedAt = SystemClock.elapsedRealtime()
            }
            result
        }.also { serversTask = it }
    }

    // ---------------------------------------------------------------- elegir y abrir una app

    private fun onAppChosen(app: TvApp) {
        prefs.edit().putString(KEY_LAST_APP, app.packageName).apply()
        if (VpnBlockers.isLikelyBlocker(app.packageName, app.label)) {
            showMessage(
                title = "${app.label} suele bloquear las VPN",
                body = "Muchas apps de streaming y de bancos detectan las VPN gratuitas: " +
                    "pueden no reproducir, dar error o mostrar menos contenido.\n¿Cómo la abro?",
                primary = "Abrir con VPN" to { openWithVpn(app.packageName) },
                secondary = "Abrir sin VPN" to { openWithoutVpn(app.packageName) },
                onBack = { showApps() }
            )
            return
        }
        openWithVpn(app.packageName)
    }

    private fun openWithVpn(packageName: String) {
        val tunnelReady = NetworkUtil.isVpnActive(this) && LockyVpnEngine.tunnelApp(this) == packageName
        if (tunnelReady) {
            launchApp(packageName)
        } else {
            startConnect(packageName, launchAfter = true)
        }
    }

    /** Sin VPN: la app no está en el túnel, así que va directo por internet normal. */
    private fun openWithoutVpn(packageName: String) {
        showApps()
        if (!TvApps.launch(this, packageName)) {
            Toast.makeText(this, "No pude abrir ${TvApps.labelOf(this, packageName)}", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchApp(packageName: String) {
        showApps()
        if (TvApps.launch(this, packageName)) {
            // Al volver a Locky (botón Atrás), onResume ofrece cortar la VPN.
            prefs.edit().putString(KEY_RETURN_FROM, packageName).apply()
        } else {
            Toast.makeText(this, "No pude abrir ${TvApps.labelOf(this, packageName)}", Toast.LENGTH_LONG).show()
        }
    }

    /** "Cambiar servidor": rearma el túnel de la misma app con otro servidor. */
    private fun changeServer() {
        val app = LockyVpnEngine.tunnelApp(this) ?: return
        startConnect(app, launchAfter = false, avoidHost = currentServer?.hostName)
    }

    // ---------------------------------------------------------------- conectar

    private fun startConnect(packageName: String, launchAfter: Boolean, avoidHost: String? = null) {
        flowJob?.cancel()
        flowJob = scope.launch {
            try {
                connectFlow(packageName, launchAfter, avoidHost)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError("Algo salió mal", e.message ?: e.javaClass.simpleName) {
                    startConnect(packageName, launchAfter, avoidHost)
                }
            }
        }
    }

    private suspend fun connectFlow(packageName: String, launchAfter: Boolean, avoidHost: String?) {
        val label = TvApps.labelOf(this, packageName)
        val retry = { startConnect(packageName, launchAfter, avoidHost) }

        // 1. Permiso de VPN de Android (solo la primera vez).
        engine.permissionIntent()?.let { intent ->
            if (!askPermission(intent, REQ_VPN)) {
                showError("Falta un permiso", "Hay que aceptar el permiso de VPN de Android. Tocá Reintentar y aceptá.", retry)
                return
            }
        }

        // 2. Servidores (si ya estaban elegidos, es instantáneo).
        showProgress("Buscando servidores…", "Para abrir $label con VPN")
        // (Si ya estaban elegidos, se reusan: con la VPN prendida, volver a medir pings
        //  daría números falsos porque irían por adentro del túnel.)
        var candidates = serversAsync().await()
        val current = currentServer
        candidates = when {
            avoidHost != null -> candidates.filter { it.server.hostName != avoidHost }
            // Si ya hay un túnel (para otra app), probamos primero el mismo servidor: es el más rápido.
            current != null && NetworkUtil.isVpnActive(this) ->
                listOf(RankedServer(current, 0, 0)) + candidates.filter { it.server.hostName != current.hostName }
            else -> candidates
        }
        if (candidates.isEmpty()) {
            showError("No pude bajar la lista de servidores", "Revisá la conexión a internet del TV y tocá Reintentar.", retry)
            return
        }

        // 3. Probar uno por uno hasta que conecte. Solo esta app (y Locky) van por la VPN.
        for ((index, ranked) in candidates.withIndex()) {
            val server = ranked.server
            val serverInfo = "VPN Gate · ${server.hostName} · ${server.countryEs}"
            val pingText = if (ranked.alive && ranked.pingMs > 0) " · ${ranked.pingMs} ms" else ""
            showProgress(
                "Conectando $label…",
                "$serverInfo$pingText  (servidor ${index + 1} de ${candidates.size})"
            )
            val started = try {
                engine.start(server.ovpnConfig, setOf(packageName))
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false   // configuración que el motor no acepta: siguiente servidor
            }
            if (started && waitForConnection()) {
                ServerPicker.recordSuccess(this, server)
                currentServer = server
                afterConnected(packageName, label, serverInfo, launchAfter)
                return
            }
            ServerPicker.recordFailure(this, server)
            engine.stop()
            waitUntilVpnDown()
        }

        showError(
            "Ningún servidor respondió",
            "Probé ${candidates.size} servidores y no conectó ninguno. Suele pasar a ratos: probá de nuevo en unos minutos.",
            retry
        )
    }

    /** Conectado: medimos la velocidad real por la VPN y abrimos la app. */
    private suspend fun afterConnected(packageName: String, label: String, serverInfo: String, launchAfter: Boolean) {
        connectedMbps = null
        showProgress("¡Conectado!", "$serverInfo\nMidiendo la velocidad…", chip = "●  VPN conectada")
        val mbps = SpeedTest.measureMbps()
        connectedMbps = mbps
        val speedText = mbps?.let { "Velocidad: ${SpeedTest.describe(it)}" } ?: "No pude medir la velocidad"

        if (!launchAfter) {
            showApps(info = "$serverInfo\n$speedText")
            return
        }
        if (mbps != null && mbps < SLOW_MBPS) {
            showMessage(
                title = "Conexión lenta",
                body = "Este servidor da ${SpeedTest.describe(mbps)}.\n¿Abro $label igual o pruebo otro servidor?",
                primary = "Abrir igual" to { launchApp(packageName) },
                secondary = "Probar otro servidor" to {
                    startConnect(packageName, launchAfter = true, avoidHost = currentServer?.hostName)
                },
                onBack = { showApps() }
            )
            return
        }
        launchApp(packageName)
    }

    private suspend fun waitForConnection(): Boolean {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < CONNECT_TIMEOUT_MS) {
            when (engine.state) {
                VpnEngine.State.CONNECTED -> return true
                VpnEngine.State.FAILED -> return false
                else -> delay(500)
            }
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
        scope.launch {
            disconnectVpn()
            refreshStatus()
        }
    }

    /** Corta la VPN. Devuelve true si quedó efectivamente apagada. */
    private suspend fun disconnectVpn(): Boolean {
        VpnTimerService.cancel(this)   // si había temporizador, ya no hace falta
        engine.stop()
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
                showApps()
            },
            secondary = "Desconectar ya" to { disconnectNow() }
        )
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
                secondary = "Elegir otra app" to { showApps() }
            )
        } else {
            showApps()
        }
    }

    // ---------------------------------------------------------------- apps

    /** Muestra la grilla de apps. [info] se ve abajo del logo si la bienvenida sigue en pantalla. */
    private fun showApps(info: String? = null) {
        onBackFromMessage = null
        if (info != null) {
            val connected = NetworkUtil.isVpnActive(this)
            splashTitle.text = if (connected) "¡Conectado!" else "¡Listo!"
            splashTitle.setTextColor(color(R.color.accent))
            splashDetail.text = info
            splashSpinner.visibility = View.GONE
        }
        apps = TvApps.load(this, prefs.getString(KEY_LAST_APP, null))
        appsGrid.adapter = AppGridAdapter(apps)
        showPanel(appsPanel)
        refreshStatus()
        releaseSplash(focusTarget = appsGrid)
        appsGrid.setSelection(0)
        startMonitor()
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
        val tunnelApp = LockyVpnEngine.tunnelApp(this)
        if (NetworkUtil.isVpnActive(this)) {
            val parts = mutableListOf("●  VPN")
            tunnelApp?.let { parts[0] = "●  VPN para ${TvApps.labelOf(this, it)}" }
            currentServer?.let { parts += it.countryEs }
            connectedMbps?.let { parts += if (it < 10) String.format("%.1f Mbps", it) else "${it.toInt()} Mbps" }
            statusChip.text = parts.joinToString(" · ")
            statusChip.setTextColor(color(if ((connectedMbps ?: 99.0) < SLOW_MBPS) R.color.gold else R.color.accent))
            // "Cambiar servidor" solo tiene sentido para un túnel de Locky.
            btnReconnect.visibility = if (appsVisible && tunnelApp != null) View.VISIBLE else View.GONE
            btnDisconnect.visibility = if (appsVisible) View.VISIBLE else View.GONE
            val left = VpnTimerService.remainingMs(this)
            btnTimer.text = if (left > 0) "⏱ ${VpnTimerService.format(left)}" else "⏱ Apagar en…"
            btnTimer.visibility = if (appsVisible) View.VISIBLE else View.GONE
        } else {
            statusChip.text = "●  VPN apagada · elegí una app para conectar"
            statusChip.setTextColor(color(R.color.text_secondary))
            btnReconnect.visibility = View.GONE
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

    private fun showProgress(title: String, detail: String, chip: String = "●  Conectando…") {
        onBackFromMessage = null
        progressTitle.text = title
        progressDetail.text = detail
        statusChip.text = chip
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
     * Mientras tanto, el logo sigue en pantalla con el resultado.
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
        secondary: Pair<String, () -> Unit>? = null,
        onBack: (() -> Unit)? = null
    ) {
        onBackFromMessage = onBack
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
        showPanel(messagePanel)
        refreshStatus()
        splashTitle.text = title
        splashTitle.setTextColor(color(R.color.gold))
        splashDetail.text = ""
        splashSpinner.visibility = View.GONE
        releaseSplash(focusTarget = btnPrimary)
    }

    private fun showError(title: String, body: String, retry: () -> Unit) {
        showMessage(
            title, body,
            primary = "Reintentar" to retry,
            secondary = "Ver apps" to { showApps() },
            onBack = { showApps() }
        )
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
