package dev.otgformat.core

/**
 * Writes a FAT32 filesystem into a partition.
 *
 * This is the only class in the project that converts between partition-relative
 * and absolute sector numbers, and it does so in exactly one place ([abs]).
 * Every other component either works purely in absolute sectors ([SectorDevice])
 * or purely in partition-relative ones ([Fat32Layout]).
 */
class Fat32Formatter(
    private val device: SectorDevice,
    private val layout: Fat32Layout,
    private val options: FormatOptions,
    private val progress: Progress = Progress.NONE,
    /**
     * Bytes per device write. Zeroing a FAT on a 128 GB stick is tens of
     * megabytes; issuing that one 512-byte USB transfer at a time would take
     * minutes of pure protocol overhead.
     */
    private val chunkBytes: Int = DEFAULT_CHUNK_BYTES,
) {
    private val bps = layout.bytesPerSector
    private val chunkSectors: Int = maxOf(1, chunkBytes / bps)

    private var done = 0L

    /** Sectors this format will write. Reported back to the caller as the work done. */
    val plannedWriteSectors: Long =
        layout.reservedSectors +                      // clearing the reserved area
            layout.numFats * layout.fatSizeSectors +  // zeroing both FATs
            layout.numFats +                          // seeding FAT[0..2] in each
            layout.sectorsPerCluster +                // clearing the root directory
            4L                                        // boot + FSInfo, and both backups

    /** Sectors read back by [verify]: boot, FSInfo, both backups, and each FAT's first sector. */
    private val verifyReadSectors: Long = 4L + layout.numFats

    /**
     * Denominator for progress reporting.
     *
     * Verification is part of what the user waits for, so it belongs in the
     * total. Counting only writes makes the bar run past 100% during the final
     * read-back, which reads as a bug in exactly the phase whose job is to
     * reassure.
     */
    val plannedProgressSectors: Long = plannedWriteSectors + verifyReadSectors

    private val volumeSerial: Int = options.volumeSerial ?: deriveSerial()

    /** Partition-relative to absolute. The one place this conversion happens. */
    private fun abs(relativeSector: Long): Long = layout.partitionStartLba + relativeSector

    fun format() {
        // Order is deliberate: the boot sectors go last.
        //
        // The reserved area is cleared first, so a format interrupted halfway
        // through leaves a zeroed boot sector — a device that reads as blank.
        // Writing the boot sector early would instead leave a device that
        // announces a perfectly valid filesystem on top of a half-written FAT,
        // which is the one failure mode a user cannot detect.
        clearReservedArea()
        zeroFats()
        seedFats()
        clearRootDirectory()
        writeBootSectors()
    }

    private fun clearReservedArea() {
        // Covers the stale backup boot sector at relative sector 6 that a
        // previous filesystem may have left behind.
        writeZeros(0, layout.reservedSectors.toLong(), Phase.RESERVED_AREA)
    }

    private fun zeroFats() {
        // Every cluster must read as free. This is the bulk of the write.
        writeZeros(layout.firstFatSector, layout.numFats * layout.fatSizeSectors, Phase.ZERO_FAT)
    }

    private fun seedFats() {
        val first = fatFirstSector()
        for (fat in 0 until layout.numFats) {
            checkCancelled()
            device.write(abs(layout.firstFatSector + fat * layout.fatSizeSectors), first)
            advance(1, Phase.INIT_FAT)
        }
    }

    /** The first sector of a FAT: entries 0, 1 and 2, then free space. */
    internal fun fatFirstSector(): ByteArray {
        val s = ByteArray(bps)
        s.putU32(0, FatEntries.ENTRY_0)
        s.putU32(4, FatEntries.ENTRY_1)
        s.putU32(8, FatEntries.ENTRY_2)
        return s
    }

    private fun clearRootDirectory() {
        val rootSector = layout.clusterToSector(Fat32Layout.ROOT_CLUSTER)
        val label = options.label

        if (label == null) {
            writeZeros(rootSector, layout.sectorsPerCluster.toLong(), Phase.ROOT_DIRECTORY)
            return
        }

        // First sector carries the volume-label entry, the rest of the cluster
        // is empty directory space.
        val first = ByteArray(bps)
        DirectoryEntry.volumeLabel(label).copyInto(first, 0)
        checkCancelled()
        device.write(abs(rootSector), first)
        advance(1, Phase.ROOT_DIRECTORY)
        writeZeros(rootSector + 1, layout.sectorsPerCluster - 1L, Phase.ROOT_DIRECTORY)
    }

    private fun writeBootSectors() {
        val boot = bootSector()
        val fsInfo = fsInfoSector()

        checkCancelled()
        device.write(abs(Fat32Layout.BACKUP_BOOT_SECTOR), boot)
        advance(1, Phase.BOOT_SECTORS)
        device.write(abs(Fat32Layout.BACKUP_BOOT_SECTOR + Fat32Layout.FSINFO_SECTOR), fsInfo)
        advance(1, Phase.BOOT_SECTORS)
        device.write(abs(Fat32Layout.FSINFO_SECTOR), fsInfo)
        advance(1, Phase.BOOT_SECTORS)
        // The primary boot sector is the very last write: until it lands, the
        // volume does not claim to be a filesystem.
        device.write(abs(0), boot)
        advance(1, Phase.BOOT_SECTORS)
    }

    internal fun bootSector(): ByteArray = BootSector.build(layout, options.label, volumeSerial)

    internal fun fsInfoSector(): ByteArray = FsInfo.build(
        bytesPerSector = bps,
        // Cluster 2 is spent on the root directory, so one fewer than the total.
        freeClusters = layout.countOfClusters - 1,
        // Cluster 2 is taken; the next allocation should start looking at 3.
        nextFreeCluster = 3,
    )

    /**
     * Reads back every sector that determines whether the volume mounts.
     *
     * A stick that accepts writes and returns something else is not a
     * theoretical failure: it is exactly how counterfeit-capacity flash
     * behaves, and it is silent until the data is needed.
     */
    fun verify() {
        device.flush()
        val checks = listOf(
            Triple("boot sector", 0L, bootSector()),
            Triple("FSInfo", Fat32Layout.FSINFO_SECTOR, fsInfoSector()),
            Triple("backup boot sector", Fat32Layout.BACKUP_BOOT_SECTOR, bootSector()),
            Triple(
                "backup FSInfo",
                Fat32Layout.BACKUP_BOOT_SECTOR + Fat32Layout.FSINFO_SECTOR,
                fsInfoSector(),
            ),
        ) + (0 until layout.numFats).map { fat ->
            Triple(
                "FAT #${fat + 1} first sector",
                layout.firstFatSector + fat * layout.fatSizeSectors,
                fatFirstSector(),
            )
        }

        val buf = ByteArray(bps)
        for ((name, relSector, expected) in checks) {
            device.read(abs(relSector), buf)
            if (!buf.contentEquals(expected)) {
                throw VerificationException(
                    "Read-back of the $name (absolute sector ${abs(relSector)}) does not match what was " +
                        "written. The device did not store the data it acknowledged. Do not use this volume."
                )
            }
            advance(1, Phase.VERIFY)
        }
    }

    private fun writeZeros(relStart: Long, count: Long, phase: Phase) {
        if (count <= 0) return
        val buf = ByteArray(minOf(count, chunkSectors.toLong()).toInt() * bps)
        var written = 0L
        while (written < count) {
            checkCancelled()
            val n = minOf(chunkSectors.toLong(), count - written)
            val slice = if (n.toInt() * bps == buf.size) buf else ByteArray(n.toInt() * bps)
            device.write(abs(relStart + written), slice)
            written += n
            advance(n, phase)
        }
    }

    private fun advance(sectors: Long, phase: Phase) {
        done += sectors
        progress.onProgress(phase, done, plannedProgressSectors)
    }

    private fun checkCancelled() {
        if (progress.isCancelled) throw FormatCancelledException()
    }

    /**
     * A volume serial from the wall clock, in the shape DOS used: packed date
     * in the high half, packed time in the low half. Nothing reads it for
     * meaning; it exists so two volumes are distinguishable.
     */
    private fun deriveSerial(): Int {
        val ms = System.currentTimeMillis()
        return ((ms ushr 32) xor ms).toInt()
    }

    companion object {
        const val DEFAULT_CHUNK_BYTES = 1 shl 20
    }
}
