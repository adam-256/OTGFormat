package dev.otgformat.harness

import dev.otgformat.core.ClusterSize
import dev.otgformat.core.FormatException
import dev.otgformat.core.FormatOptions
import dev.otgformat.core.Formatter
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Acceptance criteria 1, 2 and 5: images produced by `core` are validated by
 * the real dosfstools, at every size the project claims to support.
 *
 * `fsck.vfat` is run with `-n` (never write) and `-V` (verification pass), so
 * it can only ever report, never quietly repair a defect into passing.
 */
class FsckValidationTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireTools() {
            assumeTrue(
                ExternalTools.available("fsck.vfat"),
                "fsck.vfat not installed (package: dosfstools) — cannot validate against an independent oracle",
            )
        }
    }

    @ParameterizedTest(name = "{0} MiB image passes fsck.vfat")
    @ValueSource(longs = [64, 2048, 8192, 65536])
    fun `formatted images pass fsck`(sizeMib: Long) {
        val formatted = Images.format("fsck-$sizeMib.img", sizeMib * Images.MIB)
        try {
            println("--- $sizeMib MiB ---")
            print(formatted.result.layout.describe())

            val partition = formatted.extractPartition()
            val fsck = ExternalTools.fsck(partition)
            println(fsck.output)

            assertTrue(
                fsck.ok,
                "fsck.vfat rejected the $sizeMib MiB image (exit ${fsck.exitCode}):\n${fsck.output}",
            )

            // Exit code 0 alone only says nothing was self-contradictory. The
            // real check is that the geometry fsck independently derived from
            // our BPB is the geometry we set out to write.
            val layout = formatted.result.layout
            val report = FsckReport.parse(fsck.output)
            assertEquals(layout.bytesPerSector, report.bytesPerSector, "bytes per sector")
            assertEquals(layout.bytesPerCluster, report.bytesPerCluster, "bytes per cluster")
            assertEquals(layout.reservedSectors, report.reservedSectors, "reserved sectors")
            assertEquals(layout.firstFatSector, report.firstFatSector, "first FAT sector")
            assertEquals(layout.numFats, report.numFats, "number of FATs")
            assertEquals(32, report.fatBits, "must be read as FAT32, not FAT16")
            assertEquals(layout.fatSizeSectors, report.fatSizeSectors, "sectors per FAT")
            assertEquals(2L, report.rootCluster, "root directory cluster")
            assertEquals(layout.firstDataSector, report.dataStartSector, "first data sector")
            assertEquals(layout.countOfClusters, report.dataClusters, "cluster count")
            assertEquals(layout.partitionStartLba, report.hiddenSectors, "hidden sectors")
            assertEquals(layout.totalSectors, report.totalSectors, "total sectors")

            // And the cluster count must clear the FAT32 floor by fsck's own
            // reckoning, not just ours.
            assertTrue(report.dataClusters >= 65525, "fsck saw only ${report.dataClusters} clusters")
        } finally {
            formatted.close()
        }
    }

    @ParameterizedTest(name = "{0} MiB image is identified as DOS/MBR with a FAT32 partition")
    @ValueSource(longs = [64, 2048, 8192, 65536])
    fun `file identifies the image as MBR with a FAT32 partition`(sizeMib: Long) {
        assumeTrue(ExternalTools.available("file"), "file(1) not installed")
        val formatted = Images.format("filetype-$sizeMib.img", sizeMib * Images.MIB)
        try {
            // On the whole disk, file(1) reports the partition table only; it
            // does not recurse into a partition. ID=0xc is therefore the FAT32
            // identification, and startsector proves it points at our partition.
            val disk = ExternalTools.fileType(formatted.file).output
            println("$sizeMib MiB disk      -> $disk")
            assertContains(disk, "DOS/MBR boot sector", ignoreCase = true)
            assertContains(disk, "ID=0xc", ignoreCase = true)
            assertContains(disk, "startsector 2048")
            assertContains(disk, "${formatted.result.layout.totalSectors} sectors")

            // Pointed at the partition itself, file(1) reads the boot sector and
            // must call it FAT32 — which is what a firmware parser will do.
            val partition = ExternalTools.fileType(formatted.extractPartition()).output
            println("$sizeMib MiB partition -> $partition")
            assertContains(partition, "FAT (32 bit)", ignoreCase = true)
        } finally {
            formatted.close()
        }
    }

    @Test
    fun `the partition table describes the partition fsck actually validated`() {
        val formatted = Images.format("mbr-check.img", 2048 * Images.MIB)
        try {
            val entry = Images.partitionEntry(formatted.device)
            val startLba = entry.let {
                (it[8].toLong() and 0xFF) or ((it[9].toLong() and 0xFF) shl 8) or
                    ((it[10].toLong() and 0xFF) shl 16) or ((it[11].toLong() and 0xFF) shl 24)
            }
            val sectors = entry.let {
                (it[12].toLong() and 0xFF) or ((it[13].toLong() and 0xFF) shl 8) or
                    ((it[14].toLong() and 0xFF) shl 16) or ((it[15].toLong() and 0xFF) shl 24)
            }
            kotlin.test.assertEquals(2048L, startLba, "partition must start at LBA 2048")
            kotlin.test.assertEquals(formatted.result.layout.totalSectors, sectors)
            kotlin.test.assertEquals(
                formatted.device.sectorCount - 2048,
                sectors,
                "the partition must span the rest of the device",
            )
            kotlin.test.assertEquals(0x0C, entry[4].toInt() and 0xFF, "partition type FAT32 LBA")
        } finally {
            formatted.close()
        }
    }

    /** Acceptance criterion 5: a 16 MiB image is refused, and nothing is written. */
    @Test
    fun `a 16 MiB image is rejected with a cluster-count error and left untouched`() {
        val file = Images.image("too-small.img")
        val device = FileSectorDevice.create(file, 16 * Images.MIB)
        try {
            val e = assertFailsWith<FormatException> { Formatter.format(device, FormatOptions(label = "TOOSMALL")) }
            println("rejected as expected: ${e.message}")
            assertContains(e.message!!, "65525")
            kotlin.test.assertEquals(0L, device.sectorsWritten, "a rejected format must not write anything")

            // And the image really is still blank.
            assertTrue(device.readSector(0).all { it == 0.toByte() })
        } finally {
            device.close()
        }
    }

    @Test
    fun `an overridden cluster size that is valid still passes fsck`() {
        // 16 KiB clusters on an 8 GiB stick: legal, just not what auto picks.
        val formatted = Images.format(
            "override-16k.img",
            8192 * Images.MIB,
            FormatOptions(label = "OVERRIDE", clusterSize = ClusterSize.Bytes(16 * 1024), volumeSerial = 1),
        )
        try {
            kotlin.test.assertEquals(32, formatted.result.layout.sectorsPerCluster)
            val fsck = ExternalTools.fsck(formatted.extractPartition())
            println(fsck.output)
            assertTrue(fsck.ok, "fsck.vfat rejected a 16 KiB-cluster volume:\n${fsck.output}")
        } finally {
            formatted.close()
        }
    }

    @Test
    fun `an unlabelled volume passes fsck`() {
        val formatted = Images.format(
            "nolabel.img",
            2048 * Images.MIB,
            FormatOptions(volumeSerial = 1),
        )
        try {
            val fsck = ExternalTools.fsck(formatted.extractPartition())
            println(fsck.output)
            assertTrue(fsck.ok, "fsck.vfat rejected an unlabelled volume:\n${fsck.output}")
        } finally {
            formatted.close()
        }
    }

    @Test
    fun `a superfloppy volume passes fsck without extraction`() {
        val formatted = Images.format(
            "superfloppy.img",
            2048 * Images.MIB,
            FormatOptions(
                label = "SUPERFLOP",
                partitionScheme = dev.otgformat.core.PartitionScheme.SUPERFLOPPY,
                volumeSerial = 1,
            ),
        )
        try {
            // No partition table, so the filesystem starts at sector 0 and
            // fsck can read the image directly.
            val fsck = ExternalTools.fsck(formatted.file)
            println(fsck.output)
            assertTrue(fsck.ok, "fsck.vfat rejected the superfloppy volume:\n${fsck.output}")
        } finally {
            formatted.close()
        }
    }
}
