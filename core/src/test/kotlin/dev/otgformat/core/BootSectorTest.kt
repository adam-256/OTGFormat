package dev.otgformat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Asserts the BPB field-by-field against the specification's table. */
class BootSectorTest {

    private val layout = Formatter.plan(
        UntouchableDevice(sectorCount = 8L * 1024 * 1024 * 1024 / 512),
        FormatOptions(label = "MFLASH"),
    )
    private val boot = BootSector.build(layout, "MFLASH", 0x1234_ABCD)

    @Test
    fun `every BPB field holds the specified value`() {
        assertEquals(listOf(0xEB, 0x58, 0x90), (0..2).map { boot.getU8(it) }, "jump")
        assertEquals("MSWIN4.1", String(boot, BootSector.OFF_OEM, 8, Charsets.US_ASCII))
        assertEquals(512, boot.getU16(BootSector.OFF_BYTES_PER_SECTOR))
        assertEquals(layout.sectorsPerCluster, boot.getU8(BootSector.OFF_SECTORS_PER_CLUSTER))
        assertEquals(32, boot.getU16(BootSector.OFF_RESERVED_SECTORS))
        assertEquals(2, boot.getU8(BootSector.OFF_NUM_FATS))
        assertEquals(0, boot.getU16(BootSector.OFF_ROOT_ENTRY_COUNT), "FAT32 has no fixed root directory")
        assertEquals(0, boot.getU16(BootSector.OFF_TOTAL_SECTORS_16), "FAT32 uses the 32-bit total")
        assertEquals(0xF8, boot.getU8(BootSector.OFF_MEDIA))
        assertEquals(0, boot.getU16(BootSector.OFF_FAT_SIZE_16), "FAT32 uses the 32-bit FAT size")
        assertEquals(63, boot.getU16(BootSector.OFF_SECTORS_PER_TRACK))
        assertEquals(255, boot.getU16(BootSector.OFF_NUM_HEADS))
        assertEquals(2048L, boot.getU32(BootSector.OFF_HIDDEN_SECTORS), "hidden sectors = partition start LBA")
        assertEquals(layout.totalSectors, boot.getU32(BootSector.OFF_TOTAL_SECTORS_32))
        assertEquals(layout.fatSizeSectors, boot.getU32(BootSector.OFF_FAT_SIZE_32))
        assertEquals(0, boot.getU16(BootSector.OFF_EXT_FLAGS), "both FATs mirrored")
        assertEquals(0, boot.getU16(BootSector.OFF_FS_VERSION))
        assertEquals(2L, boot.getU32(BootSector.OFF_ROOT_CLUSTER))
        assertEquals(1, boot.getU16(BootSector.OFF_FS_INFO))
        assertEquals(6, boot.getU16(BootSector.OFF_BACKUP_BOOT))
        assertEquals(0x80, boot.getU8(BootSector.OFF_DRIVE_NUMBER))
        assertEquals(0x00, boot.getU8(BootSector.OFF_DRIVE_NUMBER + 1))
        assertEquals(0x29, boot.getU8(BootSector.OFF_EXT_BOOT_SIG))
        assertEquals(0x1234_ABCDL, boot.getU32(BootSector.OFF_VOLUME_SERIAL))
        assertEquals("MFLASH     ", String(boot, BootSector.OFF_VOLUME_LABEL, 11, Charsets.US_ASCII))
        assertEquals("FAT32   ", String(boot, BootSector.OFF_FS_TYPE, 8, Charsets.US_ASCII), "three trailing spaces")
        assertEquals(0x55, boot.getU8(BootSector.OFF_SIGNATURE))
        assertEquals(0xAA, boot.getU8(BootSector.OFF_SIGNATURE + 1))
    }

    @Test
    fun `the reserved window at 0x34 is zeroed`() {
        assertTrue(boot.copyOfRange(0x34, 0x40).all { it == 0.toByte() })
    }

    @Test
    fun `hidden sectors follows the partition, not a hard-coded 2048`() {
        val sf = Formatter.plan(
            UntouchableDevice(sectorCount = 8L * 1024 * 1024 * 1024 / 512),
            FormatOptions(partitionScheme = PartitionScheme.SUPERFLOPPY),
        )
        assertEquals(0L, BootSector.build(sf, null, 1).getU32(BootSector.OFF_HIDDEN_SECTORS))
    }
}

class FsInfoTest {

    @Test
    fun `carries the three signatures at their specified offsets`() {
        val s = FsInfo.build(512, freeClusters = 1000, nextFreeCluster = 3)
        assertEquals(FsInfo.LEAD_SIGNATURE, s.getU32(FsInfo.OFF_LEAD_SIGNATURE))
        assertEquals(FsInfo.STRUCT_SIGNATURE, s.getU32(FsInfo.OFF_STRUCT_SIGNATURE))
        assertEquals(FsInfo.TRAIL_SIGNATURE, s.getU32(FsInfo.OFF_TRAIL_SIGNATURE))
        assertEquals(1000L, s.getU32(FsInfo.OFF_FREE_COUNT))
        assertEquals(3L, s.getU32(FsInfo.OFF_NEXT_FREE))
    }

    @Test
    fun `everything outside the defined fields is zeroed`() {
        val s = FsInfo.build(512, 1000, 3)
        assertTrue(s.copyOfRange(4, FsInfo.OFF_STRUCT_SIGNATURE).all { it == 0.toByte() })
        assertTrue(s.copyOfRange(FsInfo.OFF_NEXT_FREE + 4, FsInfo.OFF_TRAIL_SIGNATURE).all { it == 0.toByte() })
    }
}
