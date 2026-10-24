package app.agregateur.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.agregateur.DesktopApp
import app.agregateur.core.model.Manifest
import app.agregateur.core.modules.InstallError
import app.agregateur.core.modules.InstallResult
import app.agregateur.core.modules.InstalledModule
import app.agregateur.core.modules.Origin
import app.agregateur.core.modules.SUGGESTED_MODULES
import app.agregateur.core.modules.SuggestedModule
import app.agregateur.ui.ModuleTile
import app.agregateur.ui.MutedText
import app.agregateur.ui.NeonButton
import app.agregateur.ui.OutlineButton
import app.agregateur.ui.QuietButton
import app.agregateur.ui.ScreenTitle
import app.agregateur.ui.SectionTitle
import app.agregateur.ui.WmTextField
import app.agregateur.ui.addonErrorMessage
import app.agregateur.ui.theme.WmIcons
import app.agregateur.ui.theme.WmShape
import app.agregateur.ui.theme.WmType
import app.agregateur.ui.theme.wm
import app.agregateur.ui.wmClickable
import kotlinx.coroutines.launch

private sealed interface Selection {
    data class Installed(val manifestUrl: String) : Selection
    data class Suggested(val module: SuggestedModule) : Selection
}

@Composable
fun ModulesScreen(app: DesktopApp, french: Boolean) {
    val installed by app.modules.modules.collectAsState()
    var address by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Selection?>(null) }
    val scope = rememberCoroutineScope()

    val suggestions = remember(installed) {
        val urls = installed.map { it.manifestUrl }.toSet()
        SUGGESTED_MODULES.filter { it.manifestUrl !in urls }
    }
    val current = when (val s = selection) {
        is Selection.Installed -> s.takeIf { installed.any { it.manifestUrl == s.manifestUrl } }
        is Selection.Suggested -> s.takeIf { it.module in suggestions }
        null -> null
    } ?: installed.firstOrNull()?.let { Selection.Installed(it.manifestUrl) }
        ?: suggestions.firstOrNull()?.let { Selection.Suggested(it) }

    fun install(url: String, origin: Origin = Origin.ADDRESS) {
        busy = true
        message = null
        scope.launch {
            message = when (val result = app.modules.install(url, origin)) {
                is InstallResult.Installed -> {
                    selection = Selection.Installed(result.module.manifestUrl)
                    if (origin == Origin.ADDRESS) address = ""
                    if (french) "Module installé." else "Module installed."
                }
                is InstallResult.AlreadyInstalled -> {
                    selection = Selection.Installed(result.module.manifestUrl)
                    if (french) "Ce module est déjà installé." else "This module is already installed."
                }
                is InstallResult.Failed -> installErrorMessage(result.error, french)
            }
            busy = false
        }
    }

    Row(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.width(420.dp).fillMaxHeight(),
            contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScreenTitle("Modules")
                    MutedText(
                        if (french) {
                            "WatchMate n’a aucun contenu à lui. Il lit ce que vos modules proposent."
                        } else {
                            "WatchMate has no content of its own. It reads what your modules offer."
                        },
                    )
                }
            }
            item {
                Column(Modifier.padding(start = 32.dp, end = 32.dp, top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (french) "Installer par adresse" else "Install by address", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        WmTextField(
                            value = address,
                            onValueChange = { address = it },
                            placeholder = "https://…/manifest.json",
                            enabled = !busy,
                            onSubmit = { if (address.isNotBlank()) install(address) },
                            modifier = Modifier.weight(1f),
                        )
                        NeonButton(if (french) "Installer" else "Install", { install(address) }, enabled = !busy && address.isNotBlank())
                    }
                    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                }
            }
            if (installed.isNotEmpty()) {
                item { SectionTitle(if (french) "Installés" else "Installed", modifier = Modifier.padding(start = 32.dp, top = 24.dp, bottom = 6.dp)) }
                items(installed, key = { "i:" + it.manifestUrl }) { module ->
                    ModuleListItem(
                        name = module.manifest.name.ifBlank { module.manifestUrl },
                        seed = module.manifestUrl,
                        detail = capabilityNames(module.manifest, french).joinToString(" · "),
                        selected = current == Selection.Installed(module.manifestUrl),
                        onClick = { selection = Selection.Installed(module.manifestUrl) },
                    )
                }
            }
            if (suggestions.isNotEmpty()) {
                item { SectionTitle("Suggestions", modifier = Modifier.padding(start = 32.dp, top = 24.dp, bottom = 6.dp)) }
                items(suggestions, key = { "s:" + it.manifestUrl }) { module ->
                    ModuleListItem(
                        name = module.name,
                        seed = module.manifestUrl,
                        detail = module.description,
                        selected = current == Selection.Suggested(module),
                        onClick = { selection = Selection.Suggested(module) },
                    )
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (current) {
                is Selection.Installed -> installed.firstOrNull { it.manifestUrl == current.manifestUrl }?.let { module ->
                    InstalledDetail(module, french, busy) {
                        scope.launch {
                            if (!app.modules.remove(module.manifestUrl)) {
                                message = if (french) "Désinstallation impossible." else "Could not uninstall."
                            }
                        }
                    }
                }
                is Selection.Suggested -> SuggestedDetail(current.module, french, busy) {
                    install(current.module.manifestUrl, Origin.LIST)
                }
                null -> MutedText(
                    if (french) "Aucun module pour l’instant." else "No modules yet.",
                    Modifier.padding(48.dp),
                )
            }
        }
    }
}

