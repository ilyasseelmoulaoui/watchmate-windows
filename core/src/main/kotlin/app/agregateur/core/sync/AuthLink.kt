package app.agregateur.core.sync

import java.net.URLDecoder

/** Ce que Supabase met dans le fragment du lien `watchmate://auth#…` ouvert depuis un e-mail. */
sealed interface AuthLink {
    /** `type` : « signup » (confirmation d'adresse) ou « recovery » (réinitialisation du mot de passe). */
    data class Tokens(val type: String, val accessToken: String, val refreshToken: String, val expiresAt: Long) : AuthLink

    data class Error(val code: String?, val description: String?) : AuthLink
}

fun parseAuthFragment(fragment: String?, nowSeconds: Long = System.currentTimeMillis() / 1000): AuthLink? {
    if (fragment.isNullOrBlank()) return null
    val params = fragment.split('&').mapNotNull { part ->
        val eq = part.indexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
        part.substring(0, eq) to runCatching { URLDecoder.decode(part.substring(eq + 1), "UTF-8") }.getOrDefault("")
    }.toMap()
    params["error"]?.let { return AuthLink.Error(params["error_code"] ?: it, params["error_description"]) }
    val access = params["access_token"]?.takeIf { it.isNotBlank() } ?: return null
    val refresh = params["refresh_token"]?.takeIf { it.isNotBlank() } ?: return null
    val expiresAt = params["expires_at"]?.toLongOrNull() ?: (nowSeconds + (params["expires_in"]?.toLongOrNull() ?: 3600))
    return AuthLink.Tokens(params["type"].orEmpty(), access, refresh, expiresAt)
}
