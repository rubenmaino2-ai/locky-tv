package ar.locky.tv

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import de.blinkt.openvpn.core.ICSOpenVPNApplication
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * La "aplicación" de Locky: es la del motor de VPN (ICSOpenVPNApplication, que prepara
 * canales de notificación y el puente de estados) más una caja negra.
 *
 * Caja negra: si cualquier proceso de Locky se cae (la pantalla o el de la VPN, ":openvpn"),
 * guarda el error completo en un archivo antes de cerrarse. "Ver detalles" lo muestra.
 */
class LockyApplication : ICSOpenVPNApplication() {

    companion object {
        private const val CRASH_FILE = "ultimo-error.txt"

        /** El último error guardado por la caja negra (o null si no hubo). */
        fun lastCrash(context: Context): String? {
            val file = File(context.filesDir, CRASH_FILE)
            return if (file.exists()) file.readText() else null
        }

        fun clearCrash(context: Context) {
            File(context.filesDir, CRASH_FILE).delete()
        }
    }

    override fun onCreate() {
        installBlackBox()
        super.onCreate()
    }

    private fun installBlackBox() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val process = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else "?"
                val time = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(Date())
                val text = "== Error en $process (hilo ${thread.name}) · $time · Locky ${BuildConfig.VERSION_NAME} ==\n" +
                    Log.getStackTraceString(error)
                // Recortado: con las primeras líneas alcanza para saber qué pasó.
                File(filesDir, CRASH_FILE).writeText(text.take(6_000))
            } catch (e: Throwable) {
                // La caja negra nunca tiene que empeorar las cosas.
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
