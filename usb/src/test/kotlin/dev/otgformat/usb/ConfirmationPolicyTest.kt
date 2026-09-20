package dev.otgformat.usb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UsbTargetTest {

    private fun target(sectorCount: Long, blockSize: Int = 512) = UsbTarget(
        vendorName = "SanDisk",
        productName = "Cruzer Blade",
        serialNumber = "4C530001120607117005",
        vendorId = 0x0781,
        productId = 0x5567,
        blockSize = blockSize,
        sectorCount = sectorCount,
    )

    @Test
    fun `capacity is reported in decimal GB, matching the number on the casing`() {
        // A stick sold as 64GB reports about 61.5 decimal GB. Showing 57.3 GiB
        // instead would leave the user unable to match it to the label.
        val stick = target(61_530_439_680L / 512)
        assertEquals("61.5 GB", stick.capacityLabel())
    }

    @Test
    fun `small devices are shown in MB`() {
        assertEquals("64 MB", target(64L * 1_000_000 / 512).capacityLabel())
    }

    @Test
    fun `identity falls back to USB IDs when the device reports no strings`() {
        val anonymous = target(1000).copy(vendorName = null, productName = "  ")
        assertEquals("USB device 0781:5567", anonymous.displayName())
    }

    @Test
    fun `the confirmation block carries everything needed to identify the device by hand`() {
        val text = target(1000).describe()
        assertTrue("SanDisk Cruzer Blade" in text)
        assertTrue("4C530001120607117005" in text, "the serial distinguishes two identical sticks")
        assertTrue("0781:5567" in text)
        assertTrue("512 KB" in text || "capacity" in text)
    }

    @Test
    fun `a missing serial is stated rather than left blank`() {
        val text = target(1000).copy(serialNumber = null).describe()
        assertTrue("(not reported)" in text, text)
    }
}

class ConfirmationPolicyTest {

    private fun targetOfBytes(bytes: Long) = UsbTarget(
        vendorName = "Generic", productName = "Flash Disk", serialNumber = "X1",
        vendorId = 1, productId = 2, blockSize = 512, sectorCount = bytes / 512,
    )

    @Test
    fun `an ordinary boot stick only needs acknowledgement`() {
        // A nominal 64GB stick reports well under the threshold, so routine
        // work is not buried under a typing ritual that trains people to
        // dismiss it unread.
        val confirmation = ConfirmationPolicy.forTarget(targetOfBytes(61_530_439_680L), "MFLASH")
        assertIs<Confirmation.Acknowledge>(confirmation)
        assertTrue("Generic Flash Disk" in confirmation.summary)
    }

    @Test
    fun `a device larger than a boot stick requires typing`() {
        val confirmation = ConfirmationPolicy.forTarget(targetOfBytes(128_000_000_000L), "MFLASH")
        assertIs<Confirmation.TypeToConfirm>(confirmation)
        assertEquals("MFLASH", confirmation.phrase, "the label is the better phrase: it proves the user read it")
        assertTrue("128.0 GB" in confirmation.reason)
        assertTrue("external drive" in confirmation.reason)
    }

    @Test
    fun `an unlabelled large device falls back to a fixed phrase`() {
        val confirmation = ConfirmationPolicy.forTarget(targetOfBytes(500_000_000_000L), null)
        assertIs<Confirmation.TypeToConfirm>(confirmation)
        assertEquals("FORMAT", confirmation.phrase)

        val blank = ConfirmationPolicy.forTarget(targetOfBytes(500_000_000_000L), "   ")
        assertEquals("FORMAT", (blank as Confirmation.TypeToConfirm).phrase)
    }

    @Test
    fun `the threshold is applied at exactly the documented boundary`() {
        val at = ConfirmationPolicy.forTarget(targetOfBytes(ConfirmationPolicy.TYPE_TO_CONFIRM_ABOVE_BYTES), "X")
        assertIs<Confirmation.Acknowledge>(at, "the rule is 'above', so the boundary itself does not trip it")

        val above = ConfirmationPolicy.forTarget(
            targetOfBytes(ConfirmationPolicy.TYPE_TO_CONFIRM_ABOVE_BYTES + 512), "X",
        )
        assertIs<Confirmation.TypeToConfirm>(above)
    }

    @Test
    fun `typed confirmation is checked case and whitespace insensitively`() {
        val confirmation = ConfirmationPolicy.forTarget(targetOfBytes(128_000_000_000L), "MFLASH")
        assertTrue(ConfirmationPolicy.isSatisfied(confirmation, "mflash"))
        assertTrue(ConfirmationPolicy.isSatisfied(confirmation, "  MFLASH  "))
        assertFalse(ConfirmationPolicy.isSatisfied(confirmation, "MFLAS"))
        assertFalse(ConfirmationPolicy.isSatisfied(confirmation, ""))
        assertFalse(ConfirmationPolicy.isSatisfied(confirmation, null))
    }

    @Test
    fun `acknowledgement needs no typed text`() {
        val confirmation = ConfirmationPolicy.forTarget(targetOfBytes(8_000_000_000L), "BOOT")
        assertTrue(ConfirmationPolicy.isSatisfied(confirmation, null))
    }
}
