package dev.otgformat.harness

/**
 * The geometry `fsck.vfat` reports after parsing a boot sector.
 *
 * Exit code 0 only proves nothing was *inconsistent*. Re-reading the geometry
 * that an independent implementation derived from our BPB, and comparing it to
 * what we intended to write, proves the fields actually say what we meant —
 * which is the failure mode that matters, since a boot sector can be
 * self-consistent and still describe the wrong volume.
 */
data class FsckReport(
    val bytesPerSector: Int,
    val bytesPerCluster: Int,
    val reservedSectors: Int,
    val firstFatSector: Long,
    val numFats: Int,
    val fatBits: Int,
    val fatSizeSectors: Long,
    val rootCluster: Long,
    val dataStartSector: Long,
    val dataClusters: Long,
    val hiddenSectors: Long,
    val totalSectors: Long,
    val raw: String,
) {
    companion object {
        fun parse(output: String): FsckReport {
            fun grab(pattern: String, group: Int = 1): String =
                Regex(pattern).find(output)?.groupValues?.get(group)
                    ?: throw AssertionError("fsck output did not contain /$pattern/:\n$output")

            return FsckReport(
                bytesPerSector = grab("""(\d+) bytes per logical sector""").toInt(),
                bytesPerCluster = grab("""(\d+) bytes per cluster""").toInt(),
                reservedSectors = grab("""(\d+) reserved sectors""").toInt(),
                firstFatSector = grab("""First FAT starts at byte \d+ \(sector (\d+)\)""").toLong(),
                numFats = grab("""(\d+) FATs, (\d+) bit entries""").toInt(),
                fatBits = grab("""(\d+) FATs, (\d+) bit entries""", 2).toInt(),
                fatSizeSectors = grab("""bytes per FAT \(= (\d+) sectors\)""").toLong(),
                rootCluster = grab("""Root directory start at cluster (\d+)""").toLong(),
                dataStartSector = grab("""Data area starts at byte \d+ \(sector (\d+)\)""").toLong(),
                dataClusters = grab("""(\d+) data clusters""").toLong(),
                hiddenSectors = grab("""(\d+) hidden sectors""").toLong(),
                totalSectors = grab("""(\d+) sectors total""").toLong(),
                raw = output,
            )
        }
    }
}
