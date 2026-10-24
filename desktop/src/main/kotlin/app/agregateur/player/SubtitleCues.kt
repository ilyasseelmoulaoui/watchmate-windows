package app.agregateur.player

import app.agregateur.core.playback.SubtitleFormat
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream

data class Cue(val startMs: Long, val endMs: Long, val text: String)

/** Sous-titres d'un module, triés par début ; [at] répond pour une position de lecture. */
class CueTrack(private val cues: List<Cue>) {
    val isEmpty: Boolean get() = cues.isEmpty()

    fun at(positionMs: Long): String? {
        var low = 0
        var high = cues.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (cues[mid].startMs <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (found < 0) return null
        // Des sous-titres qui se chevauchent s'affichent ensemble.
        val active = (maxOf(0, found - 8)..found).map(cues::get).filter { positionMs < it.endMs }
        return active.joinToString("\n") { it.text }.ifEmpty { null }
    }
}

object SubtitleParser {
    fun parse(bytes: ByteArray, format: SubtitleFormat): CueTrack {
        val text = decode(bytes)
        val cues = when {
            format == SubtitleFormat.SSA || text.contains("[Events]") -> ssa(text)
            format == SubtitleFormat.TTML || text.trimStart().startsWith("<?xml") || text.contains("<tt") -> ttml(text)
            else -> srtOrVtt(text)
        }
        return CueTrack(cues.filter { it.endMs > it.startMs && it.text.isNotBlank() }.sortedBy { it.startMs })
    }

    /** UTF-8 si le fichier en est, sinon Windows-1252 : la plupart des anciens .srt européens. */
    private fun decode(raw: ByteArray): String {
        val bytes = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(raw)).readBytes()
        } else {
            raw
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, Charsets.UTF_16LE).drop(1)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, Charsets.UTF_16BE).drop(1)
        val strict = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            strict.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
        return text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
    }

    private val timing = Regex("""([\d:.,]+)\s*-->\s*([\d:.,]+)""")

    private fun srtOrVtt(text: String): List<Cue> = text.split(Regex("\n\\s*\n")).mapNotNull { block ->
        val lines = block.trim('\n').lines()
        val index = lines.indexOfFirst { timing.containsMatchIn(it) }
        if (index < 0) return@mapNotNull null
        val match = timing.find(lines[index]) ?: return@mapNotNull null
        val start = time(match.groupValues[1]) ?: return@mapNotNull null
        val end = time(match.groupValues[2]) ?: return@mapNotNull null
        Cue(start, end, clean(lines.drop(index + 1).joinToString("\n")))
    }

    private fun ssa(text: String): List<Cue> {
        var fields = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        val cues = mutableListOf<Cue>()
        var inEvents = false
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("[") -> inEvents = trimmed.equals("[Events]", ignoreCase = true)
                !inEvents -> Unit
                trimmed.startsWith("Format:", ignoreCase = true) ->
                    fields = trimmed.substringAfter(':').split(',').map { it.trim().lowercase() }
                trimmed.startsWith("Dialogue:", ignoreCase = true) -> {
                    val parts = trimmed.substringAfter(':').split(',', limit = fields.size)
                    if (parts.size < fields.size) return@forEach
                    val start = time(parts[fields.indexOf("start")].trim()) ?: return@forEach
                    val end = time(parts[fields.indexOf("end")].trim()) ?: return@forEach
                    val body = parts[fields.indexOf("text").takeIf { it >= 0 } ?: parts.lastIndex]
                        .replace(Regex("""\{[^}]*\}"""), "")
                        .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
                    cues += Cue(start, end, clean(body))
                }
            }
        }
        return cues
    }

    private val paragraph = Regex("""<p\b([^>]*)>(.*?)</p>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val attribute = Regex("""(begin|end|dur)\s*=\s*"([^"]+)"""")

    private fun ttml(text: String): List<Cue> = paragraph.findAll(text).mapNotNull { match ->
        val attrs = attribute.findAll(match.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
        val start = attrs["begin"]?.let(::ttmlTime) ?: return@mapNotNull null
        val end = attrs["end"]?.let(::ttmlTime) ?: attrs["dur"]?.let(::ttmlTime)?.plus(start) ?: return@mapNotNull null
        val body = match.groupValues[2].replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
        Cue(start, end, clean(body))
    }.toList()

    private fun ttmlTime(value: String): Long? {
        val v = value.trim()
        return when {
            v.endsWith("ms") -> v.dropLast(2).toDoubleOrNull()?.toLong()
            v.endsWith("s") -> v.dropLast(1).toDoubleOrNull()?.let { (it * 1000).toLong() }
            v.endsWith("t") -> v.dropLast(1).toLongOrNull()?.let { it / 10_000 }
            else -> time(v)
        }
    }

    /** `h:mm:ss,mmm`, `mm:ss.mmm` (WebVTT) ou `h:mm:ss.cc` (SSA). */
    private fun time(value: String): Long? {
        val parts = value.replace(',', '.').split(':')
        if (parts.size !in 2..3) return null
        val seconds = parts.last().toDoubleOrNull() ?: return null
        val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        return hours * 3_600_000 + minutes * 60_000 + (seconds * 1000).toLong()
    }

    private fun clean(text: String): String = text
        .replace(Regex("""<[^>]+>"""), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&nbsp;", " ")
        .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
}
