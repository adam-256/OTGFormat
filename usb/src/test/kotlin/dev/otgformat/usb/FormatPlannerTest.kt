package dev.otgformat.usb

import dev.otgformat.core.PartitionScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FormatPlannerTest {

    private fun target(bytes: Long) = UsbTarget(
        vendorName = "SanDisk", productName = "Cruzer", serialNumber = "S1",
        vendorId = 1, productId = 2, blockSize = 512, sectorCount = bytes / 512,
    )

    private val eightGig = target(8L * 1024 * 1024 * 1024)

    @Test
    fun `a valid configuration produces a plan and the options that will be used`() {
        val outcome = FormatPlanner.plan(eightGig, "MFLASH", 0, PartitionScheme.MBR, false)
        assertIs<PlanOutcome.Ready>(outcome)
        assertEquals("MFLASH", outcome.options.label)
        assertEquals(8, outcome.layout.sectorsPerCluster)
        assertEquals(2048L, outcome.layout.partitionStartLba)
    }

    @Test
    fun `an illegal label character is a message, not an exception`() {
        // FormatOptions throws from its constructor on a bad label. Building it
        // inline in a UI property getter would turn typing "." into a crash.
        for (bad in listOf("MY.DISK", "A/B", "A:B", "TAB\there")) {
            val outcome = FormatPlanner.plan(eightGig, bad, 0, PartitionScheme.MBR, false)
            assertIs<PlanOutcome.Rejected>(outcome, "\"$bad\" should be rejected, not thrown")
            assertTrue(outcome.reason.isNotBlank())
        }
    }

    @Test
    fun `an over-long label is a message, not an exception`() {
        val outcome = FormatPlanner.plan(eightGig, "TWELVECHARSX", 0, PartitionScheme.MBR, false)
        assertIs<PlanOutcome.Rejected>(outcome)
        assertTrue("11" in outcome.reason, outcome.reason)
    }

    @Test
    fun `an empty label plans as an unlabelled volume`() {
        val outcome = FormatPlanner.plan(eightGig, "   ", 0, PartitionScheme.MBR, false)
        assertIs<PlanOutcome.Ready>(outcome)
        assertEquals(null, outcome.options.label)
    }

    @Test
    fun `a cluster size too large for the volume is a message`() {
        val small = target(256L * 1024 * 1024)
        val outcome = FormatPlanner.plan(small, "X", 32 * 1024, PartitionScheme.MBR, false)
        assertIs<PlanOutcome.Rejected>(outcome)
        assertTrue("65525" in outcome.reason, outcome.reason)
    }

    @Test
    fun `a device too small for FAT32 is a message`() {
        val outcome = FormatPlanner.plan(target(16L * 1024 * 1024), "X", 0, PartitionScheme.MBR, false)
        assertIs<PlanOutcome.Rejected>(outcome)
        assertTrue("FAT32" in outcome.reason || "clusters" in outcome.reason, outcome.reason)
    }

    @Test
    fun `GPT is reported as unimplemented rather than silently writing MBR`() {
        val outcome = FormatPlanner.plan(eightGig, "X", 0, PartitionScheme.GPT, false)
        assertIs<PlanOutcome.Rejected>(outcome)
        assertTrue("GPT" in outcome.reason)
    }

    @Test
    fun `planning never touches the device`() {
        // PlanningDevice throws on any access, so reaching a plan at all proves
        // the drive was left alone while the user was still deciding.
        repeat(20) { FormatPlanner.plan(eightGig, "ABC", 4096, PartitionScheme.MBR, true) }
        val outcome = FormatPlanner.plan(eightGig, "ABC", 4096, PartitionScheme.MBR, true)
        assertIs<PlanOutcome.Ready>(outcome)
        assertTrue(outcome.options.bootable)
    }

    @Test
    fun `warnings from the plan survive to the UI`() {
        val outcome = FormatPlanner.plan(target(64L * 1024 * 1024), "SMALL", 0, PartitionScheme.MBR, false)
        assertIs<PlanOutcome.Ready>(outcome)
        assertTrue(
            outcome.layout.warnings.any { "too small for the standard" in it },
            "the step down to 512 B clusters must be reported: ${outcome.layout.warnings}",
        )
    }
}
