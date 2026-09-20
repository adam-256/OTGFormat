package dev.otgformat.core

/**
 * Little-endian field accessors.
 *
 * Every multi-byte field in an MBR, a FAT32 BPB and an FSInfo block is
 * little-endian, so these are the only primitives the layout writers use.
 * Values are treated as unsigned; the widening to [Int]/[Long] keeps callers
 * from having to reason about Kotlin's signed byte arithmetic at every field.
 */

fun ByteArray.putU8(offset: Int, value: Int) {
    require(value in 0..0xFF) { "u8 out of range at offset $offset: $value" }
    this[offset] = value.toByte()
}

fun ByteArray.putU16(offset: Int, value: Int) {
    require(value in 0..0xFFFF) { "u16 out of range at offset $offset: $value" }
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
}

fun ByteArray.putU32(offset: Int, value: Long) {
    require(value in 0..0xFFFF_FFFFL) { "u32 out of range at offset $offset: $value" }
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    this[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    this[offset + 3] = ((value ushr 24) and 0xFF).toByte()
}

fun ByteArray.getU8(offset: Int): Int = this[offset].toInt() and 0xFF

fun ByteArray.getU16(offset: Int): Int =
    getU8(offset) or (getU8(offset + 1) shl 8)

fun ByteArray.getU32(offset: Int): Long =
    (getU8(offset).toLong()) or
        (getU8(offset + 1).toLong() shl 8) or
        (getU8(offset + 2).toLong() shl 16) or
        (getU8(offset + 3).toLong() shl 24)
