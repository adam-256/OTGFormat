package dev.otgformat.core

/**
 * Result of a completed format, for display and for the record.
 */
data class FormatResult(
    val layout: Fat32Layout,
    val partitionScheme: PartitionScheme,
    val label: String?,
    val sectorsWritten: Long,
) {
    fun describe(): String = buildString {
        appendLine("Formatted ${label ?: "(unlabelled)"} as ${partitionScheme.name} / FAT32")
        append(layout.describe())
    }
}

/**
 * Top-level entry point: partition a device and put a filesystem on it.
 *
 * Deliberately split into [plan] and [format] so that a caller can compute and
 * display the entire outcome — cluster size, usable capacity, every warning —
 * before the user confirms anything. Nothing in [plan] touches the device.
 */
object Formatter {

    /**
     * Validates [options] against the device and returns the geometry that
     * would result, or throws [FormatException] explaining why it cannot be
     * done. No writes.
     */
    fun plan(device: SectorDevice, options: FormatOptions): Fat32Layout {
        if (options.filesystem != Filesystem.FAT32) {
            throw FormatException(
                "${options.filesystem} is not implemented yet. Only FAT32 is supported; " +
                    "the other filesystems are Phase 2, delegated to the real mkfs tools."
            )
        }

        val partitionStart = when (options.partitionScheme) {
            PartitionScheme.MBR -> Mbr.FIRST_PARTITION_LBA
            PartitionScheme.SUPERFLOPPY -> 0L
            PartitionScheme.GPT -> throw FormatException(
                "GPT is not implemented yet. Writing a GPT with an incorrect CRC produces a table every " +
                    "tool rejects, so it is left out rather than half-done. Use MBR."
            )
        }

        if (device.sectorCount <= partitionStart) {
            throw FormatException(
                "Device holds ${device.sectorCount} sectors, which is not enough for a partition " +
                    "starting at LBA $partitionStart."
            )
        }

        return Fat32Layout.compute(
            partitionStartLba = partitionStart,
            totalSectors = device.sectorCount - partitionStart,
            bytesPerSector = device.blockSize,
            options = options,
        )
    }

    /**
     * Formats [device]. Destroys everything on it.
     *
     * Verifies the critical sectors by reading them back before returning; a
     * successful return means the structures are known to be on the medium, not
     * merely acknowledged by it.
     */
    fun format(
        device: SectorDevice,
        options: FormatOptions,
        progress: Progress = Progress.NONE,
    ): FormatResult {
        val layout = plan(device, options)

        if (options.wipeStaleSignatures) {
            wipeStaleSignatures(device, layout.partitionStartLba, progress)
        }

        if (options.partitionScheme != PartitionScheme.SUPERFLOPPY) {
            val mbr = Mbr.build(
                bytesPerSector = device.blockSize,
                partitionStartLba = layout.partitionStartLba,
                partitionSectors = layout.totalSectors,
                bootable = options.bootable,
            )
            device.write(0, mbr)
            progress.onProgress(Phase.PARTITION_TABLE, 1, 1)
            device.flush()

            val readBack = ByteArray(device.blockSize)
            device.read(0, readBack)
            if (!readBack.contentEquals(mbr)) {
                throw VerificationException(
                    "Read-back of the partition table (sector 0) does not match what was written. " +
                        "The device is not storing data reliably; nothing further was written."
                )
            }
        }

        val fs = Fat32Formatter(device, layout, options, progress)
        fs.format()
        fs.verify()
        device.flush()

        return FormatResult(
            layout = layout,
            partitionScheme = options.partitionScheme,
            label = options.label,
            sectorsWritten = fs.plannedWriteSectors,
        )
    }

    /**
     * Erases partition-table remnants the new MBR would not itself overwrite.
     *
     * The gap between sector 0 and the partition start can hold a GPT header
     * from a previous format, and the last sectors can hold its backup. A stale
     * GPT sitting beside a fresh MBR is a hybrid table: some firmware follows
     * one, some the other, and a stick that works on one machine silently fails
     * on the next. An erase of about 1 MiB removes the ambiguity.
     */
    private fun wipeStaleSignatures(device: SectorDevice, partitionStartLba: Long, progress: Progress) {
        val bps = device.blockSize
        val gapSectors = partitionStartLba
        if (gapSectors > 0) {
            val buf = ByteArray(minOf(gapSectors, (1 shl 20) / bps.toLong()).toInt() * bps)
            var written = 0L
            while (written < gapSectors) {
                if (progress.isCancelled) throw FormatCancelledException()
                val n = minOf((buf.size / bps).toLong(), gapSectors - written)
                val slice = if (n.toInt() * bps == buf.size) buf else ByteArray(n.toInt() * bps)
                device.write(written, slice)
                written += n
                progress.onProgress(Phase.WIPE_SIGNATURES, written, gapSectors + GPT_BACKUP_SECTORS)
            }
        }

        // The backup GPT header and entry array live in the last 33 sectors.
        val tailStart = device.sectorCount - GPT_BACKUP_SECTORS
        if (tailStart > partitionStartLba) {
            device.write(tailStart, ByteArray(GPT_BACKUP_SECTORS.toInt() * bps))
            progress.onProgress(
                Phase.WIPE_SIGNATURES,
                gapSectors + GPT_BACKUP_SECTORS,
                gapSectors + GPT_BACKUP_SECTORS,
            )
        }
        device.flush()
    }

    /** GPT backup: 32 sectors of entry array plus the header in the final sector. */
    private const val GPT_BACKUP_SECTORS = 33L
}
