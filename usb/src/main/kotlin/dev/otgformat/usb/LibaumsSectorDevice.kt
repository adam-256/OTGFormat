package dev.otgformat.usb

import dev.otgformat.core.SectorDevice
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.driver.ByteBlockDevice
import me.jahnen.libaums.core.driver.scsi.ScsiBlockDevice
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Adapts libaums' [BlockDeviceDriver] to [SectorDevice].
 *
 * Everything risky about Phase 1 is in this class, so it is deliberately a
 * plain JVM class with no Android imports and a fake-driver test suite. The
 * three traps it exists to handle:
 *
 * **Addressing.** `BlockDeviceDriver.read`/`write` take a *block* offset, not a
 * byte offset — libaums ships a separate `ByteBlockDevice` wrapper precisely to
 * convert between the two. An adapter that passed bytes would write 512 times
 * too far into the device.
 *
 * **Capacity.** See [probeSectorCount]. libaums reports one sector fewer than
 * the device has.
 *
 * **Buffers.** Every libaums transport implementation reaches for
 * `buffer.array()`, and one carries the comment "UsbRequest.queue always reads
 * at position 0". A buffer with a non-zero position or array offset goes down a
 * different path in each implementation. This class therefore hands libaums
 * only buffers whose position is 0, whose limit is exactly the transfer length,
 * and whose backing array starts at offset 0 — the one shape every path agrees
 * on. The cost is a memory copy per chunk, which is nothing beside a USB round
 * trip.
 */
