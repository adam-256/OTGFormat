package dev.otgformat.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VolumeLabelTest {

    @Test
    fun `encodes to 11 bytes, uppercase, space-padded, never null-terminated`() {
        val encoded = VolumeLabel.encode("boot")
        assertEquals(11, encoded.size)
        assertEquals("BOOT       ", String(encoded, Charsets.US_ASCII))
        // A trailing NUL would be read back as part of the name.
        assertContentEquals(ByteArray(7) { ' '.code.toByte() }, encoded.copyOfRange(4, 11))
    }

    @Test
    fun `an absent label becomes NO NAME, as every other formatter writes it`() {
        assertEquals("NO NAME    ", String(VolumeLabel.encode(null), Charsets.US_ASCII))
    }

    @Test
    fun `a full 11-character label fits exactly`() {
        assertEquals("ABCDEFGHIJK", String(VolumeLabel.encode("ABCDEFGHIJK"), Charsets.US_ASCII))
    }

    @Test
    fun `rejects labels the FAT label field cannot hold`() {
        assertFailsWith<FormatException> { FormatOptions(label = "TWELVECHARSX") }
        assertFailsWith<FormatException> { FormatOptions(label = "MY.DISK") }
        assertFailsWith<FormatException> { FormatOptions(label = "A/B") }
        assertFailsWith<FormatException> { FormatOptions(label = "TAB\there") }
        assertFailsWith<FormatException> { FormatOptions(label = "CAFÉ") }
    }

    @Test
    fun `the length error says what the limit is`() {
        val e = assertFailsWith<FormatException> { FormatOptions(label = "WAY TOO LONG LABEL") }
        assertEquals(true, "11" in e.message!!, e.message!!)
    }
}
