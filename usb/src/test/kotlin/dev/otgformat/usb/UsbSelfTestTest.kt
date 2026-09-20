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
    fun `it reports which capacity convention the driver uses`() {
        val lastAddress = report(
            FakeBlockDeviceDriver(trueSectorCount = 8192, convention = CapacityConvention.LAST_BLOCK_ADDRESS),
        ).asText()
        assertTrue("from the last address" in lastAddress, lastAddress)

        val count = report(
            FakeBlockDeviceDriver(trueSectorCount = 8192, convention = CapacityConvention.BLOCK_COUNT),
        ).asText()
        assertTrue("in whole blocks" in count, count)
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
    fun `the report says what is currently on the first sector`() {
        val fake = FakeBlockDeviceDriver(trueSectorCount = 8192)
        assertTrue("currently blank" in report(fake).asText())

        val withTable = FakeBlockDeviceDriver(trueSectorCount = 8192)
        withTable.store[510] = 0x55
        withTable.store[511] = 0xAA.toByte()
        assertTrue("partition table or boot sector" in report(withTable).asText())
    }
}
