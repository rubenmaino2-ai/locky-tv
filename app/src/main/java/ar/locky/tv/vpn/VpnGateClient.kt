package ar.locky.tv.vpn

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Un servidor público de VPN Gate (red de voluntarios de la Univ. de Tsukuba, Japón). */
data class VpnServer(
    val hostName: String,
    val ip: String,
    val score: Long,
    val pingMs: Int,          // ping medido por VPN Gate (desde Japón, no desde tu casa)
    val speedBps: Long,       // velocidad de la línea del voluntario
    val country: String,
    val countryCode: String,
    val sessions: Int,        // cuánta gente lo está usando ahora
    val ovpnConfig: String
) {
    /** País en castellano ("JP" -> "Japón"); si Android no lo conoce, el nombre en inglés. */
    val countryEs: String
        get() = Locale("", countryCode).getDisplayCountry(Locale("es", "AR"))
            .takeIf { it.isNotBlank() && it != countryCode } ?: country

    val speedMbps: Double get() = speedBps / 1_000_000.0

    /** Dirección y puerto a los que se conecta la VPN (línea "remote" del .ovpn). */
    val remoteHost: String
        get() = remoteLine()?.getOrNull(1) ?: ip
    val remotePort: Int
        get() = remoteLine()?.getOrNull(2)?.toIntOrNull() ?: 443
    val isTcp: Boolean
        get() = Regex("(?m)^\\s*proto\\s+tcp").containsMatchIn(ovpnConfig)

    private fun remoteLine(): List<String>? =
        ovpnConfig.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("remote ") }
            ?.split(Regex("\\s+"))
}

/** Resultado de bajar la lista: los servidores y si vinieron de la copia guardada. */
data class ServerList(val servers: List<VpnServer>, val fromCache: Boolean)

/**
 * Descarga la lista de servidores de VPN Gate.
 *
 * La lista es un CSV donde cada fila es un servidor y la última columna
 * trae el archivo .ovpn completo codificado en Base64.
 * Cada vez que la baja bien, guarda una copia: si otro día vpngate.net no
 * responde, Locky usa esa copia (muchos servidores siguen vivos por días).
 */
object VpnGateClient {

    private val SOURCES = listOf(
        "https://www.vpngate.net/api/iphone/",
        "http://www.vpngate.net/api/iphone/"   // respaldo si falla HTTPS
    )
    private const val CACHE_MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000   // 3 días

    suspend fun fetchServers(cacheFile: File): ServerList = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        for (source in SOURCES) {
            try {
                val csv = download(source)
                val servers = parse(csv)
                if (servers.isNotEmpty()) {
                    try { cacheFile.writeText(csv) } catch (e: Exception) { }
                    return@withContext ServerList(servers, fromCache = false)
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        // Plan B: la última lista que bajamos bien.
        if (cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < CACHE_MAX_AGE_MS) {
            val cached = parse(cacheFile.readText())
            if (cached.isNotEmpty()) return@withContext ServerList(cached, fromCache = true)
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

            val speed = f[4].toLongOrNull() ?: 0L
            if (speed <= 0) continue

            result += VpnServer(
                hostName = f[0],
                ip = f[1],
                score = f[2].toLongOrNull() ?: 0L,
                pingMs = f[3].toIntOrNull() ?: 9999,
                speedBps = speed,
                country = f[5],
                countryCode = f[6],
                sessions = f[7].toIntOrNull() ?: 0,
                ovpnConfig = adapt(config)
            )
        }
        return result
    }

    /**
     * Los servidores de VPN Gate usan cifrados viejos (AES-128-CBC).
     * Las versiones nuevas de OpenVPN los rechazan si no se los habilita a mano.
     */
    private fun adapt(config: String): String {
        if (config.contains("# --- Agregado por Locky")) return config
        val clean = config.replace("\r\n", "\n").trimEnd()
        return clean + "\n\n# --- Agregado por Locky (compatibilidad con OpenVPN 2.6) ---\n" +
            "data-ciphers AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305:AES-128-CBC:AES-256-CBC\n" +
            "data-ciphers-fallback AES-128-CBC\n"
    }
}
