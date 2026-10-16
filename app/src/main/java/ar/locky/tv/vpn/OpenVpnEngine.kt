package ar.locky.tv.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import de.blinkt.openvpn.api.IOpenVPNAPIService
import de.blinkt.openvpn.api.IOpenVPNStatusCallback
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * "Motor" de la VPN: le da órdenes a la app "OpenVPN for Android"
 * a través de su interfaz pública (AIDL). Locky nunca abre esa app:
 * le pasa la configuración del servidor y escucha cómo va la conexión.
 */
class OpenVpnEngine(context: Context) {

    companion object {
        const val PACKAGE = "de.blinkt.openvpn"
        private const val STATE_CONNECTED = "CONNECTED"
        private const val STATE_AUTH_FAILED = "AUTH_FAILED"
    }

    private val appContext = context.applicationContext
    private var api: IOpenVPNAPIService? = null
    private var connection: ServiceConnection? = null
    private var callbackRegistered = false

    @Volatile var lastState: String? = null
        private set

    private val statusCallback = object : IOpenVPNStatusCallback.Stub() {
        override fun newStatus(uuid: String?, state: String?, message: String?, level: String?) {
            lastState = state
        }
    }

    fun isInstalled(): Boolean = try {
        appContext.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Se conecta al servicio de OpenVPN for Android. Devuelve false si no se pudo. */
    suspend fun bind(): Boolean {
        if (api != null) return true
        return suspendCancellableCoroutine { cont ->
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    api = IOpenVPNAPIService.Stub.asInterface(binder)
                    if (cont.isActive) cont.resume(true)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    api = null
                    callbackRegistered = false
                }
            }
            connection = conn
            val intent = Intent(IOpenVPNAPIService::class.java.name).setPackage(PACKAGE)
            val ok = try {
                appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            } catch (e: SecurityException) {
                false
            }
            if (!ok && cont.isActive) cont.resume(false)
        }
    }

    /** null = Locky ya tiene permiso para controlar OpenVPN. Si no, un Intent para pedirlo. */
    fun apiPermissionIntent(packageName: String): Intent? = api?.prepare(packageName)

    /** null = el permiso de VPN de Android ya está aceptado. */
    fun vpnPermissionIntent(): Intent? = api?.prepareVPNService()

    fun listenStatus() {
        if (callbackRegistered) return
        try {
            api?.registerStatusCallback(statusCallback)
            callbackRegistered = true
        } catch (e: Exception) {
            // Sin callback igual funcionamos: también miramos si Android tiene una VPN activa.
        }
    }

    fun start(ovpnConfig: String) {
        lastState = null
        api?.startVPN(ovpnConfig)
    }

    fun stop() {
        try {
            api?.disconnect()
        } catch (e: Exception) {
            // Si la VPN la había prendido otra app, OpenVPN no nos deja cortarla.
        }
    }

    fun isConnected() = lastState == STATE_CONNECTED
    fun isAuthFailed() = lastState == STATE_AUTH_FAILED

    fun release() {
        if (callbackRegistered) {
            try { api?.unregisterStatusCallback(statusCallback) } catch (e: Exception) { }
        }
        connection?.let {
            try { appContext.unbindService(it) } catch (e: Exception) { }
        }
        connection = null
        api = null
        callbackRegistered = false
    }
}
