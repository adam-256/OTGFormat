package dev.otgformat.usb

import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsbSelfTestTest {

    private fun report(driver: BlockDeviceDriver) = UsbSelfTest.run(driver)

    @Test
    fun `a healthy device passes every check`() {
        val report = report(FakeBlockDeviceDriver(trueSectorCount = 8192))
        println(report.asText())
        assertTrue(report.passed, report.asText())
        assertTrue("All checks passed" in report.headline)
    }

    @Test
    fun `the self-test never writes`() {
        // It is meant to be safe to run on a drive full of data.
        val fake = FakeBlockDeviceDriver(trueSectorCount = 8192)
        report(fake)
        assertTrue(fake.transfers.none { it.write }, "the self-test must not write a single sector")
    }

    @Test
    fun `it reports which capacity convention was applied`() {
        // The convention follows the driver's type, so any stand-in is read as
        // a block count; only ScsiBlockDevice reports a last address, and that
        // needs Android to instantiate.
        val text = report(FakeBlockDeviceDriver(trueSectorCount = 8192)).asText()
        assertTrue("as a block count" in text, text)
    }

    @Test
    fun `a device that answers past its own end is called out`() {
        val report = report(
            FakeBlockDeviceDriver(trueSectorCount = 8192, unboundedReads = true),
        )
        assertFalse(report.passed)
        assertTrue(
            report.notes.any { "counterfeit" in it },
            "a device reading past its capacity must be named for what it is: ${report.notes}",
        )
    }

    @Test
    fun `a device that will not initialise stops the run with a readable reason`() {
        val broken = object : BlockDeviceDriver {
            override val blockSize = 512
            override val blocks = 0L
            override fun init() = throw IOException("could not read capacity")
            override fun read(deviceOffset: Long, buffer: ByteBuffer) = Unit
            override fun write(deviceOffset: Long, buffer: ByteBuffer) = Unit
        }
        val report = report(broken)
        println(report.asText())
        assertFalse(report.passed)
        assertTrue("could not read capacity" in report.asText())
        // It must not carry on and report nonsense after the first failure.
        assertTrue(report.steps.size == 1, report.asText())
    }

    @Test
    fun `transfer sizes the bridge rejects are recorded rather than thrown`() {
        // Some bridges fail above a certain transfer size. That is exactly what
        // this project could not determine without hardware, so it is measured.
        val limited = object : BlockDeviceDriver by FakeBlockDeviceDriver(trueSectorCount = 8192) {
            private val inner = FakeBlockDeviceDriver(trueSectorCount = 8192)
            override val blockSize get() = inner.blockSize
            override val blocks get() = inner.blocks
            override fun init() = inner.init()
            override fun read(deviceOffset: Long, buffer: ByteBuffer) {
                if (buffer.remaining() > 128 * 1024) throw IOException("transfer too large")
                inner.read(deviceOffset, buffer)
            }
            override fun write(deviceOffset: Long, buffer: ByteBuffer) = inner.write(deviceOffset, buffer)
        }
        val report = report(limited)
        val text = report.asText()
        println(text)
        assertTrue("256 KiB rejected" in text, text)
        assertTrue("128 KiB" in text && "MB/s" in text, text)
    }

    @Test
    fun `the report says what is actually on the drive`() {
        // "Holds a partition table" is equally true before and after a format,
        // so it cannot answer the question people have afterwards. The report
        // has to say what the table and the filesystem actually claim.
        val blank = FakeBlockDeviceDriver(trueSectorCount = 8192)
        assertTrue("no partition table" in report(blank).asText())

        // 64 MiB is the smallest volume that is legal FAT32.
        val formatted = FakeBlockDeviceDriver(trueSectorCount = 64L * 1024 * 1024 / 512)
        writeFat32Volume(formatted, label = "MFLASH")
        val text = report(formatted).asText()
        println(text)
        assertTrue("type 0x0c (FAT32 LBA)" in text, text)
        assertTrue("start sector 2048" in text, text)
        assertTrue("FAT32" in text, text)
        assertTrue("\"MFLASH\"" in text, text)
        assertTrue("consistent with a volume this app wrote" in text, text)
        // The cluster size must never render as "0 KiB", which is what a plain
        // division does to a 512-byte cluster.
        assertTrue(!Regex("""(^|[^\d.])0 (B|KiB|MiB)""").containsMatchIn(text), text)
    }

    /** Formats the fake with the real formatter, so the report reads a genuine volume. */
    private fun writeFat32Volume(fake: FakeBlockDeviceDriver, label: String) {
        val device = LibaumsSectorDevice(fake, fake.blockSize, fake.trueSectorCount, 64)
        dev.otgformat.core.Formatter.format(
            device,
            dev.otgformat.core.FormatOptions(label = label, volumeSerial = 1),
        )
    }
}
