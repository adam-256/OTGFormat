package dev.otgformat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LittleEndianTest {

    @Test
    fun `u16 writes low byte first`() {
        val b = ByteArray(4)
        b.putU16(0, 0xAA55)
        assertEquals(0x55, b.getU8(0))
        assertEquals(0xAA, b.getU8(1))
    }

    @Test
    fun `u32 writes low byte first`() {
        val b = ByteArray(8)
        b.putU32(0, 0x0FFF_FFF8L)
        assertEquals(listOf(0xF8, 0xFF, 0xFF, 0x0F), (0..3).map { b.getU8(it) })
    }

    @Test
    fun `round trips the full unsigned range`() {
        val b = ByteArray(8)
        b.putU32(0, 0xFFFF_FFFFL)
        assertEquals(0xFFFF_FFFFL, b.getU32(0))
        b.putU16(4, 0xFFFF)
        assertEquals(0xFFFF, b.getU16(4))
    }

    @Test
    fun `rejects out-of-range values rather than truncating`() {
        // Silent truncation here would write a plausible-looking wrong field.
        assertFailsWith<IllegalArgumentException> { ByteArray(4).putU16(0, 0x10000) }
        assertFailsWith<IllegalArgumentException> { ByteArray(8).putU32(0, 0x1_0000_0000L) }
        assertFailsWith<IllegalArgumentException> { ByteArray(4).putU8(0, 256) }
    }
}