@Composable
private fun ModuleListItem(name: String, seed: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.background(colors.surfaceVariant) else Modifier)
            .wmClickable(hover = MaterialTheme.wm.carte, onClick = onClick),
    ) {
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(32.dp)
                    .background(colors.primary, RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp)),
            )
        }
        Row(
            Modifier.padding(start = 32.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModuleTile(name, seed, size = 44.dp, radius = 12.dp)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail.isNotBlank()) {
                    Text(detail, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun DetailHeader(name: String, seed: String, subtitle: String?, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModuleTile(name, seed, size = 88.dp, radius = 20.dp, style = WmType.GeneriqueXl)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = WmType.Module, color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstalledDetail(module: InstalledModule, french: Boolean, busy: Boolean, onUninstall: () -> Unit) {
    val manifest = module.manifest
    val clipboard = LocalClipboardManager.current
    var confirm by remember(module.manifestUrl) { mutableStateOf(false) }
    var copied by remember(module.manifestUrl) { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        val name = manifest.name.ifBlank { module.manifestUrl }
        DetailHeader(
            name = name,
            seed = module.manifestUrl,
            subtitle = manifest.version.takeIf { it.isNotBlank() }?.let { if (french) "Version $it" else "Version $it" },
        ) {}
        if (manifest.description.isNotBlank()) {
            Text(
                manifest.description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.widthIn(max = 560.dp),
            )
        }
        val capabilities = capabilities(manifest, french)
        if (capabilities.isNotEmpty()) {
            FlowRow(
                Modifier.widthIn(max = 900.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                capabilities.forEach { CapabilityCard(it) }
            }
        }
        Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (french) "Adresse du manifeste" else "Manifest address", style = MaterialTheme.typography.titleSmall)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, WmShape.Field)
                    .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(module.manifestUrl, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                QuietButton(
                    if (copied) {
                        if (french) "Copiée" else "Copied"
                    } else {
                        if (french) "Copier l’adresse" else "Copy address"
                    },
                    {
                        clipboard.setText(AnnotatedString(module.manifestUrl))
                        copied = true
                    },
                    icon = if (copied) WmIcons.Check else WmIcons.Copy,
                )
            }
        }
        OutlineButton(if (french) "Désinstaller le module" else "Uninstall module", { confirm = true }, enabled = !busy)
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(if (french) "Désinstaller « ${manifest.name} » ?" else "Uninstall “${manifest.name}”?") },
            text = {
                Text(
                    if (french) {
                        "Ses catalogues et ses sources disparaissent de WatchMate. Vous pourrez le réinstaller avec la même adresse."
                    } else {
                        "Its catalogs and sources disappear from WatchMate. You can reinstall it with the same address."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onUninstall()
                }) { Text(if (french) "Désinstaller" else "Uninstall") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(if (french) "Annuler" else "Cancel") }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
private fun SuggestedDetail(module: SuggestedModule, french: Boolean, busy: Boolean, onInstall: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        DetailHeader(module.name, module.manifestUrl, if (french) "Suggestion · non installé" else "Suggestion · not installed") {}
        Text(module.description, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.widthIn(max = 560.dp))
        Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (french) "Adresse du manifeste" else "Manifest address", style = MaterialTheme.typography.titleSmall)
            Text(
                module.manifestUrl,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, WmShape.Field)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
        NeonButton(if (french) "Installer ce module" else "Install this module", onInstall, enabled = !busy)
    }
}

private data class Capability(val icon: ImageVector, val title: String, val detail: String)

@Composable
private fun CapabilityCard(capability: Capability) {
    Column(
        Modifier
            .width(260.dp)
            .border(1.dp, MaterialTheme.wm.logeHaute, WmShape.Field)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(capability.icon, null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(24.dp))
        Text(capability.title, style = MaterialTheme.typography.titleSmall)
        Text(capability.detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun Manifest.has(resource: String) = resources.any { it.name == resource }

private fun capabilityNames(manifest: Manifest, french: Boolean): List<String> = buildList {
    if (manifest.catalogs.isNotEmpty()) add(if (french) "Catalogue" else "Catalog")
    if (manifest.has("stream")) add("Sources")
    if (manifest.has("meta")) add(if (french) "Fiches" else "Details")
    if (manifest.has("subtitles")) add(if (french) "Sous-titres" else "Subtitles")
}

private fun capabilities(manifest: Manifest, french: Boolean): List<Capability> = buildList {
    if (manifest.catalogs.isNotEmpty()) {
        val names = manifest.catalogs.map { it.name.ifBlank { it.id } }.distinct()
        val shown = names.take(3).joinToString(", ") + if (names.size > 3) "…" else ""
        add(
            Capability(
                WmIcons.Catalog,
                if (french) "Catalogue" else "Catalog",
                if (french) "${names.size} rangée(s) : $shown" else "${names.size} row(s): $shown",
            ),
        )
    }
    if (manifest.has("stream")) {
        add(Capability(WmIcons.Play, if (french) "Sources de lecture" else "Playback sources", if (french) "Propose des sources pour les titres" else "Offers sources for titles"))
    }
    if (manifest.has("meta")) {
        add(Capability(WmIcons.Sheet, if (french) "Fiches" else "Details", if (french) "Résumé, épisodes, distribution" else "Synopsis, episodes, cast"))
    }
    if (manifest.has("subtitles")) {
        add(Capability(WmIcons.Subtitles, if (french) "Sous-titres" else "Subtitles", if (french) "Sous-titres pendant la lecture" else "Subtitles during playback"))
    }
}

private fun installErrorMessage(error: InstallError, french: Boolean): String = when (error) {
    InstallError.EmptyAddress -> if (french) "Saisissez une adresse." else "Enter an address."
    InstallError.Storage -> if (french) "Enregistrement impossible." else "Could not save."
    is InstallError.Addon -> addonErrorMessage(error.error, french)
}
