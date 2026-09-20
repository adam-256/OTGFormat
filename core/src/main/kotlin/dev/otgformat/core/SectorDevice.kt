package dev.otgformat.core

/**
 * A random-access block device addressed in whole sectors.
 *
 * Addresses are always **absolute** — sector 0 is the first sector of the
 * physical medium, never the first sector of a partition. Partition-relative
 * addressing is the single most common source of bugs in a formatter, so it
 * lives in exactly one place: [Fat32Formatter], which is handed an explicit
 * `partitionStartLba` and offsets every access itself. Nothing else in the
 * codebase is allowed to pass around a pre-offset view of a device.
 *
 * Implementations back this with a file (tests), or with libaums'
 * `BlockDeviceDriver` over USB bulk transfers (the Android app).
 */
interface SectorDevice {

    /** Bytes per sector. Virtually always 512, but some card readers report 4096. */
    val blockSize: Int

    /** Total number of addressable sectors on the medium. */
    val sectorCount: Long

    /** Total capacity in bytes. */
    val sizeBytes: Long get() = sectorCount * blockSize

    /**
     * Reads `dst.size / blockSize` consecutive sectors starting at [sector].
     *
     * [dst] must have a length that is a non-zero multiple of [blockSize].
     * Multi-sector transfers are not an optimisation detail — a USB
     * Bulk-Only-Transport round trip per 512 bytes would make zeroing a FAT on
     * a large stick take minutes, so callers are expected to batch.
     */
    fun read(sector: Long, dst: ByteArray)

    /**
     * Writes `src.size / blockSize` consecutive sectors starting at [sector].
     *
     * [src] must have a length that is a non-zero multiple of [blockSize].
     */
    fun write(sector: Long, src: ByteArray)

    /**
     * Forces buffered writes out to the medium.
     *
     * libaums buffers, so a format that never flushes can report success over
     * data that is still in RAM when the user pulls the stick.
     */
    fun flush()
}

/** Throws unless [buffer] is a whole, non-zero number of sectors and the access is in bounds. */
internal fun SectorDevice.checkAccess(sector: Long, buffer: ByteArray) {
    require(buffer.isNotEmpty() && buffer.size % blockSize == 0) {
        "buffer length ${buffer.size} is not a non-zero multiple of block size $blockSize"
    }
    val sectors = buffer.size / blockSize
    require(sector >= 0 && sector + sectors <= sectorCount) {
        "access to sectors [$sector, ${sector + sectors}) is outside device of $sectorCount sectors"
    }
}
