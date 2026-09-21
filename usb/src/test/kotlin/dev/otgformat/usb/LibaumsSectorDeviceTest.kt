package dev.otgformat.usb

import dev.otgformat.core.FormatOptions
import dev.otgformat.core.Formatter
import me.jahnen.libaums.core.driver.ByteBlockDevice
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LibaumsSectorDeviceTest {

    // ---- addressing -------------------------------------------------------

    @Test
    fun `sector numbers are passed through as logical block addresses, not byte offsets`() {
        // The trap: BlockDeviceDriver's own documentation says the offset may
        // be either bytes or blocks. ScsiBlockDevice uses blocks. Passing bytes
        // would put every write 512 times too far into the device.
        val fake = FakeBlockDeviceDriver(trueSectorCount = 4096)
        val device = LibaumsSectorDevice.open(fake)

        val payload = ByteArray(512) { 0xAB.toByte() }
        device.write(100, payload)

        assertEquals(100L, fake.transfers.single { it.write }.lba, "the LBA must be the sector number itself")
        assertContentEquals(payload, fake.sector(100))
        assertTrue(fake.sector(99).all { it == 0.toByte() }, "nothing should have landed in the neighbouring sector")
    }

    @Test
    fun `data round trips through the adapter unchanged`() {
        val fake = FakeBlockDeviceDriver(trueSectorCount = 4096)
        val device = LibaumsSectorDevice.open(fake)

        val payload = ByteArray(4096) { (it * 31 % 251).toByte() }
        device.write(64, payload)

        val read = ByteArray(4096)
        device.read(64, read)
        assertContentEquals(payload, read)
    }

    @Test
    fun `a 4096-byte-sector device is addressed correctly`() {
        val fake = FakeBlockDeviceDriver(trueSectorCount = 2048, blockSize = 4096)
        val device = LibaumsSectorDevice.open(fake)
        assertEquals(4096, device.blockSize)

        val payload = ByteArray(4096) { 0x5A }
        device.write(7, payload)
        assertEquals(7L, fake.transfers.single { it.write }.lba)
        assertContentEquals(payload, fake.sector(7))
    }

    // ---- capacity ---------------------------------------------------------

    @Test
    fun `a last-block-address driver yields the true sector count`() {
        // ScsiBlockDevice's convention. Taking `blocks` at face value would
        // lose the final sector — the one a backup GPT header sits in.
        val fake = FakeBlockDeviceDriver(trueSectorCount = 1000, convention = CapacityConvention.LAST_BLOCK_ADDRESS)
        assertEquals(999L, fake.blocks, "precondition: the fake reports a last address")
        assertEquals(
            1000L,
            LibaumsSectorDevice.determineSectorCount(fake, 512, CapacityConvention.LAST_BLOCK_ADDRESS),
        )
    }

    @Test
    fun `a block-count driver yields the same count`() {
        // FileBlockDeviceDriver's convention. A hard-coded +1 would over-run
        // this device by a sector.
        val fake = FakeBlockDeviceDriver(trueSectorCount = 1000, convention = CapacityConvention.BLOCK_COUNT)
        assertEquals(1000L, fake.blocks)
        assertEquals(1000L, LibaumsSectorDevice.determineSectorCount(fake, 512, CapacityConvention.BLOCK_COUNT))
    }

    @Test
    fun `determining the capacity never addresses a sector past the end`() {
        // This is the bug that broke the first run on real hardware: the old
        // implementation read one sector *past* the reported count to work out
        // the convention. A drive answers that by stalling its bulk endpoint,
        // and every transfer afterwards fails until the halt is cleared.
        for (convention in CapacityConvention.entries) {
            val fake = FakeBlockDeviceDriver(trueSectorCount = 1000, convention = convention)
            val count = LibaumsSectorDevice.determineSectorCount(fake, 512, convention)
            val highest = fake.transfers.maxOf { it.lba + it.bytes / 512 }
            assertTrue(
                highest <= count,
                "$convention: addressed up to sector $highest on a device of $count sectors",
            )
            assertTrue(fake.transfers.none { it.write }, "$convention: determining capacity must not write")
        }
    }

    @Test
    fun `a driver whose last sector cannot be read falls back to the smaller count`() {
        // Under-reporting costs one sector; over-reporting corrupts writes that
        // fall off the end, so the smaller figure wins.
        val fake = FakeBlockDeviceDriver(
            trueSectorCount = 1000,
            convention = CapacityConvention.LAST_BLOCK_ADDRESS,
        )
        assertEquals(999L, fake.blocks, "precondition: reports a last address")
        fake.failReadsFrom = 999
        assertEquals(999L, LibaumsSectorDevice.determineSectorCount(fake, 512, CapacityConvention.LAST_BLOCK_ADDRESS))
    }

    @Test
    fun `the last sector of the device is writable under either convention`() {
        for (convention in CapacityConvention.entries) {
            val fake = FakeBlockDeviceDriver(trueSectorCount = 1000, convention = convention)
            val count = LibaumsSectorDevice.determineSectorCount(fake, 512, convention)
            val device = LibaumsSectorDevice(fake, 512, count, 32)
            val payload = ByteArray(512) { 0x7E }
            // Would throw if the count were wrong in either direction.
            device.write(device.sectorCount - 1, payload)
            assertContentEquals(payload, fake.sector(count - 1), "$convention")
        }
    }

    @Test
    fun `an unknown driver is assumed to report a block count`() {
        // The libaums interface documents `blocks` as a count, and only
        // ScsiBlockDevice departs from that.
        assertEquals(
            CapacityConvention.BLOCK_COUNT,
            LibaumsSectorDevice.conventionOf(FakeBlockDeviceDriver(trueSectorCount = 10)),
        )
    }

    @Test
    fun `init is called once`() {
        val fake = FakeBlockDeviceDriver(trueSectorCount = 1000)
        LibaumsSectorDevice.open(fake)
        assertEquals(1, fake.initCalled)

        val already = FakeBlockDeviceDriver(trueSectorCount = 1000)
        LibaumsSectorDevice.open(already, initialise = false)
        assertEquals(0, already.initCalled)
    }

    // ---- buffer shape -----------------------------------------------------

    @Test
    fun `every buffer handed to libaums starts at position zero with no array offset`() {
        // Each libaums transport reaches for buffer.array(), and one notes that
        // "UsbRequest.queue always reads at position 0". A buffer with a
        // non-zero position or array offset takes a different path in each
        // implementation; this is the one shape they all agree on.
        val fake = FakeBlockDeviceDriver(trueSectorCount = 8192)
        val device = LibaumsSectorDevice.open(fake)

        device.write(10, ByteArray(1 shl 20) { it.toByte() })
        device.read(10, ByteArray(1 shl 20))

        assertTrue(fake.transfers.isNotEmpty())
        for (t in fake.transfers) {
            assertEquals(0, t.bufferPosition, "buffer position must be 0")
            assertEquals(0, t.arrayOffset, "buffer array offset must be 0")
            assertEquals(t.bytes, t.bufferLimit, "limit must be exactly the transfer length")
            assertEquals(t.bytes, t.backingArraySize, "the backing array must be exactly the transfer length")
        }
    }

    // ---- chunking ---------------------------------------------------------

    @Test
    fun `large transfers are split into commands the transport will accept`() {
        val fake = FakeBlockDeviceDriver(trueSectorCount = 8192)
        val device = LibaumsSectorDevice.open(fake)
        fake.transfers.clear()

        val oneMiB = ByteArray(1 shl 20) { (it % 253).toByte() }
        device.write(1000, oneMiB)

        val writes = fake.transfers.filter { it.write }
        val expected = (1 shl 20) / LibaumsSectorDevice.DEFAULT_MAX_TRANSFER_BYTES
        assertEquals(expected, writes.size, "1 MiB should split into $expected commands")
        writes.forEach { assertTrue(it.bytes <= LibaumsSectorDevice.DEFAULT_MAX_TRANSFER_BYTES) }

        // Chunks must be consecutive and cover the range exactly once.
        assertEquals(1000L, writes.first().lba)
        writes.zipWithNext().forEach { (a, b) ->
            assertEquals(a.lba + a.bytes / 512, b.lba, "chunks must be consecutive with no gap or overlap")
        }
        val read = ByteArray(1 shl 20)
        device.read(1000, read)
        assertContentEquals(oneMiB, read, "a split transfer must reassemble exactly")
    }

    @Test
    fun `a transfer that is not a whole multiple of the chunk size still covers its range`() {
        val fake = FakeBlockDeviceDriver(trueSectorCount = 8192)
        val device = LibaumsSectorDevice.open(fake)

        // 300 KiB: two full 128 KiB chunks and a 44 KiB remainder.
        val payload = ByteArray(300 * 1024) { (it % 251).toByte() }
        device.write(2000, payload)
        val read = ByteArray(300 * 1024)
        device.read(2000, read)
        assertContentEquals(payload, read)
    }

    // ---- refusals ---------------------------------------------------------

    @Test
    fun `access past the end of the device is refused before it reaches libaums`() {
        val fake = FakeBlockDeviceDriver(trueSectorCount = 1000)
        val device = LibaumsSectorDevice.open(fake)
        fake.transfers.clear()

        assertFailsWith<IllegalArgumentException> { device.write(999, ByteArray(1024)) }
        assertFailsWith<IllegalArgumentException> { device.read(1000, ByteArray(512)) }
        assertFailsWith<IllegalArgumentException> { device.write(-1, ByteArray(512)) }
        assertTrue(fake.transfers.isEmpty(), "a refused access must not reach the device at all")
    }

    @Test
    fun `a buffer that is not a whole number of sectors is refused`() {
        val device = LibaumsSectorDevice.open(FakeBlockDeviceDriver(trueSectorCount = 1000))
        assertFailsWith<IllegalArgumentException> { device.write(0, ByteArray(500)) }
        assertFailsWith<IllegalArgumentException> { device.write(0, ByteArray(0)) }
    }

    @Test
    fun `a byte-addressed partition-relative driver is refused`() {
        // Partition extends ByteBlockDevice. Formatting through one would write
        // at 1/512 of every intended offset and could never reach sector 0.
        val wrapped = ByteBlockDevice(FakeBlockDeviceDriver(trueSectorCount = 1000))
        val e = assertFailsWith<IllegalArgumentException> { LibaumsSectorDevice.open(wrapped) }
        assertTrue("byte-addressed" in e.message!!, e.message!!)
    }

    // ---- the whole path ---------------------------------------------------

    @Test
    fun `formatting through the adapter produces the same bytes as formatting a file`() {
        // The strongest statement available without hardware: the Android I/O
        // path and the path Phase 0 verified against fsck.vfat produce
        // byte-identical media.
        // 64 MiB: the smallest volume that is legal FAT32, which keeps the two
        // in-memory copies small while still exercising every phase.
        val sectors = 64L * 1024 * 1024 / 512
        val options = FormatOptions(label = "VIAUSB", volumeSerial = 0x1234_ABCD)

        val fake = FakeBlockDeviceDriver(trueSectorCount = sectors)
        val viaUsb = LibaumsSectorDevice.open(fake)
        Formatter.format(viaUsb, options)

        val reference = ArraySectorDevice(sectors, 512)
        Formatter.format(reference, options)

        assertSameBytes(
            reference.store,
            fake.store,
            "the adapter must be transparent: formatting through libaums and formatting a plain " +
                "array must produce identical media",
        )
    }
}

