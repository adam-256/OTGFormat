package dev.otgformat.harness

import dev.otgformat.core.SectorDevice
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * A [SectorDevice] backed by a regular file.
 *
 * This is what makes the formatter testable at all. Every bug in a filesystem
 * writer is an off-by-one in a field offset, and chasing those through
 * sideload → plug in a stick → watch it fail is unusably slow. Against a file,
 * with `fsck.vfat` as the oracle, the same bug surfaces in under a second.
 *
 * Images are sparse: a 64 GiB test image costs only the bytes actually written.
 */
class FileSectorDevice private constructor(
    val file: File,
    override val blockSize: Int,
    override val sectorCount: Long,
    private val raf: RandomAccessFile,
) : SectorDevice, Closeable {

    /** Every read and write that went through this device, for assertions. */
    var readCount: Long = 0; private set
    var writeCount: Long = 0; private set
    var sectorsWritten: Long = 0; private set

    override fun read(sector: Long, dst: ByteArray) {
        checkAccess(sector, dst)
        raf.seek(sector * blockSize)
        raf.readFully(dst)
        readCount++
    }

    override fun write(sector: Long, src: ByteArray) {
        checkAccess(sector, src)
        raf.seek(sector * blockSize)
        raf.write(src)
        writeCount++
        sectorsWritten += src.size / blockSize
    }

    override fun flush() {
        raf.channel.force(false)
    }

    override fun close() = raf.close()

    private fun checkAccess(sector: Long, buffer: ByteArray) {
        require(buffer.isNotEmpty() && buffer.size % blockSize == 0) {
            "buffer length ${buffer.size} is not a non-zero multiple of block size $blockSize"
        }
        val sectors = buffer.size / blockSize
        require(sector >= 0 && sector + sectors <= sectorCount) {
            "access to sectors [$sector, ${sector + sectors}) is outside device of $sectorCount sectors"
        }
    }

    /** Overwrites a sector range with [fill], to prove a format really erases stale data. */
    fun poison(startSector: Long, sectorCount: Long, fill: Byte = 0xFF.toByte()) {
        val buf = ByteArray(blockSize) { fill }
        for (s in startSector until startSector + sectorCount) {
            raf.seek(s * blockSize)
            raf.write(buf)
        }
    }

    fun readSector(sector: Long): ByteArray =
        ByteArray(blockSize).also { read(sector, it) }

    companion object {
        /** Opens an image that already exists, without changing its size. */
        fun create0(file: File, blockSize: Int = 512): FileSectorDevice {
            val raf = RandomAccessFile(file, "rw")
            return FileSectorDevice(file, blockSize, file.length() / blockSize, raf)
        }

        /** Creates a sparse image of [sizeBytes] and opens it. */
        fun create(file: File, sizeBytes: Long, blockSize: Int = 512): FileSectorDevice {
            require(sizeBytes % blockSize == 0L) { "size must be a whole number of sectors" }
            file.parentFile?.mkdirs()
            if (file.exists()) file.delete()
            val raf = RandomAccessFile(file, "rw")
            raf.setLength(sizeBytes)
            return FileSectorDevice(file, blockSize, sizeBytes / blockSize, raf)
        }
    }
}
