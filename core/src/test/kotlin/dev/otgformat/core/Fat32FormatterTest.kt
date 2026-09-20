package dev.otgformat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Exercises the writer against an in-memory device: structure, order, and safety. */
class Fat32FormatterTest {

    private val diskSectors = 512L * 1024 * 1024 / 512

    private fun format(
        options: FormatOptions = FormatOptions(label = "TESTVOL", volumeSerial = 0x1234_ABCD),
        progress: Progress = Progress.NONE,
    ): Pair<MemoryDevice, FormatResult> {
        val dev = MemoryDevice(sectorCount = diskSectors)
        return dev to Formatter.format(dev, options, progress)
    }

    @Test
    fun `writes the structures a FAT32 volume is defined by`() {
        val (dev, result) = format()
        val layout = result.layout
        val start = layout.partitionStartLba

        val boot = ByteArray(512).also { dev.read(start, it) }
        assertEquals(0x55, boot.getU8(0x1FE))
        assertEquals(0xAA, boot.getU8(0x1FF))

        val backup = ByteArray(512).also { dev.read(start + 6, it) }
        assertTrue(boot.contentEquals(backup), "backup boot sector at relative sector 6 must be identical")

        val fsInfo = ByteArray(512).also { dev.read(start + 1, it) }
        assertEquals(FsInfo.LEAD_SIGNATURE, fsInfo.getU32(0))
        val backupFsInfo = ByteArray(512).also { dev.read(start + 7, it) }
        assertTrue(fsInfo.contentEquals(backupFsInfo), "FSInfo backup belongs at relative sector 7")

        // FSInfo's free count excludes cluster 2, spent on the root directory.
        assertEquals(layout.countOfClusters - 1, fsInfo.getU32(FsInfo.OFF_FREE_COUNT))
        assertEquals(3L, fsInfo.getU32(FsInfo.OFF_NEXT_FREE))
    }

    @Test
    fun `both FATs are seeded identically and the rest is free`() {
        val (dev, result) = format()
        val layout = result.layout
        for (fat in 0 until layout.numFats) {
            val sector = layout.partitionStartLba + layout.firstFatSector + fat * layout.fatSizeSectors
            val s = ByteArray(512).also { dev.read(sector, it) }
            assertEquals(FatEntries.ENTRY_0, s.getU32(0), "FAT[0] in FAT #${fat + 1}")
            assertEquals(FatEntries.ENTRY_1, s.getU32(4), "FAT[1] in FAT #${fat + 1}")
            assertEquals(FatEntries.ENTRY_2, s.getU32(8), "FAT[2]: root directory, one cluster, end of chain")
            assertTrue(s.copyOfRange(12, 512).all { it == 0.toByte() }, "remaining entries must read as free")
        }
    }

    @Test
    fun `the volume label is written to the root directory as well as the BPB`() {
        val (dev, result) = format()
        val rootSector = result.layout.partitionStartLba + result.layout.clusterToSector(2)
        val s = ByteArray(512).also { dev.read(rootSector, it) }
        assertEquals("TESTVOL    ", String(s, 0, 11, Charsets.US_ASCII))
        assertEquals(DirectoryEntry.ATTR_VOLUME_ID, s.getU8(11))
        // A second entry here would make the root directory look occupied.
        assertTrue(s.copyOfRange(32, 512).all { it == 0.toByte() })
    }

    @Test
    fun `an unlabelled volume gets an empty root directory`() {
        val (dev, result) = format(FormatOptions(volumeSerial = 1))
        val rootSector = result.layout.partitionStartLba + result.layout.clusterToSector(2)
        assertTrue(ByteArray(512).also { dev.read(rootSector, it) }.all { it == 0.toByte() })
    }

    @Test
    fun `stale data in the reserved area and root directory is erased`() {
        // A previous filesystem's backup boot sector at relative sector 6 would
        // otherwise survive and contradict the new one.
        val dev = MemoryDevice(sectorCount = diskSectors)
        val garbage = ByteArray(512) { 0xFF.toByte() }
        for (s in 2048L until 2048 + 32) dev.write(s, garbage)
        val result = Formatter.format(dev, FormatOptions(volumeSerial = 1))

        for (rel in listOf(2L, 3L, 4L, 5L, 8L, 20L, 31L)) {
            val s = ByteArray(512).also { dev.read(result.layout.partitionStartLba + rel, it) }
            assertTrue(s.all { it == 0.toByte() }, "reserved sector $rel still holds stale data")
        }
    }

