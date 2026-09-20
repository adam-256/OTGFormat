package dev.otgformat.core

/**
 * The complete geometry of a FAT32 volume, computed before a single byte is
 * written.
 *
 * Keeping the arithmetic in a value object rather than inline in the writer
 * buys three things: every geometry rule can be tested without any I/O at all,
 * the UI can show the user exactly what is about to happen, and the writer
 * becomes a transcription of already-validated numbers instead of a place where
 * arithmetic and byte-poking can go wrong together.
 *
 * All sector fields except [partitionStartLba] are **partition-relative**.
 */
data class Fat32Layout(
    /** Absolute LBA where the partition (and therefore this filesystem) begins. */
    val partitionStartLba: Long,
    val bytesPerSector: Int,
    val sectorsPerCluster: Int,
    val reservedSectors: Int,
    val numFats: Int,
    /** Sectors in the partition. Becomes BPB_TotSec32. */
    val totalSectors: Long,
    /** Sectors in one FAT. Becomes BPB_FATSz32. */
    val fatSizeSectors: Long,
    val countOfClusters: Long,
    val warnings: List<String>,
) {
    /** Partition-relative sector of the first FAT. */
    val firstFatSector: Long get() = reservedSectors.toLong()

    /** Partition-relative sector where the data area (cluster 2) begins. */
    val firstDataSector: Long get() = reservedSectors + numFats * fatSizeSectors

    val dataSectors: Long get() = totalSectors - firstDataSector

    val bytesPerCluster: Int get() = sectorsPerCluster * bytesPerSector

    /** Bytes actually available to files, once the root directory cluster is spent. */
    val usableBytes: Long get() = (countOfClusters - 1) * bytesPerCluster

    /** Number of 32-bit entries one FAT can hold. */
    val fatEntryCapacity: Long get() = fatSizeSectors * bytesPerSector / 4

    /** Partition-relative sector at which [cluster] starts. Cluster numbering starts at 2. */
    fun clusterToSector(cluster: Long): Long {
        require(cluster >= 2) { "FAT32 data clusters are numbered from 2, got $cluster" }
        return firstDataSector + (cluster - 2) * sectorsPerCluster
    }

    /** A human-readable plan, for confirmation screens and test logs. */
    fun describe(): String = buildString {
        appendLine("FAT32 layout")
        appendLine("  partition start LBA   : $partitionStartLba")
        appendLine("  bytes per sector      : $bytesPerSector")
        appendLine("  sectors per cluster   : $sectorsPerCluster (${formatBytes(bytesPerCluster)} clusters)")
        appendLine("  reserved sectors      : $reservedSectors")
        appendLine("  number of FATs        : $numFats")
        appendLine("  sectors per FAT       : $fatSizeSectors (${formatBytes(fatSizeSectors * bytesPerSector)} each)")
        appendLine("  first FAT sector      : $firstFatSector (partition-relative)")
        appendLine("  first data sector     : $firstDataSector (partition-relative)")
        appendLine("  total sectors         : $totalSectors")
        appendLine("  cluster count         : $countOfClusters")
        appendLine("  FAT entry capacity    : $fatEntryCapacity (needs ${countOfClusters + 2})")
        appendLine("  usable space          : ${formatBytes(usableBytes)}")
        warnings.forEach { appendLine("  warning               : $it") }
    }

    companion object {

        /** fatgen103 fixes the FAT32 root directory at cluster 2. */
        const val ROOT_CLUSTER = 2L

        /** BPB_RsvdSecCnt. 32 is the value every FAT32 implementation expects. */
        const val RESERVED_SECTORS = 32

        /** Two FATs. One is legal and nobody does it. */
        const val NUM_FATS = 2

        /** BPB_BkBootSec: the backup boot sector, and FSInfo's backup right after it. */
        const val BACKUP_BOOT_SECTOR = 6L
        const val FSINFO_SECTOR = 1L

        /**
         * Sectors per FAT, by the fatgen103 formula.
         *
         *     TmpVal1 = TotalSectors - ReservedSectors
         *     TmpVal2 = (256 * SectorsPerCluster + NumFATs) / 2
         *     FATSz   = ceil(TmpVal1 / TmpVal2)
         *
         * The literal 256 in Microsoft's text is `bytesPerSector / 2` with 512
         * assumed, so it is written out here to keep 4096-byte-sector media
         * correct rather than silently under-allocating on them.
         *
         * The result slightly over-allocates compared to the tightest possible
         * FAT — `mkfs.vfat` computes the exact minimum and will report a smaller
         * number. That is expected: over-allocating wastes a few clusters and is
         * always safe, under-allocating produces a volume whose last clusters
         * have no FAT entry to describe them.
         */
        fun fatSizeSectorsFor(
            totalSectors: Long,
            sectorsPerCluster: Int,
            bytesPerSector: Int,
            numFats: Int,
            reservedSectors: Int,
        ): Long {
            val tmpVal1 = totalSectors - reservedSectors
            val tmpVal2 = ((bytesPerSector / 2).toLong() * sectorsPerCluster + numFats) / 2
            if (tmpVal1 <= 0 || tmpVal2 <= 0) return 0
            return (tmpVal1 + tmpVal2 - 1) / tmpVal2
        }

        /**
         * Cluster count that would result from the given geometry. May be zero
         * or negative-turned-zero for volumes too small to hold their own FATs.
         */
        fun clusterCountFor(
            totalSectors: Long,
            sectorsPerCluster: Int,
            bytesPerSector: Int,
            numFats: Int,
            reservedSectors: Int,
        ): Long {
            val fatSize = fatSizeSectorsFor(totalSectors, sectorsPerCluster, bytesPerSector, numFats, reservedSectors)
            val dataSectors = totalSectors - (reservedSectors + numFats * fatSize)
            if (dataSectors <= 0) return 0
            return dataSectors / sectorsPerCluster
        }

        /**
         * Computes and validates the geometry for a partition of [totalSectors]
         * sectors, or throws [FormatException] explaining why it cannot be
         * FAT32. Nothing is written and no device is touched.
         */
        fun compute(
            partitionStartLba: Long,
            totalSectors: Long,
            bytesPerSector: Int,
            options: FormatOptions,
        ): Fat32Layout {
            require(bytesPerSector >= 512 && bytesPerSector and (bytesPerSector - 1) == 0) {
                "bytes per sector must be a power of two at least 512, got $bytesPerSector"
            }
            if (totalSectors <= 0) {
                throw FormatException("Partition has no sectors.")
            }
            if (totalSectors > 0xFFFF_FFFFL) {
                throw FormatException(
                    "Partition is $totalSectors sectors; FAT32 records the total in a 32-bit field " +
                        "and cannot exceed ${0xFFFF_FFFFL} sectors."
                )
            }

            val warnings = mutableListOf<String>()

            val sectorsPerCluster = when (val cs = options.clusterSize) {
                is ClusterSize.Auto -> {
                    val auto = ClusterSizeTable.forVolume(totalSectors, bytesPerSector, NUM_FATS, RESERVED_SECTORS)
                        ?: throw tooSmall(totalSectors, bytesPerSector)
                    val table = ClusterSizeTable.standardTable(totalSectors * bytesPerSector)
                    if (auto < table) {
                        warnings += "Volume is too small for the standard " +
                            "${formatBytes(table * bytesPerSector)} cluster size; using " +
                            "${formatBytes(auto * bytesPerSector)} to reach the " +
                            "${ClusterSizeTable.MIN_FAT32_CLUSTERS}-cluster FAT32 minimum."
                    }
                    auto
                }

                is ClusterSize.Bytes -> {
                    if (cs.bytes % bytesPerSector != 0) {
                        throw FormatException(
                            "Cluster size ${cs.bytes} B is not a multiple of the device's " +
                                "$bytesPerSector B sector size."
                        )
                    }
                    val spc = cs.bytes / bytesPerSector
                    if (spc > 128) {
                        throw FormatException(
                            "Cluster size ${cs.bytes} B needs $spc sectors per cluster; FAT32 stores that " +
                                "in one byte and allows at most 128."
                        )
                    }
                    if (cs.bytes > 32 * 1024) {
                        warnings += "Clusters larger than 32 KiB (${formatBytes(cs.bytes)}) are outside " +
                            "Microsoft's table. Windows will " +
                            "refuse to mount this volume even though it is structurally valid."
                    }
                    spc
                }
            }

            val fatSizeSectors =
                fatSizeSectorsFor(totalSectors, sectorsPerCluster, bytesPerSector, NUM_FATS, RESERVED_SECTORS)
            val dataSectors = totalSectors - (RESERVED_SECTORS + NUM_FATS * fatSizeSectors)
            if (dataSectors <= 0) {
                throw tooSmall(totalSectors, bytesPerSector)
            }
            val countOfClusters = dataSectors / sectorsPerCluster

            if (countOfClusters < ClusterSizeTable.MIN_FAT32_CLUSTERS) {
                throw FormatException(
                    "This geometry produces $countOfClusters clusters. FAT32 requires at least " +
                        "${ClusterSizeTable.MIN_FAT32_CLUSTERS}; below that the volume is FAT16 by definition " +
                        "and checkers will reject it. " +
                        if (options.clusterSize is ClusterSize.Bytes) {
                            "Try a smaller cluster size, or leave it on automatic."
                        } else {
                            "The volume is too small for FAT32."
                        }
                )
            }
            if (countOfClusters > ClusterSizeTable.MAX_FAT32_CLUSTERS) {
                throw FormatException(
                    "This geometry produces $countOfClusters clusters, past the FAT32 maximum of " +
                        "${ClusterSizeTable.MAX_FAT32_CLUSTERS}. Use a larger cluster size."
                )
            }

            // The fatgen103 formula is a closed-form approximation, so the
            // guarantee that actually matters is asserted rather than assumed:
            // every cluster must have a FAT entry describing it.
            val capacity = fatSizeSectors * bytesPerSector / 4
            if (capacity < countOfClusters + 2) {
                throw FormatException(
                    "Internal geometry error: $fatSizeSectors-sector FAT holds $capacity entries but the " +
                        "volume has $countOfClusters clusters (needs ${countOfClusters + 2}). Refusing to write."
                )
            }

            val firstDataSector = RESERVED_SECTORS + NUM_FATS * fatSizeSectors
            val bytesPerCluster = sectorsPerCluster.toLong() * bytesPerSector
            if ((partitionStartLba + firstDataSector) * bytesPerSector % bytesPerCluster != 0L) {
                warnings += "Data area starts at absolute byte " +
                    "${(partitionStartLba + firstDataSector) * bytesPerSector}, which is not a multiple of the " +
                    "$bytesPerCluster B cluster size. Harmless, but cluster writes will straddle flash erase " +
                    "blocks. See README, \"Data-area alignment\"."
            }

            return Fat32Layout(
                partitionStartLba = partitionStartLba,
                bytesPerSector = bytesPerSector,
                sectorsPerCluster = sectorsPerCluster,
                reservedSectors = RESERVED_SECTORS,
                numFats = NUM_FATS,
                totalSectors = totalSectors,
                fatSizeSectors = fatSizeSectors,
                countOfClusters = countOfClusters,
                warnings = warnings,
            )
        }

        private fun tooSmall(totalSectors: Long, bytesPerSector: Int): FormatException {
            val minBytes = ClusterSizeTable.MIN_FAT32_CLUSTERS * bytesPerSector
            return FormatException(
                "Partition is ${formatBytes(totalSectors * bytesPerSector)}. " +
                    "Even with ${formatBytes(bytesPerSector)} clusters that is fewer than " +
                    "${ClusterSizeTable.MIN_FAT32_CLUSTERS} clusters, so it cannot be FAT32 " +
                    "(the floor is roughly ${minBytes / (1024 * 1024)} MiB plus overhead). Use FAT16 or a larger device."
            )
        }
    }
}
