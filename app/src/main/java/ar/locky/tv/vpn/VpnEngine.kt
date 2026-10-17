package ar.locky.tv.vpn

import android.content.Intent

/**
 * El "enchufe" de motores de VPN: lo que Locky necesita de cualquier motor.
 * MainActivity y el temporizador hablan con esto, no con un motor en particular,
 * así cambiar de motor (o agregar otro, por ejemplo WireGuard) no toca el resto.
 */
interface VpnEngine {

    enum class State { IDLE, CONNECTING, CONNECTED, FAILED }

    /** El pedido de permiso de VPN de Android que falta, o null si ya está aceptado. */
    fun permissionIntent(): Intent?

    /**
     * Conecta con la configuración [ovpnConfig] de un servidor.
     * [apps] = las únicas apps que pasan por la VPN (Locky se agrega solo, para medir
     * la velocidad). null = todo el TV por la VPN.
     */
    fun start(ovpnConfig: String, apps: Set<String>?)

    /** Corta la VPN (si estaba prendida). */
    suspend fun stop()

    /** Cómo va la conexión que se pidió con [start]. */
    val state: State

    /** Se llama al cerrar la pantalla: deja de escuchar al motor. */
    fun release()
}
