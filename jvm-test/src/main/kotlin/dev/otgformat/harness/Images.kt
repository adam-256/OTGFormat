package dev.otgformat.harness

import dev.otgformat.core.FormatOptions
import dev.otgformat.core.FormatResult
import dev.otgformat.core.Formatter
import dev.otgformat.core.Mbr
import java.io.File

/** Shared setup for tests that produce and validate real disk images. */
object Images {

    val MIB = 1024L * 1024
    val GIB = 1024L * MIB

    /** The four sizes Phase 0 must satisfy, in MiB. */
    val ACCEPTANCE_SIZES_MIB = longArrayOf(64, 2 * 1024, 8 * 1024, 64 * 1024)

    val scratch: File by lazy {
        File(System.getProperty("otgformat.scratch") ?: "build/images").also { it.mkdirs() }
    }

    fun image(name: String): File = File(scratch, name)

    /**
     * Formats a fresh sparse image and returns it with its layout.
     *
     * The image stays on disk after the test so a failure can be examined with
     * the same tools by hand.
     */
    fun format(
        name: String,
        sizeBytes: Long,
        options: FormatOptions = FormatOptions(label = "OTGTEST", volumeSerial = 0x1234_ABCD),
        blockSize: Int = 512,
    ): Formatted {
        val file = image(name)
        val device = FileSectorDevice.create(file, sizeBytes, blockSize)
        val result = Formatter.format(device, options)
        device.flush()
        return Formatted(file, device, result)
    }

    data class Formatted(
        val file: File,
        val device: FileSectorDevice,
        val result: FormatResult,
    ) {
        /** Extracts the partition to a standalone file, which is what fsck.vfat needs. */
        fun extractPartition(): File {
            val out = File(file.parentFile, file.nameWithoutExtension + ".part.img")
            return PartitionExtractor.extract(
                device,
                result.layout.partitionStartLba,
                result.layout.totalSectors,
                out,
            )
        }

        fun close() = device.close()
    }

    /** Sector 0 of the partition table, for assertions about the MBR itself. */
    fun partitionEntry(disk: FileSectorDevice): ByteArray =
        disk.readSector(0).copyOfRange(Mbr.PARTITION_TABLE_OFFSET, Mbr.PARTITION_TABLE_OFFSET + 16)
}
