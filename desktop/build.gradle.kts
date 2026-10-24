import java.net.URI
import java.security.MessageDigest
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(project(":core"))
    implementation(project(":relay"))
    implementation(libs.coroutines.swing)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.vlcj)
    implementation(libs.libtorrent4j)
    implementation(libs.libtorrent4j.windows)
    implementation(libs.webrtc.java)
    implementation(variantOf(libs.webrtc.java) { classifier("windows-x86_64") })
}

/**
 * libVLC n'est pas installé chez tout le monde : on embarque la version portable officielle (libvlc, libvlccore,
 * plugins) dans les ressources de l'application. Le lecteur cherche d'abord là, puis un VLC 64 bits installé.
 */
val vlcVersion = "3.0.21"
val vlcSha256 = "a0b7ec02b50adf6417eed014fb8df50af39690505a4225b85b3dc2ed17d14843"
val appResources = layout.projectDirectory.dir("resources")
val vlcZip = layout.buildDirectory.file("vlc/vlc-$vlcVersion-win64.zip")

val downloadVlc by tasks.registering {
    outputs.file(vlcZip)
    doLast {
        val target = vlcZip.get().asFile
        if (target.exists() && sha256(target) == vlcSha256) return@doLast
        target.parentFile.mkdirs()
        val url = "https://download.videolan.org/pub/videolan/vlc/$vlcVersion/win64/vlc-$vlcVersion-win64.zip"
        URI(url).toURL().openStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        check(sha256(target) == vlcSha256) { "Empreinte inattendue pour $url" }
    }
}

val fetchVlc by tasks.registering(Sync::class) {
    dependsOn(downloadVlc)
    from(zipTree(vlcZip)) {
        include("vlc-$vlcVersion/libvlc.dll", "vlc-$vlcVersion/libvlccore.dll", "vlc-$vlcVersion/plugins/**")
        eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
        includeEmptyDirs = false
    }
    into(appResources.dir("windows/vlc"))
}

fun sha256(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

tasks.matching { it.name == "prepareAppResources" || it.name == "run" }.configureEach { dependsOn(fetchVlc) }

compose.desktop {
    application {
        mainClass = "app.agregateur.MainKt"
        jvmArgs += listOf("-Xmx1g", "-Dsun.java2d.d3d=true")
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "WatchMate"
            packageVersion = "0.2.0"
            description = "WatchMate for Windows"
            copyright = "WatchMate"
            vendor = "WatchMate"
            appResourcesRootDir.set(appResources)
            modules("java.naming", "java.net.http", "java.sql", "jdk.unsupported", "jdk.crypto.ec")
            windows {
                menuGroup = "WatchMate"
                upgradeUuid = "a7c3e9f1-2b4d-4e6a-8c0f-1d3e5f7a9b2c"
            }
        }
    }
}
