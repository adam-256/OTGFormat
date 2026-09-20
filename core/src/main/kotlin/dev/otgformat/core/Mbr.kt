package dev.otgformat.core

/**
 * The master boot record: a single partition spanning the medium.
 */
object Mbr {

    /**
     * Where the partition starts. 1 MiB in, which is what every modern tool
     * does: it clears the legacy 63-sector reservation and lands on a boundary
     * that suits flash erase blocks.
     */
    const val FIRST_PARTITION_LBA = 2048L

    /** Partition type 0x0C: FAT32 with LBA access. */
    const val TYPE_FAT32_LBA = 0x0C

    const val PARTITION_TABLE_OFFSET = 0x1BE
    const val SIGNATURE_OFFSET = 0x1FE

    /**
     * CHS filler.
     *
     * CHS addressing is legacy and ignored by anything that understands LBA.
     * `0xFE 0xFF 0xFF` is the conventional "beyond CHS range" marker, and
     * writing it unconditionally is deliberate — computing real CHS values
     * gains nothing and is a reliable source of disagreement between tools.
     */
    private val CHS_BEYOND_RANGE = byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0xFF.toByte())

    /**
     * Builds sector 0.
     *
     * @param partitionSectors length of the partition in sectors.
     */
    fun build(
        bytesPerSector: Int,
        partitionStartLba: Long = FIRST_PARTITION_LBA,
        partitionSectors: Long,
        partitionType: Int = TYPE_FAT32_LBA,
        bootable: Boolean = false,
    ): ByteArray {
        require(partitionSectors > 0) { "partition must have at least one sector" }
        require(partitionStartLba + partitionSectors <= 0xFFFF_FFFFL) {
            "partition end exceeds the 32-bit LBA fields of an MBR; this medium needs GPT"
        }

        // Bootstrap area (0x000-0x1BD) and partition entries 2-4 stay zeroed.
        val sector = ByteArray(bytesPerSector)
        val p = PARTITION_TABLE_OFFSET

        sector.putU8(p + 0x00, if (bootable) 0x80 else 0x00)
        CHS_BEYOND_RANGE.copyInto(sector, p + 0x01)
        sector.putU8(p + 0x04, partitionType)
        CHS_BEYOND_RANGE.copyInto(sector, p + 0x05)
        sector.putU32(p + 0x08, partitionStartLba)
        sector.putU32(p + 0x0C, partitionSectors)

        sector.putU8(SIGNATURE_OFFSET, 0x55)
        sector.putU8(SIGNATURE_OFFSET + 1, 0xAA)
        return sector
    }
}
