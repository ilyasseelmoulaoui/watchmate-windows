package app.agregateur.core.storage

import java.io.IOException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Enregistre puis publie le nouvel état, sans interruption possible entre les deux : un appelant annulé
 * (écran quitté pendant l'écriture) laisserait sinon un fichier à jour et un état en mémoire périmé.
 * Renvoie faux si l'enregistrement échoue ; l'état n'est alors pas modifié.
 */
suspend fun saveThenPublish(block: suspend () -> Unit): Boolean = withContext(NonCancellable) {
    try {
        block()
        true
    } catch (_: IOException) {
        false
    }
}
