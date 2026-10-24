package app.agregateur.core.addon

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Règles sur les adresses venant des modules : ce sont des données non fiables (SPEC §7). */
object UrlPolicy {
    private val localHosts = setOf("127.0.0.1", "localhost", "::1")

    /** Adresse http(s) valide, chiffrée ou locale à l'appareil. */
    fun isSecureOrLocal(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.isHttps || parsed.host in localHosts
    }

    /** Adresse http(s) valide, quelle que soit la protection (pour ouvrir un lien dans un navigateur). */
    fun isWebUrl(url: String): Boolean = url.toHttpUrlOrNull() != null
}
