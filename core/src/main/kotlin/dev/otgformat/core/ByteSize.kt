package dev.otgformat.core

import java.util.Locale

/**
 * Formats a byte count for display.
 *
 * Exists because `bytes / 1024` renders a 512-byte cluster as "0 KiB", which
 * appeared in the warning shown on the confirmation screen — the one place a
 * user is being asked to trust the numbers.
 *
 * Exact multiples print as whole numbers, so power-of-two quantities such as
 * cluster and FAT sizes read cleanly ("4 KiB", "32 KiB"). Anything else keeps
 * one decimal rather than falling back to a raw byte count, so a capacity reads
 * as "62.0 MiB" instead of "65019392 B".
 */
fun formatBytes(bytes: Long): String {
    val units = listOf(
        1024L * 1024 * 1024 * 1024 to "TiB",
        1024L * 1024 * 1024 to "GiB",
        1024L * 1024 to "MiB",
        1024L to "KiB",
    )
    for ((size, name) in units) {
        if (bytes >= size) {
            return if (bytes % size == 0L) "${bytes / size} $name"
            else String.format(Locale.ROOT, "%.1f %s", bytes.toDouble() / size, name)
        }
    }
    return "$bytes B"
}

fun formatBytes(bytes: Int): String = formatBytes(bytes.toLong())
