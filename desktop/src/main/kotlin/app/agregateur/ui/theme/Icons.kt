package app.agregateur.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Icônes maison : grille 24, trait 1,75, extrémités rondes, aucun remplissage (sauf lecture). */
object WmIcons {
    val Home = icon("Home", "M4 11 12 4l8 7v9h-5v-6H9v6H4z")
    val Explore = icon("Explore", "M20 12a8 8 0 1 1-16 0a8 8 0 1 1 16 0z", "m15 9-2 4-4 2 2-4z")
    val Library = icon("Library", "M5 4v16M10 4v16M15 5l4 15")
    val Modules = icon(
        "Modules",
        "M5.5 4h4a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1-1.5 1.5h-4A1.5 1.5 0 0 1 4 9.5v-4A1.5 1.5 0 0 1 5.5 4z",
        "M14.5 13h4a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1-1.5 1.5h-4a1.5 1.5 0 0 1-1.5-1.5v-4a1.5 1.5 0 0 1 1.5-1.5z",
        "M5.5 13h4a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1-1.5 1.5h-4A1.5 1.5 0 0 1 4 18.5v-4A1.5 1.5 0 0 1 5.5 13z",
        "M16.5 4v6M13.5 7h6",
    )
    val Settings = icon(
        "Settings",
        "M4 7h10M18 7h2M4 17h4M12 17h8",
        "M18 7a2 2 0 1 1-4 0a2 2 0 1 1 4 0z",
        "M12 17a2 2 0 1 1-4 0a2 2 0 1 1 4 0z",
    )
    val Search = icon("Search", "M17.5 11a6.5 6.5 0 1 1-13 0a6.5 6.5 0 1 1 13 0z", "m16 16 4 4")
    val Back = icon("Back", "M19 12H5M11 6l-6 6 6 6", stroke = 2f)
    val Add = icon("Add", "M12 5v14M5 12h14")
    val Check = icon("Check", "m5 12.5 4.5 4.5L19 7.5", stroke = 2.25f)
    val Clear = icon("Clear", "M6 6l12 12M18 6 6 18")
    val ChevronDown = icon("ChevronDown", "m6 9 6 6 6-6", stroke = 2f)
    val Pulse = icon("Pulse", "M3 12h4l2-4 3 8 2-4h7")
    val Play = icon("Play", "M7 5v14l11-7z")
    val PlayFilled = filled("PlayFilled", "M7 5v14l11-7z")
    val Copy = icon(
        "Copy",
        "M10 8h7a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2h-7a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z",
        "M5 15V6a1 1 0 0 1 1-1h9",
    )
    val Catalog = icon(
        "Catalog",
        "M4 5h5a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z",
        "M15 5h5a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z",
    )
    val Sheet = icon("Sheet", "M6 4h9l4 4v12H6z", "M9 12h7M9 16h5")
    val Subtitles = icon(
        "Subtitles",
        "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z",
        "M7 12.5h4M13.5 12.5h3.5M7 15.5h7M16.5 15.5h.5",
    )
    val PauseFilled = filled("PauseFilled", "M7 5h3.5v14H7zM13.5 5H17v14h-3.5z")
    val Rewind = icon("Rewind", "M4 12a8 8 0 1 0 2.34-5.66", "M6.3 2.6v3.8h3.8")
    val Forward = icon("Forward", "M20 12a8 8 0 1 1-2.34-5.66", "M17.7 2.6v3.8h-3.8")
    val Audio = icon("Audio", "M4 9.5h3.5L12 5.5v13l-4.5-4H4z", "M15.5 9a4 4 0 0 1 0 6", "M18 6.5a7.5 7.5 0 0 1 0 11")
    val Mute = icon("Mute", "M4 9.5h3.5L12 5.5v13l-4.5-4H4z", "M16 9.5l5 5M21 9.5l-5 5")
    val Speed = icon("Speed", "M4.5 17a8 8 0 1 1 15 0", "m12 13 4-4")
    val Fit = icon("Fit", "M4 9V5h4M20 9V5h-4M4 15v4h4M20 15v4h-4")
    val Fullscreen = icon("Fullscreen", "M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5")
    val ExitFullscreen = icon("ExitFullscreen", "M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5")
    val Lock = icon(
        "Lock",
        "M7 11h10a1 1 0 0 1 1 1v7a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1v-7a1 1 0 0 1 1-1z",
        "M8.5 11V8a3.5 3.5 0 0 1 7 0v3",
    )
    val Unlock = icon(
        "Unlock",
        "M7 11h10a1 1 0 0 1 1 1v7a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1v-7a1 1 0 0 1 1-1z",
        "M8.5 11V8a3.5 3.5 0 0 1 6.8-1.2",
    )
    val Pip = icon(
        "Pip",
        "M4 5h16a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z",
        "M12.5 12.5h5.5v4h-5.5z",
    )
    val Chat = icon(
        "Chat",
        "M5 5h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-8l-4.5 3.5V17H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z",
        "M8 10h8M8 13h5",
    )
    val Send = icon("Send", "M4 12 20 4l-6 16-3-7z", "M11 13l9-9")
    val Mic = icon(
        "Mic",
        "M12 3.5a3 3 0 0 1 3 3V12a3 3 0 0 1-6 0V6.5a3 3 0 0 1 3-3z",
        "M5.5 11.5a6.5 6.5 0 0 0 13 0",
        "M12 18v2.5",
    )
    val MicOff = icon(
        "MicOff",
        "M15 10V6.5a3 3 0 0 0-5.8-1.1",
        "M9 9v3a3 3 0 0 0 4.6 2.5",
        "M5.5 11.5a6.5 6.5 0 0 0 10.4 5.2M18.5 11.5a6.4 6.4 0 0 1-.6 2.7",
        "M12 18v2.5",
        "M4 4l16 16",
    )
    val People = icon(
        "People",
        "M12 9a3 3 0 1 1-6 0a3 3 0 1 1 6 0z",
        "M3.5 19c0-3 2.5-5 5.5-5s5.5 2 5.5 5",
        "M15.5 6.3a3 3 0 0 1 0 5.4",
        "M17.5 14.2c2 .5 3 2.3 3 4.8",
    )
    val External = icon("External", "M14 4h6v6", "M20 4l-9 9", "M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5")
}

private fun icon(name: String, vararg paths: String, stroke: Float = 1.75f): ImageVector {
    val builder = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
    paths.forEach { data ->
        builder.addPath(
            pathData = PathParser().parsePathString(data).toNodes(),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = stroke,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return builder.build()
}

private fun filled(name: String, path: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(pathData = PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
        .build()
