package ar.locky.tv.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.IBinder
import android.os.SystemClock
import de.blinkt.openvpn.R as MotorR
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import java.io.StringReader
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Motor de VPN integrado en Locky: el mismo motor de "OpenVPN for Android"
 * (módulo vpncore), pero adentro de Locky. Ya no hace falta otra app.
 *
 * Diferencia clave con el motor anterior: acá Locky decide QUÉ APPS pasan por la VPN.
 * Android arma el túnel solo para esas apps; el resto del TV sigue navegando directo.
 *
 * El motor corre en un proceso aparte (":openvpn"), así la VPN sigue andando
 * aunque se cierre la pantalla de Locky. Nos enteramos de cómo va por VpnStatus.
 */
class LockyVpnEngine(context: Context) : VpnEngine {

    companion object {
        private const val PREFS = "locky"
        private const val KEY_TUNNEL_APP = "tunnel_app"

        /** La app para la que está armado el túnel ahora (si la VPN está prendida). */
        fun tunnelApp(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TUNNEL_APP, null)

        /**
         * Las últimas líneas del registro del motor (lo que va diciendo OpenVPN).
         * Sirve para entender por qué no conecta un servidor.
         */
        fun recentLog(context: Context, lines: Int = 40): String {
            val items = VpnStatus.getlogbuffer()
            if (items.isEmpty()) return "(el motor no mandó ningún mensaje)"
            return items.takeLast(lines).joinToString("\n") { item ->
                try { item.getString(context) } catch (e: Exception) { "?" }
            }
        }

        /** Anota una línea propia de Locky en el mismo registro. */
        fun note(text: String) = VpnStatus.logInfo("Locky: $text")

        private fun setTunnelApp(context: Context, packageName: String?) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_TUNNEL_APP, packageName).apply()
        }
    }

    private val appContext = context.applicationContext

    @Volatile private var level: ConnectionStatus? = null
    @Volatile private var startedAt = 0L
    @Volatile private var sawProgress = false   // el motor ya empezó a intentar conectar
    @Volatile private var exited = false        // ...y después se cortó solo

    private val statusListener = object : VpnStatus.StateListener {
        override fun updateState(
            state: String?, logmessage: String?, localizedResId: Int,
            level: ConnectionStatus?, intent: Intent?
        ) {
            this@LockyVpnEngine.level = level
            if (level != null && level != ConnectionStatus.LEVEL_NOTCONNECTED) {
                sawProgress = true
            } else if (level == ConnectionStatus.LEVEL_NOTCONNECTED && sawProgress &&
                SystemClock.elapsedRealtime() - startedAt > 3_000
            ) {
                // OpenVPN arrancó y se cerró: no tiene sentido esperar más a este servidor.
                exited = true
            }
        }

        override fun setConnectedVPN(uuid: String?) {}
    }

    init {
        VpnStatus.addStateListener(statusListener)
    }

    override fun permissionIntent(): Intent? = VpnService.prepare(appContext)

    override fun start(ovpnConfig: String, apps: Set<String>?) {
        // Leemos el .ovpn del servidor con el mismo lector que usa OpenVPN for Android.
        val parser = ConfigParser()
        parser.parseConfig(StringReader(ovpnConfig))
        val profile = parser.convertProfile()
        profile.mName = "Locky"

        if (apps != null) {
            // Lista de apps PERMITIDAS: solo estas van por la VPN.
            profile.mAllowedAppsVpnAreDisallowed = false
            profile.mAllowedAppsVpn = HashSet(apps + appContext.packageName)
        } else {
            // Lista de apps EXCLUIDAS vacía: todo el TV va por la VPN.
            profile.mAllowedAppsVpnAreDisallowed = true
            profile.mAllowedAppsVpn = HashSet()
        }

        val check = profile.checkProfile(appContext)
        if (check != MotorR.string.no_error_found) {
            throw IllegalStateException(appContext.getString(check))
        }

        level = null
        sawProgress = false
        exited = false
        startedAt = SystemClock.elapsedRealtime()
        setTunnelApp(appContext, apps?.firstOrNull())
        // Perfil "temporal": no queda guardado como perfil del usuario.
        ProfileManager.setTemporaryProfile(appContext, profile)
        // true = si había otra conexión de Locky, la reemplaza.
        VPNLaunchHelper.startOpenVpn(profile, appContext, "Locky", true)
    }

    override suspend fun stop() {
        setTunnelApp(appContext, null)
        level = null
        // Le pedimos al servicio de la VPN que corte (es lo mismo que hace su botón "Desconectar").
        withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { cont ->
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        try {
                            IOpenVPNServiceInternal.Stub.asInterface(binder).stopVPN(false)
                        } catch (e: Exception) {
                            // Si ya estaba cortada no pasa nada.
                        }
                        try { appContext.unbindService(this) } catch (e: Exception) { }
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {}
                }
                val intent = Intent(appContext, OpenVPNService::class.java)
                    .setAction(OpenVPNService.START_SERVICE)
                val bound = try {
                    appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                } catch (e: Exception) {
                    false
                }
                if (!bound && cont.isActive) cont.resume(Unit)
            }
        }
    }

    override val state: VpnEngine.State
        get() = if (exited) VpnEngine.State.FAILED else when (level) {
            ConnectionStatus.LEVEL_CONNECTED -> VpnEngine.State.CONNECTED
            ConnectionStatus.LEVEL_AUTH_FAILED -> VpnEngine.State.FAILED
            null, ConnectionStatus.LEVEL_NOTCONNECTED -> VpnEngine.State.IDLE
            else -> VpnEngine.State.CONNECTING
        }

    override fun release() {
        VpnStatus.removeStateListener(statusListener)
    }
}
