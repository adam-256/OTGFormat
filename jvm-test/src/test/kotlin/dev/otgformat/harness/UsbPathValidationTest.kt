package dev.otgformat.harness

import dev.otgformat.core.FormatOptions
import dev.otgformat.core.Formatter
import dev.otgformat.usb.LibaumsSectorDevice
import me.jahnen.libaums.core.driver.file.FileBlockDeviceDriver
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives a format through the *real* libaums stack and validates the result
 * with `fsck.vfat`.
 *
 * `FileBlockDeviceDriver` is libaums' own file-backed `BlockDeviceDriver`. It
 * is block-addressed and reads its buffers with `array()` and `position()` —
 * the same shape `ScsiBlockDevice` uses over USB. So this exercises the exact
 * path the Android app will take (`Formatter` → `LibaumsSectorDevice` →
 * libaums) against the same oracle Phase 0 used, with the only untested link
 * being the USB transport itself.
 */
class UsbPathValidationTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireTools() {
            assumeTrue(ExternalTools.available("fsck.vfat"), "fsck.vfat not installed (package: dosfstools)")
        }
    }

    private fun sparseImage(name: String, sizeBytes: Long): File {
        val file = Images.image(name)
        FileSectorDevice.create(file, sizeBytes).close()
        return file
    }

    @ParameterizedTest(name = "{0} MiB formatted through libaums passes fsck.vfat")
    @ValueSource(longs = [64, 2048, 8192])
    fun `a format driven through libaums produces a clean filesystem`(sizeMib: Long) {
        val file = sparseImage("usbpath-$sizeMib.img", sizeMib * Images.MIB)
        val driver = FileBlockDeviceDriver(file, 0, 512)
        val device = LibaumsSectorDevice.open(driver)

        assertEquals(
            sizeMib * Images.MIB / 512,
            device.sectorCount,
            "the adapter must see the whole medium",
        )

        val result = Formatter.format(device, FormatOptions(label = "VIAUSB", volumeSerial = 0x1234_ABCD))
        println("--- $sizeMib MiB through libaums ---")
        print(result.layout.describe())

        FileSectorDevice.create0(file).use { disk ->
            val partition = PartitionExtractor.extract(
                disk,
                result.layout.partitionStartLba,
                result.layout.totalSectors,
                File(file.parentFile, "usbpath-$sizeMib.part.img"),
            )
            val fsck = ExternalTools.fsck(partition)
            println(fsck.output.lines().takeLast(2).joinToString("\n"))
            assertTrue(fsck.ok, "fsck.vfat rejected a volume formatted through libaums:\n${fsck.output}")

            // The geometry fsck derives must match what core planned, exactly
            // as in the direct-to-file case.
            val report = FsckReport.parse(fsck.output)
            assertEquals(result.layout.countOfClusters, report.dataClusters)
            assertEquals(result.layout.fatSizeSectors, report.fatSizeSectors)
            assertEquals(result.layout.partitionStartLba, report.hiddenSectors)
        }
    }

    @Test
    fun `the volume formatted through libaums round-trips real files`() {
        assumeTrue(Mtools.available(), "mtools not installed")
        val file = sparseImage("usbpath-roundtrip.img", 2048 * Images.MIB)
        val device = LibaumsSectorDevice.open(FileBlockDeviceDriver(file, 0, 512))
        val result = Formatter.format(device, FormatOptions(label = "VIAUSB", volumeSerial = 1))

        FileSectorDevice.create0(file).use { disk ->
            val partition = PartitionExtractor.extract(
                disk,
                result.layout.partitionStartLba,
                result.layout.totalSectors,
                File(file.parentFile, "usbpath-roundtrip.part.img"),
            )
            val payload = File(Images.scratch, "usb-payload.bin").also {
                it.writeBytes(ByteArray(1 shl 20) { i -> (i % 251).toByte() })
            }
            assertTrue(Mtools.copyIn(partition, payload, "::/VIAUSB.BIN").ok)
            assertTrue(ExternalTools.fsck(partition).ok, "volume became inconsistent after a write")

            val back = File(Images.scratch, "usb-payload-back.bin")
            if (back.exists()) back.delete()
            assertTrue(Mtools.copyOut(partition, "::/VIAUSB.BIN", back).ok)
            assertTrue(payload.readBytes().contentEquals(back.readBytes()), "file did not survive the round trip")
        }
    }

    @Test
    fun `formatting through libaums and straight to a file produce identical media`() {
        // Removes any doubt that the adapter perturbs what gets written.
        val size = 64 * Images.MIB
        val options = FormatOptions(label = "IDENTICAL", volumeSerial = 0x0BADC0DE)

        val viaUsb = sparseImage("identical-usb.img", size)
        Formatter.format(LibaumsSectorDevice.open(FileBlockDeviceDriver(viaUsb, 0, 512)), options)

        val direct = sparseImage("identical-direct.img", size)
        FileSectorDevice.create0(direct).use { Formatter.format(it, options) }

        assertTrue(
            viaUsb.readBytes().contentEquals(direct.readBytes()),
            "the libaums path and the direct path must produce byte-identical media",
        )
    }
}
