package ar.locky.tv.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mide la velocidad real de bajada a través de la VPN:
 * descarga un archivo de prueba de Cloudflare durante unos segundos.
 */
object SpeedTest {

    private const val TEST_URL = "https://speed.cloudflare.com/__down?bytes=25000000"
    private const val MAX_SECONDS = 5.0

    /** Megabits por segundo, o null si no se pudo medir. */
    suspend fun measureMbps(): Double? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(TEST_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("User-Agent", "Locky-TV")
            val input = conn.inputStream
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            val start = System.nanoTime()
            var elapsed = 0.0
            while (elapsed < MAX_SECONDS) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                elapsed = (System.nanoTime() - start) / 1e9
            }
            input.close()
            if (elapsed < 0.3 || total == 0L) null else total * 8 / elapsed / 1_000_000
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Texto amigable: "18 Mbps · ideal para HD". */
    fun describe(mbps: Double): String {
        val value = if (mbps < 10) String.format("%.1f", mbps) else mbps.toInt().toString()
        val verdict = when {
            mbps >= 15 -> "ideal para HD"
            mbps >= 8 -> "bien para HD"
            mbps >= 4 -> "alcanza para calidad media"
            else -> "lenta, probá cambiar de servidor"
        }
        return "$value Mbps · $verdict"
    }
}
