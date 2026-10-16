package ar.locky.tv.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import ar.locky.tv.vpn.OpenVpnEngine

data class TvApp(
    val packageName: String,
    val label: String,
    val image: Drawable,
    val isBanner: Boolean   // los TV tienen "banners" horizontales; si no hay, usamos el ícono
)

/** Lista las apps instaladas en el TV y las abre. */
object TvApps {

    private val HIDDEN = setOf(OpenVpnEngine.PACKAGE)

    fun load(context: Context, lastUsed: String?): List<TvApp> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, TvApp>()

        // Primero las apps "de TV" y después las comunes, sin repetir.
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(query, 0)) {
                val pkg = info.activityInfo.packageName
                if (pkg == context.packageName || pkg in HIDDEN || found.containsKey(pkg)) continue
                val banner: Drawable? = info.activityInfo.loadBanner(pm)
                    ?: info.activityInfo.applicationInfo.loadBanner(pm)
                found[pkg] = TvApp(
                    packageName = pkg,
                    label = info.loadLabel(pm).toString(),
                    image = banner ?: info.loadIcon(pm),
                    isBanner = banner != null
                )
            }
        }

        // La última app usada va primera; el resto en orden alfabético.
        return found.values.sortedWith(
            compareBy<TvApp>({ it.packageName != lastUsed }, { it.label.lowercase() })
        )
    }

    /** Nombre visible de una app ("Netflix"); si no se encuentra, el paquete. */
    fun labelOf(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: Exception) {
        packageName
    }

    fun launch(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: pm.getLaunchIntentForPackage(packageName)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
