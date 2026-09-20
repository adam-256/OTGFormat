package dev.otgformat.core

/**
 * The FAT32 boot sector / BIOS Parameter Block.
 *
 * Built as a detached byte array rather than written in place, so that tests
 * can diff it field-by-field against one produced by `mkfs.vfat` without going
 * anywhere near a device.
 */
object BootSector {

    const val OFF_JUMP = 0x00
    const val OFF_OEM = 0x03
    const val OFF_BYTES_PER_SECTOR = 0x0B
    const val OFF_SECTORS_PER_CLUSTER = 0x0D
    const val OFF_RESERVED_SECTORS = 0x0E
    const val OFF_NUM_FATS = 0x10
    const val OFF_ROOT_ENTRY_COUNT = 0x11
    const val OFF_TOTAL_SECTORS_16 = 0x13
    const val OFF_MEDIA = 0x15
    const val OFF_FAT_SIZE_16 = 0x16
    const val OFF_SECTORS_PER_TRACK = 0x18
    const val OFF_NUM_HEADS = 0x1A
    const val OFF_HIDDEN_SECTORS = 0x1C
    const val OFF_TOTAL_SECTORS_32 = 0x20
    const val OFF_FAT_SIZE_32 = 0x24
    const val OFF_EXT_FLAGS = 0x28
    const val OFF_FS_VERSION = 0x2A
    const val OFF_ROOT_CLUSTER = 0x2C
    const val OFF_FS_INFO = 0x30
    const val OFF_BACKUP_BOOT = 0x32
    const val OFF_DRIVE_NUMBER = 0x40
    const val OFF_EXT_BOOT_SIG = 0x42
    const val OFF_VOLUME_SERIAL = 0x43
    const val OFF_VOLUME_LABEL = 0x47
    const val OFF_FS_TYPE = 0x52
    const val OFF_SIGNATURE = 0x1FE

    /** `0xF8` — fixed disk. What every removable FAT volume actually uses. */
    const val MEDIA_FIXED = 0xF8

    /** "MSWIN4.1" is the most widely accepted OEM string; some old drivers sniff it. */
    private const val OEM_NAME = "MSWIN4.1"

    fun build(layout: Fat32Layout, label: String?, volumeSerial: Int): ByteArray {
        val s = ByteArray(layout.bytesPerSector)

        // Short jump over the BPB, then a NOP. Required even on media that will
        // never be booted: drivers check it.
        s.putU8(OFF_JUMP, 0xEB)
        s.putU8(OFF_JUMP + 1, 0x58)
        s.putU8(OFF_JUMP + 2, 0x90)
        ascii(OEM_NAME, 8).copyInto(s, OFF_OEM)

        s.putU16(OFF_BYTES_PER_SECTOR, layout.bytesPerSector)
        s.putU8(OFF_SECTORS_PER_CLUSTER, layout.sectorsPerCluster)
        s.putU16(OFF_RESERVED_SECTORS, layout.reservedSectors)
        s.putU8(OFF_NUM_FATS, layout.numFats)
        s.putU16(OFF_ROOT_ENTRY_COUNT, 0)   // FAT32: the root is a normal cluster chain
        s.putU16(OFF_TOTAL_SECTORS_16, 0)   // FAT32: always the 32-bit field instead
        s.putU8(OFF_MEDIA, MEDIA_FIXED)
        s.putU16(OFF_FAT_SIZE_16, 0)        // FAT32: always the 32-bit field instead
        s.putU16(OFF_SECTORS_PER_TRACK, 63)
        s.putU16(OFF_NUM_HEADS, 255)
        s.putU32(OFF_HIDDEN_SECTORS, layout.partitionStartLba)
        s.putU32(OFF_TOTAL_SECTORS_32, layout.totalSectors)
        s.putU32(OFF_FAT_SIZE_32, layout.fatSizeSectors)
        s.putU16(OFF_EXT_FLAGS, 0)          // 0 = all FATs mirrored and live
        s.putU16(OFF_FS_VERSION, 0)
        s.putU32(OFF_ROOT_CLUSTER, Fat32Layout.ROOT_CLUSTER)
        s.putU16(OFF_FS_INFO, Fat32Layout.FSINFO_SECTOR.toInt())
        s.putU16(OFF_BACKUP_BOOT, Fat32Layout.BACKUP_BOOT_SECTOR.toInt())
        // 0x34..0x3F reserved, left zero.

        s.putU8(OFF_DRIVE_NUMBER, 0x80)
        s.putU8(OFF_DRIVE_NUMBER + 1, 0x00)
        s.putU8(OFF_EXT_BOOT_SIG, 0x29)
        s.putU32(OFF_VOLUME_SERIAL, volumeSerial.toLong() and 0xFFFF_FFFFL)
        VolumeLabel.encode(label).copyInto(s, OFF_VOLUME_LABEL)
        ascii("FAT32", 8).copyInto(s, OFF_FS_TYPE)

        // Boot code area is left zeroed; there is nothing to boot.
        s.putU8(OFF_SIGNATURE, 0x55)
        s.putU8(OFF_SIGNATURE + 1, 0xAA)
        return s
    }

