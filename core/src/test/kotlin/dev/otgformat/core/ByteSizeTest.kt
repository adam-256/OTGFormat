package dev.otgformat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ByteSizeTest {

    @Test
    fun `renders each unit without truncating small values to zero`() {
        // The bug this guards: 512 / 1024 == 0, which put "0 KiB" in a warning
        // shown on the confirmation screen.
        assertEquals("512 B", formatBytes(512))
        assertEquals("4 KiB", formatBytes(4096))
        assertEquals("32 KiB", formatBytes(32 * 1024))
        assertEquals("2 MiB", formatBytes(2 * 1024 * 1024))
        assertEquals("64 GiB", formatBytes(64L * 1024 * 1024 * 1024))
    }

    @Test
    fun `sizes that are not whole units keep a decimal rather than dropping to raw bytes`() {
        assertEquals("1.5 KiB", formatBytes(1536))
        assertEquals("62.0 MiB", formatBytes(65_019_392))
        assertEquals("1.0 MiB", formatBytes(1025 * 1024), "always uses the largest unit that fits")
        assertEquals("500 KiB", formatBytes(1000 * 512), "an exact multiple stays a whole number")
    }

    @Test
    fun `no user-facing message can report a zero-sized cluster`() {
        // 64 MiB forces the step down to 512-byte clusters.
        val layout = Formatter.plan(UntouchableDevice(sectorCount = 64L * 1024 * 1024 / 512), FormatOptions())
        val text = layout.describe() + layout.warnings.joinToString(" ")
        // Word-boundary matched: "500 KiB each" contains "0 KiB" as a substring
        // but reports nothing zero-sized.
        val zeroQuantity = Regex("""(^|[^\d.])0 (B|KiB|MiB|GiB)""")
        assertTrue(
            !zeroQuantity.containsMatchIn(text),
            "a message reported a zero-sized quantity:\n$text",
        )
        assertTrue("512 B" in text, "512-byte clusters should be named as such:\n$text")
    }
}
