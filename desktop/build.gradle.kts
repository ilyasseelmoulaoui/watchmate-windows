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
    // libtorrent natif Windows + VLC : branchés avec le lecteur / relais (HANDOFF §3).
}

compose.desktop {
    application {
        mainClass = "app.agregateur.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "WatchMate"
            packageVersion = "0.1.0"
            description = "WatchMate for Windows"
            copyright = "WatchMate"
            vendor = "WatchMate"
            windows {
                menuGroup = "WatchMate"
                // Stable upgrade id for MSI upgrades (do not change once published).
                upgradeUuid = "a7c3e9f1-2b4d-4e6a-8c0f-1d3e5f7a9b2c"
            }
        }
    }
}