    /** Space-padded fixed-width ASCII, as FAT stores text everywhere. */
    private fun ascii(text: String, width: Int): ByteArray {
        require(text.length <= width)
        val out = ByteArray(width) { ' '.code.toByte() }
        for (i in text.indices) out[i] = text[i].code.toByte()
        return out
    }
}

/**
 * The FSInfo sector.
 *
 * Its free-cluster count is a hint, not authority — drivers recompute it when
 * they distrust it, and `fsck` will not fail over a wrong value. It is still
 * written correctly, because a wrong hint makes the first write to the stick
 * scan the entire FAT.
 */
object FsInfo {

    const val OFF_LEAD_SIGNATURE = 0x000
    const val OFF_STRUCT_SIGNATURE = 0x1E4
    const val OFF_FREE_COUNT = 0x1E8
    const val OFF_NEXT_FREE = 0x1EC
    const val OFF_TRAIL_SIGNATURE = 0x1FC

    const val LEAD_SIGNATURE = 0x4161_5252L
    const val STRUCT_SIGNATURE = 0x6141_7272L
    const val TRAIL_SIGNATURE = 0xAA55_0000L

    fun build(bytesPerSector: Int, freeClusters: Long, nextFreeCluster: Long): ByteArray {
        val s = ByteArray(bytesPerSector)
        s.putU32(OFF_LEAD_SIGNATURE, LEAD_SIGNATURE)
        s.putU32(OFF_STRUCT_SIGNATURE, STRUCT_SIGNATURE)
        s.putU32(OFF_FREE_COUNT, freeClusters)
        s.putU32(OFF_NEXT_FREE, nextFreeCluster)
        s.putU32(OFF_TRAIL_SIGNATURE, TRAIL_SIGNATURE)
        return s
    }
}

/** Directory entries. Only the volume label entry is needed to create a volume. */
object DirectoryEntry {

    const val SIZE = 32
    const val ATTR_VOLUME_ID = 0x08

    /**
     * The root directory's volume-label entry.
     *
     * A FAT32 volume carries its label twice: here and in the BPB. Checkers
     * compare them, so both are written from the same encoding.
     */
    fun volumeLabel(label: String): ByteArray {
        val e = ByteArray(SIZE)
        VolumeLabel.encode(label).copyInto(e, 0)
        e.putU8(11, ATTR_VOLUME_ID)
        return e
    }
}

/** The first three FAT entries, which every FAT32 volume must start with. */
object FatEntries {

    /** FAT[0]: media descriptor in the low byte, the rest set. */
    const val ENTRY_0 = 0x0FFF_FFF8L

    /**
     * FAT[1]: end-of-chain, with the "cleanly unmounted" and "no hard errors"
     * bits both set. A driver that finds these clear runs a repair pass.
     */
    const val ENTRY_1 = 0x0FFF_FFFFL

    /** FAT[2]: the root directory — one cluster, end of chain. */
    const val ENTRY_2 = 0x0FFF_FFFFL
}
