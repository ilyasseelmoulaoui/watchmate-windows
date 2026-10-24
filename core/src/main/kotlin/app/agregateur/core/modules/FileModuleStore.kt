package app.agregateur.core.modules

import app.agregateur.core.storage.jsonListFile
import java.io.File

class FileModuleStore(file: File) : ModuleStore {
    private val json = jsonListFile(file, InstalledModule.serializer())

    override suspend fun load(): List<InstalledModule> = json.load()

    override suspend fun save(modules: List<InstalledModule>) = json.save(modules)
}
