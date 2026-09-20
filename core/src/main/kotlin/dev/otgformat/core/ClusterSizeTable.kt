package dev.otgformat.core

/**
 * Cluster size selection for FAT32.
 *
 * The starting point is Microsoft's standard table, by volume size:
 *
 * | Volume size   | Sectors/cluster | Cluster |
 * |---------------|-----------------|---------|
 * | up to 8 GiB   | 8               | 4 KiB   |
 * | 8 – 16 GiB    | 16              | 8 KiB   |
 * | 16 – 32 GiB   | 32              | 16 KiB  |
 * | over 32 GiB   | 64              | 32 KiB  |
 *
 * That table alone is not sufficient, and this is worth spelling out because
 * it looks like a deviation.
 *
 * A FAT32 volume must contain at least 65525 clusters, or it is by definition
 * FAT16 and every checker will say so. The table's smallest entry is 4 KiB
 * clusters, which needs 65525 * 4 KiB ≈ 256 MiB of data area before the volume
 * is legal. So the table as written cannot format anything between its own
 * stated 32 MiB floor and roughly 256 MiB — a 64 MiB stick would be rejected.
 *
 * The 32 MiB floor in Microsoft's table is really the floor for *512-byte*
 * clusters (65525 * 512 ≈ 32 MiB). So below 256 MiB the correct behaviour is to
 * step the cluster size back down rather than to refuse. [forVolume] starts at
 * the table value and halves until the volume is legal, which reproduces the
 * table exactly everywhere the table applies and extends it sensibly below.
 *
 * If even 512-byte clusters cannot reach 65525 clusters, the volume genuinely
 * is too small for FAT32 and [Fat32Layout.compute] rejects it.
 */
object ClusterSizeTable {

    const val MIN_FAT32_CLUSTERS = 65525L

    /** FAT32 cannot address more than this many clusters. */
    const val MAX_FAT32_CLUSTERS = 0x0FFF_FFF5L - 1

    private const val GIB = 1024L * 1024L * 1024L

    /** Microsoft's table, before any small-volume correction. */
    fun standardTable(volumeBytes: Long): Int = when {
        volumeBytes <= 8 * GIB -> 8
        volumeBytes <= 16 * GIB -> 16
        volumeBytes <= 32 * GIB -> 32
        else -> 64
    }

    /**
     * Sectors per cluster for a volume of [totalSectors], or null if no cluster
     * size at all yields a legal FAT32 volume.
     */
    fun forVolume(totalSectors: Long, bytesPerSector: Int, numFats: Int, reservedSectors: Int): Int? {
        var spc = standardTable(totalSectors * bytesPerSector)
        while (spc >= 1) {
            val clusters = Fat32Layout.clusterCountFor(totalSectors, spc, bytesPerSector, numFats, reservedSectors)
            if (clusters >= MIN_FAT32_CLUSTERS && clusters <= MAX_FAT32_CLUSTERS) return spc
            spc /= 2
        }
        return null
    }
}
