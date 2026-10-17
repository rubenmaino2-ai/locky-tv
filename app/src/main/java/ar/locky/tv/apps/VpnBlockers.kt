package ar.locky.tv.apps

/**
 * Apps que suelen detectar y bloquear las VPN (sobre todo las gratuitas y públicas
 * como VPN Gate): no reproducen, muestran un error o muestran menos contenido.
 * Si elegís una de estas, Locky avisa y te deja abrirla con o sin VPN.
 *
 * Para agregar una app: sumar su nombre de paquete (lo ves en Ajustes > Apps del TV
 * o en la dirección de Google Play: ...details?id=ESTE.ES.EL.PAQUETE).
 */
object VpnBlockers {

    private val KNOWN = mapOf(
        // Streaming
        "com.netflix.ninja" to "Netflix",                       // Netflix para TV
        "com.netflix.mediaclient" to "Netflix",
        "com.disney.disneyplus" to "Disney+",
        "com.amazon.amazonvideo.livingroom" to "Prime Video",   // Prime Video para TV
        "com.amazon.avod.thirdpartyclient" to "Prime Video",
        "com.wbd.stream" to "Max",
        "com.hbo.hbonow" to "Max",
        "com.cbs.ott" to "Paramount+",                          // Paramount+ para TV
        "com.cbs.app" to "Paramount+",
        "com.apple.atve.androidtv.appletv" to "Apple TV",
        // Pagos
        "com.mercadopago.wallet" to "Mercado Pago",
    )

    /** Palabras que delatan apps de bancos y billeteras. */
    private val KEYWORDS = listOf("bank", "banco", "banca", "wallet", "billetera")

    /** true si esta app suele bloquear las VPN. */
    fun isLikelyBlocker(packageName: String, label: String): Boolean {
        if (packageName in KNOWN) return true
        val text = "$packageName $label".lowercase()
        return KEYWORDS.any { it in text }
    }
}
