package ar.locky.tv.vpn

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/** Un servidor con la "nota" que le puso Locky (menor = mejor). */
data class RankedServer(val server: VpnServer, val pingMs: Int, val cost: Int) {
    val alive: Boolean get() = pingMs >= 0
}

/**
 * Elige los mejores servidores para conectarse desde TU casa:
 *  1. Le hace un "ping" rápido a cada candidato desde el TV (los muertos van al fondo).
 *  2. Prefiere países cercanos (América) a los lejanos (Japón, Corea).
 *  3. Suma velocidad de la línea y cuánta gente lo usa.
 *  4. Castiga 1 hora a los que fallaron 3 veces seguidas.
 *  5. Le da prioridad al último que anduvo bien.
 */
object ServerPicker {

    private const val PREFS = "locky_servers"
    private const val MAX_TO_PING = 40
    private const val PING_TIMEOUT_MS = 1_500
    private const val FAILS_FOR_PENALTY = 3
    private const val PENALTY_MS = 60L * 60 * 1000   // 1 hora

    private const val KEY_LAST_GOOD_IP = "last_good_ip"
    private const val KEY_LAST_GOOD_CONFIG = "last_good_config"
    private const val KEY_LAST_GOOD_HOST = "last_good_host"
    private const val KEY_LAST_GOOD_CC = "last_good_cc"
    private const val KEY_LAST_GOOD_COUNTRY = "last_good_country"

    // Cercanía aproximada a Argentina (sin VPN: menos distancia = más velocidad y menos cortes).
    private val NEAR = setOf("AR", "BR", "CL", "UY", "PY", "BO", "PE", "CO", "EC", "VE", "MX", "US", "CA", "PA", "CR")
    private val MID = setOf("ES", "PT", "FR", "GB", "DE", "NL", "BE", "IT", "CH", "AT", "IE", "PL", "SE", "NO", "DK", "FI", "CZ", "RO")

    suspend fun rank(context: Context, servers: List<VpnServer>): List<RankedServer> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastGoodIp = prefs.getString(KEY_LAST_GOOD_IP, null)

        // Partimos de los mejores según VPN Gate, sumando siempre el último bueno.
        val pool = servers.sortedByDescending { it.score }.take(MAX_TO_PING).toMutableList()
        servers.firstOrNull { it.ip == lastGoodIp }?.let { if (it !in pool) pool += it }

        val pings = pingAll(pool)
        val ranked = pool.zip(pings).map { (server, ping) ->
            RankedServer(server, ping, cost(server, ping, server.ip == lastGoodIp))
        }
        val (penalized, ok) = ranked.partition { isPenalized(context, it.server.ip) }
        // Los castigados quedan al final, por si no anda ningún otro.
        return ok.sortedBy { it.cost } + penalized.sortedBy { it.cost }
    }

    private fun cost(server: VpnServer, pingMs: Int, isLastGood: Boolean): Int {
        if (pingMs < 0) return 100_000 + server.sessions   // no respondió: al fondo
        val region = when (server.countryCode.uppercase()) {
            in NEAR -> 0
            in MID -> 120
            else -> 220
        }
        val load = (server.sessions * 2).coerceAtMost(120)
        val speedBonus = server.speedMbps.coerceAtMost(150.0).toInt()
        val lastGoodBonus = if (isLastGood) 150 else 0
        return pingMs + region + load - speedBonus - lastGoodBonus
    }

    /** "Ping" TCP en paralelo: intenta abrir una conexión y mide cuánto tarda. -1 = no respondió. */
    private suspend fun pingAll(servers: List<VpnServer>): List<Int> = coroutineScope {
        servers.map { s ->
            async(Dispatchers.IO) {
                // OpenVPN por UDP no contesta a un ping TCP; VPN Gate siempre atiende el 443 por TCP.
                val port = if (s.isTcp) s.remotePort else 443
                tcpPing(s.remoteHost, port)
            }
        }.awaitAll()
    }

    private fun tcpPing(host: String, port: Int): Int = try {
        val start = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(host, port), PING_TIMEOUT_MS) }
        ((System.nanoTime() - start) / 1_000_000).toInt()
    } catch (e: Exception) {
        -1
    }

    // ------------------------------------------------------------ memoria de fallas y éxitos

    fun recordFailure(context: Context, server: VpnServer) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val count = prefs.getInt("fail_count_${server.ip}", 0) + 1
        prefs.edit()
            .putInt("fail_count_${server.ip}", count)
            .putLong("fail_time_${server.ip}", System.currentTimeMillis())
            .apply()
    }

    fun recordSuccess(context: Context, server: VpnServer) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("fail_count_${server.ip}")
            .remove("fail_time_${server.ip}")
            .putString(KEY_LAST_GOOD_IP, server.ip)
            .putString(KEY_LAST_GOOD_CONFIG, server.ovpnConfig)
            .putString(KEY_LAST_GOOD_HOST, server.hostName)
            .putString(KEY_LAST_GOOD_CC, server.countryCode)
            .putString(KEY_LAST_GOOD_COUNTRY, server.country)
            .apply()
    }

    private fun isPenalized(context: Context, ip: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt("fail_count_$ip", 0) < FAILS_FOR_PENALTY) return false
        val since = System.currentTimeMillis() - prefs.getLong("fail_time_$ip", 0)
        return since < PENALTY_MS
    }

    /** El último servidor que anduvo, para usarlo si no se puede bajar ninguna lista. */
    fun lastGoodServer(context: Context): VpnServer? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val config = prefs.getString(KEY_LAST_GOOD_CONFIG, null) ?: return null
        return VpnServer(
            hostName = prefs.getString(KEY_LAST_GOOD_HOST, "") ?: "",
            ip = prefs.getString(KEY_LAST_GOOD_IP, "") ?: "",
            score = 0, pingMs = 0, speedBps = 1, sessions = 0,
            country = prefs.getString(KEY_LAST_GOOD_COUNTRY, "") ?: "",
            countryCode = prefs.getString(KEY_LAST_GOOD_CC, "") ?: "",
            ovpnConfig = VpnGateClient.adapt(config)   // por si se guardó con una versión vieja
        )
    }

    /** Ping a un solo servidor (para el último bueno cuando no hay lista). */
    suspend fun ping(server: VpnServer): Int = withContext(Dispatchers.IO) {
        tcpPing(server.remoteHost, if (server.isTcp) server.remotePort else 443)
    }
}
