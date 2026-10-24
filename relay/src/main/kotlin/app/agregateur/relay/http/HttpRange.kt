package app.agregateur.relay.http

sealed interface RangeSpec {
    data object Full : RangeSpec
    data class Partial(val start: Long, val endInclusive: Long) : RangeSpec {
        val length: Long get() = endInclusive - start + 1
    }
    data object Unsatisfiable : RangeSpec
}

/**
 * En-tête `Range` d'un lecteur. Une demande de plusieurs plages, ou mal formée, est servie en entier (RFC 9110 le permet).
 */
fun parseRange(header: String?, size: Long): RangeSpec {
    val value = header?.trim()?.takeIf { it.startsWith("bytes=", ignoreCase = true) } ?: return RangeSpec.Full
    val spec = value.substring(6).trim()
    if (',' in spec || size <= 0) return RangeSpec.Full
    val dash = spec.indexOf('-').takeIf { it >= 0 } ?: return RangeSpec.Full
    val first = spec.substring(0, dash).trim()
    val last = spec.substring(dash + 1).trim()
    return when {
        first.isEmpty() -> {
            val suffix = last.toLongOrNull()?.takeIf { it >= 0 } ?: return RangeSpec.Full
            if (suffix == 0L) RangeSpec.Unsatisfiable else RangeSpec.Partial(maxOf(0, size - suffix), size - 1)
        }
        else -> {
            val start = first.toLongOrNull()?.takeIf { it >= 0 } ?: return RangeSpec.Full
            val end = if (last.isEmpty()) size - 1 else last.toLongOrNull() ?: return RangeSpec.Full
            when {
                start >= size -> RangeSpec.Unsatisfiable
                end < start -> RangeSpec.Full
                else -> RangeSpec.Partial(start, minOf(end, size - 1))
            }
        }
    }
}
