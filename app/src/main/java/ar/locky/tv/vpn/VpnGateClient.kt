package ar.locky.tv.vpn

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Un servidor público de VPN Gate (red de voluntarios de la Univ. de Tsukuba, Japón). */
data class VpnServer(
    val hostName: String,
    val ip: String,
    val score: Long,
    val pingMs: Int,
    val speedBps: Long,
    val country: String,
    val countryCode: String,
    val sessions: Int,
    val ovpnConfig: String
) {
    /** País en castellano ("JP" -> "Japón"); si Android no lo conoce, el nombre en inglés. */
    val countryEs: String
        get() = Locale("", countryCode).getDisplayCountry(Locale("es", "AR"))
            .takeIf { it.isNotBlank() && it != countryCode } ?: country
}

/**
 * Descarga y ordena la lista de servidores de VPN Gate.
 *
 * La lista es un CSV donde cada fila es un servidor y la última columna
 * trae el archivo .ovpn completo codificado en Base64.
 */
object VpnGateClient {

    private val SOURCES = listOf(
        "https://www.vpngate.net/api/iphone/",
        "http://www.vpngate.net/api/iphone/"   // respaldo si falla HTTPS
    )

    suspend fun fetchServers(): List<VpnServer> = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        for (source in SOURCES) {
            try {
                val servers = parse(download(source))
                if (servers.isNotEmpty()) return@withContext rank(servers)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("La lista de servidores vino vacía")
    }

    private fun download(address: String): String {
        val conn = URL(address).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "Locky-TV")
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Columnas: HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,...,OpenVPN_ConfigData_Base64 */
    private fun parse(csv: String): List<VpnServer> {
        val result = ArrayList<VpnServer>()
        val needsPassword = Regex("(?m)^\\s*auth-user-pass")
        for (raw in csv.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("*") || line.startsWith("#")) continue
            val f = line.split(',')
            if (f.size < 15) continue

            val config = try {
                String(Base64.decode(f.last(), Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                continue
            }
            if (!config.contains("remote ")) continue
            if (needsPassword.containsMatchIn(config)) continue // no sabemos el usuario: lo salteamos

            result += VpnServer(
                hostName = f[0],
                ip = f[1],
                score = f[2].toLongOrNull() ?: 0L,
                pingMs = f[3].toIntOrNull() ?: 9999,
                speedBps = f[4].toLongOrNull() ?: 0L,
                country = f[5],
                countryCode = f[6],
                sessions = f[7].toIntOrNull() ?: 0,
                ovpnConfig = adapt(config)
            )
        }
        return result
    }

    /** El "score" de VPN Gate ya combina velocidad, ping y estabilidad: ordenamos por eso. */
    private fun rank(servers: List<VpnServer>): List<VpnServer> =
        servers.filter { it.speedBps > 0 }.sortedByDescending { it.score }

    /**
     * Los servidores de VPN Gate usan cifrados viejos (AES-128-CBC).
     * Las versiones nuevas de OpenVPN los rechazan si no se los habilita a mano.
     */
    private fun adapt(config: String): String {
        val clean = config.replace("\r\n", "\n").trimEnd()
        return clean + "\n\n# --- Agregado por Locky (compatibilidad con OpenVPN 2.6) ---\n" +
            "data-ciphers AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305:AES-128-CBC:AES-256-CBC\n" +
            "data-ciphers-fallback AES-128-CBC\n"
    }
}