class LibaumsSectorDevice internal constructor(
    private val driver: BlockDeviceDriver,
    override val blockSize: Int,
    override val sectorCount: Long,
    /** Sectors per SCSI command. See [DEFAULT_MAX_TRANSFER_BYTES]. */
    maxTransferSectors: Int,
) : SectorDevice {

    /**
     * Sectors per command, reduced on the fly if the bridge refuses a size.
     *
     * Exposed so the self-test can report what the drive settled on.
     */
    var transferSectors: Int = maxTransferSectors
        private set

    /**
     * Scratch buffer handed to libaums.
     *
     * Reused across transfers so a multi-gigabyte format does not allocate a
     * fresh buffer per chunk, and sized to exactly one chunk so its limit is
     * never anything but the transfer length.
     */
    private var scratch: ByteArray = ByteArray(0)

    private fun scratchOf(size: Int): ByteArray {
        if (scratch.size != size) scratch = ByteArray(size)
        return scratch
    }

    override fun read(sector: Long, dst: ByteArray) {
        checkAccess(sector, dst, "read")
        forEachChunk(sector, dst.size) { lba, offset, length ->
            val buf = ByteBuffer.wrap(scratchOf(length))
            driver.read(lba, buf)
            scratch.copyInto(dst, offset, 0, length)
        }
    }

    override fun write(sector: Long, src: ByteArray) {
        checkAccess(sector, src, "write")
        forEachChunk(sector, src.size) { lba, offset, length ->
            val staged = scratchOf(length)
            src.copyInto(staged, 0, offset, offset + length)
            driver.write(lba, ByteBuffer.wrap(staged))
        }
    }

    /**
     * Splits a transfer into chunks the transport will accept.
     *
     * The formatter writes a megabyte at a time, which is far more than a
     * single SCSI READ(10)/WRITE(10) can carry on many devices, so the split
     * belongs here where the transport limit is known rather than in `core`.
     */
    private inline fun forEachChunk(startSector: Long, totalBytes: Int, transfer: (Long, Int, Int) -> Unit) {
        val totalSectors = totalBytes / blockSize
        var done = 0
        while (done < totalSectors) {
            val n = minOf(transferSectors, totalSectors - done)
            try {
                transfer(startSector + done, done * blockSize, n * blockSize)
                done += n
            } catch (e: Exception) {
                // Bridges disagree about how much they will carry in one
                // command, and the limit is not discoverable in advance. Rather
                // than failing a format outright, step the size down and try
                // the same chunk again; once it sticks, the smaller size is
                // kept for the rest of the run.
                if (n <= 1 || transferSectors <= 1) throw e
                transferSectors = maxOf(1, transferSectors / 2)
            }
        }
    }

    private fun checkAccess(sector: Long, buffer: ByteArray, what: String) {
        require(buffer.isNotEmpty() && buffer.size % blockSize == 0) {
            "$what buffer of ${buffer.size} bytes is not a non-zero multiple of the $blockSize byte block size"
        }
        val sectors = buffer.size / blockSize
        require(sector >= 0 && sector + sectors <= sectorCount) {
            "$what of $sectors sectors at $sector runs outside the device's $sectorCount sectors"
        }
        // ScsiRead10/ScsiWrite10 carry the LBA in 32 bits, and libaums narrows
        // with Long.toInt(), which wraps silently past that. Refusing is the
        // only safe response: a wrapped address writes to the start of the
        // device instead of the end.
        require(sector + sectors <= MAX_ADDRESSABLE_SECTOR) {
            "$what reaches sector ${sector + sectors}, past the 32-bit limit of SCSI READ(10)/WRITE(10)"
        }
    }

    /**
     * libaums issues each write as a SCSI WRITE(10) synchronously and does no
     * buffering of its own at this layer, so there is nothing here to flush.
     *
     * This is *not* a guarantee the data has reached flash: the device's own
     * write cache may still hold it, and libaums exposes no SYNCHRONIZE CACHE.
     * The read-back verification in `Formatter` is the real check, and the app
     * must still keep the user from pulling the stick until it finishes.
     */
    override fun flush() = Unit

    companion object {

        /**
         * Bytes per SCSI command.
         *
         * Measured on a SanDisk 3.2Gen1 over a Pixel 10, every size from 32 KiB
         * to 1 MiB was accepted: 32 KiB gave 38 MB/s, 128 KiB 95 MB/s and
         * 256 KiB 135 MB/s. 128 KiB is taken as the default rather than the
         * fastest measured size because the same run also produced an
         * unexplained dip at 512 KiB, and a formatter has nothing to gain from
         * sitting near a cliff — the whole write for a 61.5 GB volume is about
         * 16 MB, so the difference is a fraction of a second either way.
         *
         * Transfers are halved on the fly if a bridge refuses this, so the
         * figure is a starting point rather than a promise.
         */
        const val DEFAULT_MAX_TRANSFER_BYTES = 128 * 1024

        /** READ(10) and WRITE(10) address blocks with 32 unsigned bits. */
        const val MAX_ADDRESSABLE_SECTOR = 0xFFFF_FFFFL

        /**
         * Initialises [driver] and wraps it, determining the true capacity.
         *
         * @param initialise call `driver.init()` first. Leave true unless the
         *   caller has already done so.
         */
        fun open(
            driver: BlockDeviceDriver,
            initialise: Boolean = true,
            maxTransferBytes: Int = DEFAULT_MAX_TRANSFER_BYTES,
        ): LibaumsSectorDevice {
            // BlockDeviceDriver's own documentation leaves the addressing
            // convention to the implementation: "deviceOffset can either be the
            // amount of bytes or a logical block addressing using the block
            // size". ScsiBlockDevice — the one a formatter wants — uses block
            // addressing. ByteBlockDevice and Partition, which extends it, use
            // byte addressing and are confined to a single partition.
            //
            // Handing one of those to a formatter would write at 1/512 of every
            // intended offset and could never reach sector 0 to lay down a
            // partition table. The type is checkable, so it is checked.
            require(driver !is ByteBlockDevice) {
                "LibaumsSectorDevice needs a block-addressed whole-device driver such as ScsiBlockDevice. " +
                    "${driver.javaClass.simpleName} is byte-addressed and partition-relative, so a format " +
                    "through it would write to the wrong offsets and could not reach the partition table."
            }

            if (initialise) driver.init()

            val blockSize = driver.blockSize
            require(blockSize >= 512 && blockSize and (blockSize - 1) == 0) {
                "device reports a $blockSize byte block size, which is not a power of two at least 512"
            }

            val sectorCount = determineSectorCount(driver, blockSize)
            if (sectorCount <= 0) {
                throw IOException("Device reports no capacity ($sectorCount sectors); it may not be ready.")
            }

            val maxTransferSectors = maxOf(1, maxTransferBytes / blockSize)
            return LibaumsSectorDevice(driver, blockSize, sectorCount, maxTransferSectors)
        }

        /**
         * Establishes how many sectors the device really has.
         *
         * `BlockDeviceDriver.blocks` is documented as "the block device size in
         * blocks", and `FileBlockDeviceDriver` returns exactly that
         * (`length / blockSize`). `ScsiBlockDevice` instead returns the SCSI
         * READ CAPACITY(10) *last logical block address*, which is one less
         * than the count. The two implementations disagree with each other and
         * one disagrees with the interface.
         *
         * Taking `blocks` at face value loses the final sector. That is not
         * cosmetic: the stale-signature wipe targets the last 33 sectors
         * precisely because a backup GPT header lives in the very last one.
         *
         * An earlier version of this settled the question by reading one sector
         * *past* the reported count and seeing whether it succeeded. On real
         * hardware that is actively harmful. A drive answers an out-of-range
         * read by stalling the bulk endpoint, and a stalled endpoint stays
         * stalled until CLEAR_FEATURE(ENDPOINT_HALT) — so every later transfer
         * fails too. The probe worked on a file-backed fake and broke the
         * device on a real one.
         *
         * So the convention is taken from the driver type, which is knowable,
         * and checked by reading the last sector the answer implies. That read
         * is inside the medium and cannot stall. Verification only ever looks
         * downwards; nothing here addresses a sector it does not believe
         * exists.
         */
        fun determineSectorCount(
            driver: BlockDeviceDriver,
            blockSize: Int,
            convention: CapacityConvention = conventionOf(driver),
        ): Long {
            val reported = driver.blocks
            if (reported <= 0) return reported

            val count = when (convention) {
                CapacityConvention.LAST_BLOCK_ADDRESS -> reported + 1
                CapacityConvention.BLOCK_COUNT -> reported
            }

            // The last sector of a correct answer must be readable.
            if (readable(driver, count - 1, blockSize)) return count

            // It was not, so fall back to the smaller reading of the same
            // number. Under-reporting costs one sector; over-reporting corrupts
            // writes that fall off the end.
            return minOf(reported, count - 1).coerceAtLeast(0)
        }

        /**
         * Which meaning a driver gives to `blocks`.
         *
         * `ScsiBlockDevice` assigns it the READ CAPACITY(10) last block
         * address; every other implementation in libaums, and the interface's
         * own documentation, mean a count. Pinning the libaums version is what
         * makes this safe to decide by type — see the dependency in
         * `usb/build.gradle.kts`.
         */
        fun conventionOf(driver: BlockDeviceDriver): CapacityConvention =
            if (driver is ScsiBlockDevice) CapacityConvention.LAST_BLOCK_ADDRESS
            else CapacityConvention.BLOCK_COUNT

        private fun readable(driver: BlockDeviceDriver, sector: Long, blockSize: Int): Boolean = try {
            driver.read(sector, ByteBuffer.wrap(ByteArray(blockSize)))
            true
        } catch (e: Exception) {
            false
        }
    }
}
