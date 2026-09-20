package dev.otgformat.core

/** Filesystems this project can write. */
enum class Filesystem {
    FAT32,
}

/** How the medium is partitioned. */
enum class PartitionScheme {
    /** Classic MBR with a single partition starting at LBA 2048. */
    MBR,

    /** GPT. Phase 2 — deliberately unimplemented rather than half-implemented. */
    GPT,

    /**
     * No partition table: the filesystem starts at sector 0 of the medium.
     *
     * Some USB sticks ship this way and some BIOSes prefer it, but MSI's
     * M-FLASH and most UEFI implementations expect a partition table, so [MBR]
     * is the default.
     */
    SUPERFLOPPY,
}

/** Cluster size selection. */
sealed class ClusterSize {

    /** Pick a cluster size from the volume size. See [ClusterSizeTable]. */
    object Auto : ClusterSize()

    /** Use exactly this many bytes per cluster. Must be a power of two. */
    data class Bytes(val bytes: Int) : ClusterSize() {
        init {
            require(bytes > 0 && bytes and (bytes - 1) == 0) {
                "cluster size must be a power of two, got $bytes"
            }
        }
    }
}

/**
 * Everything the user controls about a format.
 *
 * @param volumeSerial fixes the FAT32 volume serial. Left null it is derived
 *   from the wall clock, which is what real formatters do; tests set it so that
 *   output is byte-for-byte reproducible.
 * @param wipeStaleSignatures zeroes the gap between the MBR and the partition
 *   start, plus the last sectors of the medium. This costs about 1 MiB of
 *   writing and removes stale GPT headers left by a previous format — a
 *   leftover GPT beside a fresh MBR is exactly the hybrid-table confusion that
 *   makes firmware refuse a stick for no visible reason.
 */
data class FormatOptions(
    val filesystem: Filesystem = Filesystem.FAT32,
    val clusterSize: ClusterSize = ClusterSize.Auto,
    val label: String? = null,
    val partitionScheme: PartitionScheme = PartitionScheme.MBR,
    val bootable: Boolean = false,
    val volumeSerial: Int? = null,
    val wipeStaleSignatures: Boolean = true,
) {
    init {
        if (label != null) VolumeLabel.validate(label)
    }
}

/**
 * FAT volume label rules.
 *
 * The label is an 11-byte space-padded field, not a C string: it is neither
 * null-terminated nor length-prefixed, and a trailing NUL would be read back as
 * part of the name.
 */
object VolumeLabel {

    const val LENGTH = 11

    /** Characters FAT forbids in a short name, and therefore in a label. */
    private const val ILLEGAL = "\"*+,./:;<=>?[\\]|"

    fun validate(label: String) {
        if (label.length > LENGTH) {
            throw FormatException(
                "Volume label \"$label\" is ${label.length} characters; the FAT label field holds $LENGTH."
            )
        }
        for (c in label) {
            if (c.code < 0x20) {
                throw FormatException("Volume label may not contain control characters.")
            }
            if (c in ILLEGAL) {
                throw FormatException("Volume label may not contain '$c'. Forbidden: $ILLEGAL")
            }
            if (c.code > 0x7E) {
                throw FormatException(
                    "Volume label may only contain printable ASCII; '$c' is not. " +
                        "Non-ASCII labels need a DOS codepage and are read back differently on different systems."
                )
            }
        }
    }

    /** Encodes to the on-disk 11-byte form: uppercase, space-padded, never null-terminated. */
    fun encode(label: String?): ByteArray {
        val text = (label ?: "NO NAME").uppercase()
        val out = ByteArray(LENGTH) { ' '.code.toByte() }
        for (i in text.indices) out[i] = text[i].code.toByte()
        return out
    }
}