    @Test
    fun `a device that does not store what it acknowledges is caught`() {
        // This is how counterfeit-capacity flash behaves: writes are accepted
        // and quietly dropped. Without read-back the format reports success.
        val dev = MemoryDevice(sectorCount = diskSectors)
        dev.swallowWritesToSector = 2048L + 6 // the backup boot sector
        val e = assertFailsWith<VerificationException> {
            Formatter.format(dev, FormatOptions(volumeSerial = 1))
        }
        assertTrue("backup boot sector" in e.message!!, e.message!!)
        assertTrue("Do not use this volume" in e.message!!, "the message must tell the user what to do")
    }

    @Test
    fun `a lying partition table is caught before the filesystem is written`() {
        val dev = MemoryDevice(sectorCount = diskSectors)
        dev.swallowWritesToSector = 0L
        val e = assertFailsWith<VerificationException> {
            Formatter.format(dev, FormatOptions(volumeSerial = 1))
        }
        assertTrue("nothing further was written" in e.message!!, e.message!!)
    }

    @Test
    fun `progress is monotonic, real, and ends at the planned total`() {
        val seen = mutableListOf<Triple<Phase, Long, Long>>()
        val progress = object : Progress {
            override fun onProgress(phase: Phase, sectorsDone: Long, sectorsTotal: Long) {
                seen += Triple(phase, sectorsDone, sectorsTotal)
            }
        }
        val (_, result) = format(progress = progress)

        val fsPhases = seen.filter { it.first != Phase.WIPE_SIGNATURES && it.first != Phase.PARTITION_TABLE }
        assertTrue(fsPhases.isNotEmpty())
        var last = 0L
        fsPhases.forEach { (phase, done, total) ->
            assertTrue(done >= last, "progress went backwards at $phase")
            assertTrue(done <= total, "progress exceeded its total at $phase: $done of $total")
            last = done
        }
        // Writing must finish exactly on the planned write count...
        assertEquals(result.sectorsWritten, fsPhases.last { it.first != Phase.VERIFY }.second)
        // ...and the bar must land on exactly 100% after verification, never past it.
        val (_, finalDone, finalTotal) = fsPhases.last()
        assertEquals(Phase.VERIFY, fsPhases.last().first)
        assertEquals(finalTotal, finalDone, "progress must end at exactly its total")
        assertTrue(seen.any { it.first == Phase.ZERO_FAT }, "FAT zeroing must be reported; it is the long phase")
    }

    @Test
    fun `cancellation aborts and does not leave a valid boot sector`() {
        val dev = MemoryDevice(sectorCount = diskSectors)
        val progress = object : Progress {
            var ticks = 0
            override fun onProgress(phase: Phase, sectorsDone: Long, sectorsTotal: Long) {
                ticks++
            }
            override val isCancelled: Boolean get() = ticks > 3
        }
        assertFailsWith<FormatCancelledException> { Formatter.format(dev, FormatOptions(volumeSerial = 1), progress) }

        // Boot sectors are written last, so an aborted format reads as blank
        // rather than as a valid filesystem over a half-written FAT.
        val boot = ByteArray(512).also { dev.read(2048, it) }
        assertTrue(boot.all { it == 0.toByte() }, "an interrupted format must not leave a mountable-looking volume")
    }

    @Test
    fun `stale GPT headers are wiped from the gap and the tail`() {
        val dev = MemoryDevice(sectorCount = diskSectors)
        val fake = ByteArray(512) { 0xAB.toByte() }
        dev.write(1, fake)                      // where a GPT header would sit
        dev.write(diskSectors - 1, fake)        // where its backup would sit
        Formatter.format(dev, FormatOptions(volumeSerial = 1))

        assertTrue(ByteArray(512).also { dev.read(1, it) }.all { it == 0.toByte() }, "primary GPT header survived")
        assertTrue(
            ByteArray(512).also { dev.read(diskSectors - 1, it) }.all { it == 0.toByte() },
            "backup GPT header survived",
        )
    }

    @Test
    fun `wiping can be turned off`() {
        val dev = MemoryDevice(sectorCount = diskSectors)
        val fake = ByteArray(512) { 0xAB.toByte() }
        dev.write(1, fake)
        Formatter.format(dev, FormatOptions(volumeSerial = 1, wipeStaleSignatures = false))
        assertTrue(ByteArray(512).also { dev.read(1, it) }.any { it != 0.toByte() })
    }

    @Test
    fun `superfloppy writes no partition table`() {
        val dev = MemoryDevice(sectorCount = diskSectors)
        Formatter.format(dev, FormatOptions(partitionScheme = PartitionScheme.SUPERFLOPPY, volumeSerial = 1))
        val s0 = ByteArray(512).also { dev.read(0, it) }
        // Sector 0 is the boot sector itself, not an MBR.
        assertEquals(0xEB, s0.getU8(0))
        assertEquals("FAT32   ", String(s0, BootSector.OFF_FS_TYPE, 8, Charsets.US_ASCII))
    }
}