/**
 * Compares two whole-volume images.
 *
 * `assertContentEquals` renders both arrays into its failure message, which on
 * a 64 MiB image exhausts the heap before it can tell you anything. This
 * reports the first offset that differs instead.
 */
private fun assertSameBytes(expected: ByteArray, actual: ByteArray, message: String) {
    assertEquals(expected.size, actual.size, "$message (different sizes)")
    for (i in expected.indices) {
        if (expected[i] != actual[i]) {
            val from = maxOf(0, i - 8)
            fun hex(a: ByteArray) = a.copyOfRange(from, minOf(a.size, i + 8))
                .joinToString(" ") { "%02x".format(it) }
            kotlin.test.fail(
                "$message\nFirst difference at byte $i (sector ${i / 512}, offset ${i % 512}):\n" +
                    "  expected ${hex(expected)}\n  actual   ${hex(actual)}",
            )
        }
    }
}

/** A trivial in-memory [dev.otgformat.core.SectorDevice], as the reference for the comparison above. */
private class ArraySectorDevice(
    override val sectorCount: Long,
    override val blockSize: Int,
) : dev.otgformat.core.SectorDevice {
    val store = ByteArray((sectorCount * blockSize).toInt())
    override fun read(sector: Long, dst: ByteArray) {
        val from = (sector * blockSize).toInt()
        store.copyInto(dst, 0, from, from + dst.size)
    }
    override fun write(sector: Long, src: ByteArray) { src.copyInto(store, (sector * blockSize).toInt()) }
    override fun flush() {}
}
