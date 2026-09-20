package dev.otgformat.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MbrTest {

    private val sectors = 8L * 1024 * 1024 * 1024 / 512

    @Test
    fun `writes exactly the fields the layout requires`() {
        val mbr = Mbr.build(512, Mbr.FIRST_PARTITION_LBA, sectors - 2048)
        val p = Mbr.PARTITION_TABLE_OFFSET

        assertEquals(0x00, mbr.getU8(p + 0x00), "not bootable by default")
        assertContentEquals(byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0xFF.toByte()), mbr.copyOfRange(p + 1, p + 4))
        assertEquals(Mbr.TYPE_FAT32_LBA, mbr.getU8(p + 0x04))
        assertContentEquals(byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0xFF.toByte()), mbr.copyOfRange(p + 5, p + 8))
        assertEquals(2048L, mbr.getU32(p + 0x08))
        assertEquals(sectors - 2048, mbr.getU32(p + 0x0C))
        assertEquals(0x55, mbr.getU8(Mbr.SIGNATURE_OFFSET))
        assertEquals(0xAA, mbr.getU8(Mbr.SIGNATURE_OFFSET + 1))
    }

    @Test
    fun `bootstrap area and spare partition entries are zeroed`() {
        val mbr = Mbr.build(512, Mbr.FIRST_PARTITION_LBA, sectors - 2048)
        assertTrue(mbr.copyOfRange(0, Mbr.PARTITION_TABLE_OFFSET).all { it == 0.toByte() }, "bootstrap area")
        assertTrue(
            mbr.copyOfRange(Mbr.PARTITION_TABLE_OFFSET + 16, Mbr.SIGNATURE_OFFSET).all { it == 0.toByte() },
            "partition entries 2-4",
        )
    }

    @Test
    fun `bootable flag is honoured`() {
        val mbr = Mbr.build(512, Mbr.FIRST_PARTITION_LBA, sectors - 2048, bootable = true)
        assertEquals(0x80, mbr.getU8(Mbr.PARTITION_TABLE_OFFSET))
    }
}
