package app.agregateur.core.storage

import app.agregateur.core.addon.AddonJson
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer

/**
 * Valeur enregistrée dans un fichier JSON. Écriture par fichier temporaire puis renommage ;
 * un fichier illisible est mis de côté (`.bak`) et remplacé par la valeur par défaut.
 */
class JsonFile<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val default: () -> T,
) {
    suspend fun load(): T = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext default()
        try {
            AddonJson.decodeFromString(serializer, file.readText())
        } catch (_: SerializationException) {
            Files.move(file.toPath(), File(file.path + ".bak").toPath(), StandardCopyOption.REPLACE_EXISTING)
            default()
        }
    }

    @Throws(IOException::class)
    suspend fun save(value: T) {
        withContext(Dispatchers.IO) {
            file.absoluteFile.parentFile?.mkdirs()
            val temp = File(file.path + ".tmp")
            try {
                temp.writeText(AddonJson.encodeToString(serializer, value))
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: IOException) {
                temp.delete()
                throw e
            }
        }
    }
}

fun <T> jsonListFile(file: File, element: KSerializer<T>) = JsonFile(file, ListSerializer(element)) { emptyList() }
