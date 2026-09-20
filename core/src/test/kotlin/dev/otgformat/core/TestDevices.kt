package dev.otgformat.core

/**
 * A device that fails loudly on any access.
 *
 * Used to prove that planning a format genuinely touches nothing: if
 * [Formatter.plan] ever grows an I/O call, these tests stop compiling their way
 * to a pass and start throwing.
 */
class UntouchableDevice(
    override val blockSize: Int = 512,
    override val sectorCount: Long,
) : SectorDevice {
    override fun read(sector: Long, dst: ByteArray) = error("plan() must not read the device")
    override fun write(sector: Long, src: ByteArray) = error("plan() must not write the device")
    override fun flush() = error("plan() must not flush the device")
}

/** A small in-memory device, for exercising the writer without a filesystem. */
class MemoryDevice(
    override val blockSize: Int = 512,
    override val sectorCount: Long,
) : SectorDevice {
    private val sectors = HashMap<Long, ByteArray>()

    /** When set, the sector whose write is silently discarded. Simulates lying flash. */
    var swallowWritesToSector: Long? = null

    override fun read(sector: Long, dst: ByteArray) {
        for (i in 0 until dst.size / blockSize) {
            val stored = sectors[sector + i]
            if (stored != null) stored.copyInto(dst, i * blockSize)
            else java.util.Arrays.fill(dst, i * blockSize, (i + 1) * blockSize, 0)
        }
    }

    override fun write(sector: Long, src: ByteArray) {
        for (i in 0 until src.size / blockSize) {
            if (sector + i == swallowWritesToSector) continue
            sectors[sector + i] = src.copyOfRange(i * blockSize, (i + 1) * blockSize)
        }
    }

    override fun flush() {}
}
