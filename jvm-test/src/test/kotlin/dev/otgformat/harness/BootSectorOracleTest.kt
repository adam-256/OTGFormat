package dev.otgformat.harness

import dev.otgformat.core.BootSector
import dev.otgformat.core.FatEntries
import dev.otgformat.core.FormatOptions
import dev.otgformat.core.FsInfo
import dev.otgformat.core.getU32
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Acceptance criterion 4, the highest-value test in Phase 0.
 *
 * Our boot sector is compared field-by-field against one `mkfs.vfat` produces
 * from the same parameters. Fields are classified up front: [Match.STRICT]
 * fields must be byte-identical, and every other classification carries a
 * reason. A difference that is not in this table fails the test — the point is
 * that divergence from the reference implementation has to be argued for, not
 * discovered later on a stick that will not boot.
 */
class BootSectorOracleTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireTools() {
            assumeTrue(
                ExternalTools.available("mkfs.vfat"),
                "mkfs.vfat not installed (package: dosfstools) — cannot diff against the reference implementation",
            )
        }
    }

    /** FAT32 reads any entry at or above this value as end-of-chain. */
    private val END_OF_CHAIN_MIN = 0x0FFF_FFF8L

    private enum class Match { STRICT, DOCUMENTED, FAT_SIZE }

    private data class Field(
        val name: String,
        val offset: Int,
        val size: Int,
        val match: Match = Match.STRICT,
        val reason: String = "",
    )

    private val fields = listOf(
        Field("jump instruction", BootSector.OFF_JUMP, 3),
        Field(
            "OEM name", BootSector.OFF_OEM, 8, Match.DOCUMENTED,
            "cosmetic; we write \"MSWIN4.1\", the most widely accepted value, mkfs writes \"mkfs.fat\"",
        ),
        Field("bytes per sector", BootSector.OFF_BYTES_PER_SECTOR, 2),
        Field("sectors per cluster", BootSector.OFF_SECTORS_PER_CLUSTER, 1),
        Field("reserved sectors", BootSector.OFF_RESERVED_SECTORS, 2),
        Field("number of FATs", BootSector.OFF_NUM_FATS, 1),
        Field("root entry count", BootSector.OFF_ROOT_ENTRY_COUNT, 2),
        Field("total sectors 16", BootSector.OFF_TOTAL_SECTORS_16, 2),
        Field("media descriptor", BootSector.OFF_MEDIA, 1),
        Field("FAT size 16", BootSector.OFF_FAT_SIZE_16, 2),
        Field("sectors per track", BootSector.OFF_SECTORS_PER_TRACK, 2),
        Field("number of heads", BootSector.OFF_NUM_HEADS, 2),
        Field("hidden sectors", BootSector.OFF_HIDDEN_SECTORS, 4),
        Field("total sectors 32", BootSector.OFF_TOTAL_SECTORS_32, 4),
        Field(
            "FAT size 32", BootSector.OFF_FAT_SIZE_32, 4, Match.FAT_SIZE,
            "fatgen103's closed form over-allocates; mkfs computes the exact minimum. " +
                "Ours must never be smaller.",
        ),
        Field("ext flags", BootSector.OFF_EXT_FLAGS, 2),
        Field("filesystem version", BootSector.OFF_FS_VERSION, 2),
        Field("root cluster", BootSector.OFF_ROOT_CLUSTER, 4),
        Field("FSInfo sector", BootSector.OFF_FS_INFO, 2),
        Field("backup boot sector", BootSector.OFF_BACKUP_BOOT, 2),
        Field("reserved (0x34)", 0x34, 12),
        Field("drive number", BootSector.OFF_DRIVE_NUMBER, 1),
        Field("reserved (0x41)", 0x41, 1),
        Field("extended boot signature", BootSector.OFF_EXT_BOOT_SIG, 1),
        Field("volume serial", BootSector.OFF_VOLUME_SERIAL, 4),
        Field("volume label", BootSector.OFF_VOLUME_LABEL, 11),
        Field("filesystem type", BootSector.OFF_FS_TYPE, 8),
        Field(
            "boot code", 0x5A, 0x1FE - 0x5A, Match.DOCUMENTED,
            "mkfs embeds a \"not bootable\" message stub; we leave the area zeroed, as there is nothing to boot",
        ),
        Field("signature", BootSector.OFF_SIGNATURE, 2),
    )

    @ParameterizedTest(name = "{0} MiB boot sector matches mkfs.vfat structurally")
    @ValueSource(longs = [64, 2048, 8192, 65536])
    fun `boot sector agrees with mkfs vfat on every structural field`(sizeMib: Long) {
        val label = "OTGTEST"
        val size = sizeMib * Images.MIB
        val ours = Images.format(
            "oracle-ours-$sizeMib.img",
            size,
            FormatOptions(label = label, volumeSerial = MkfsOracle.INVARIANT_VOLUME_ID),
        )
        try {
            val layout = ours.result.layout
            val theirs = MkfsOracle.format("oracle-mkfs-$sizeMib.img", size, layout, label)
            val a = ours.device.readSector(layout.partitionStartLba)
            val b = theirs.bootSector

            println()
            println("=== $sizeMib MiB : boot sector vs mkfs.vfat ===")
            println("oracle command: ${theirs.command}")
            println(
                "%-24s %-6s %-22s %-22s %s".format("FIELD", "OFFSET", "OURS", "MKFS.VFAT", "VERDICT"),
            )

            val failures = mutableListOf<String>()
            for (f in fields) {
                val mine = a.copyOfRange(f.offset, f.offset + f.size)
                val ref = b.copyOfRange(f.offset, f.offset + f.size)
                val same = mine.contentEquals(ref)

                val verdict = when {
                    same -> "match"
                    f.match == Match.DOCUMENTED -> "differs (expected: ${f.reason})"
                    f.match == Match.FAT_SIZE -> {
                        val mineVal = a.getU32(f.offset)
                        val refVal = b.getU32(f.offset)
                        if (mineVal < refVal) {
                            failures += "${f.name}: ours ($mineVal) is SMALLER than mkfs ($refVal) — " +
                                "the FAT cannot describe every cluster"
                            "UNDER-ALLOCATED"
                        } else {
                            "ours +${mineVal - refVal} sectors (expected: ${f.reason})"
                        }
                    }
                    else -> {
                        failures += "${f.name} at 0x%02X: ours=%s mkfs=%s".format(f.offset, hex(mine), hex(ref))
                        "MISMATCH"
                    }
                }
                println("%-24s 0x%04X %-22s %-22s %s".format(f.name, f.offset, hex(mine), hex(ref), verdict))
            }

            // Nothing outside the table may differ either.
            val tableCovered = fields.flatMap { (it.offset until it.offset + it.size) }.toSet()
            val uncovered = (0 until layout.bytesPerSector).filter { it !in tableCovered && a[it] != b[it] }
            if (uncovered.isNotEmpty()) {
                failures += "bytes differ outside the field table at offsets " +
                    uncovered.joinToString(", ") { "0x%02X".format(it) }
            }

            assertTrue(failures.isEmpty(), "structural disagreement with mkfs.vfat:\n" + failures.joinToString("\n"))
        } finally {
            ours.close()
        }
    }

    @Test
    fun `FSInfo agrees with mkfs vfat on its signatures`() {
        val size = 2048 * Images.MIB
        val ours = Images.format(
            "oracle-fsinfo.img",
            size,
            FormatOptions(label = "OTGTEST", volumeSerial = MkfsOracle.INVARIANT_VOLUME_ID),
        )
        try {
            val layout = ours.result.layout
            val theirs = MkfsOracle.format("oracle-fsinfo-mkfs.img", size, layout, "OTGTEST")
            val a = ours.device.readSector(layout.partitionStartLba + 1)
            val b = theirs.fsInfoSector

            for ((name, off) in listOf(
                "lead signature" to FsInfo.OFF_LEAD_SIGNATURE,
                "struct signature" to FsInfo.OFF_STRUCT_SIGNATURE,
                "trail signature" to FsInfo.OFF_TRAIL_SIGNATURE,
            )) {
                assertEquals(b.getU32(off), a.getU32(off), "FSInfo $name")
            }

            // The free-cluster count necessarily differs: it follows from the
            // cluster count, which follows from the FAT size.
            println(
                "FSInfo free clusters: ours=${a.getU32(FsInfo.OFF_FREE_COUNT)} " +
                    "mkfs=${b.getU32(FsInfo.OFF_FREE_COUNT)} (differs with FAT size, as expected)",
            )
            assertEquals(
                layout.countOfClusters - 1,
                a.getU32(FsInfo.OFF_FREE_COUNT),
                "our free count must be the cluster count less the root directory's cluster",
            )
        } finally {
            ours.close()
        }
    }

    @Test
    fun `the first FAT entries agree with mkfs vfat exactly`() {
        val size = 2048 * Images.MIB
        val ours = Images.format(
            "oracle-fat.img",
            size,
            FormatOptions(label = "OTGTEST", volumeSerial = MkfsOracle.INVARIANT_VOLUME_ID),
        )
        try {
            val layout = ours.result.layout
            val theirs = MkfsOracle.format("oracle-fat-mkfs.img", size, layout, "OTGTEST")
            val a = ours.device.readSector(layout.partitionStartLba + layout.firstFatSector)
            val b = theirs.firstFatSector

            assertEquals(b.getU32(0), a.getU32(0), "FAT[0] media descriptor entry")
            assertEquals(b.getU32(4), a.getU32(4), "FAT[1] end-of-chain and clean-shutdown flags")
            assertEquals(FatEntries.ENTRY_0, a.getU32(0))
            assertEquals(FatEntries.ENTRY_1, a.getU32(4))

            // FAT[2] terminates the root directory's one-cluster chain. FAT32
            // treats any value at or above 0x0FFFFFF8 as end-of-chain, so there
            // is no single right answer here: we write 0x0FFFFFFF (what the
            // specification recommends and what Windows writes) and mkfs.vfat
            // writes 0x0FFFFFF8. Both are correct, and fsck accepts both — so
            // the property to assert is the semantics, not the bytes.
            val ourRoot = a.getU32(8)
            val theirRoot = b.getU32(8)
            println("FAT[2]: ours=0x%08X mkfs=0x%08X (both end-of-chain)".format(ourRoot, theirRoot))
            assertTrue(ourRoot >= END_OF_CHAIN_MIN, "ours (0x%08X) must mark end of chain".format(ourRoot))
            assertTrue(theirRoot >= END_OF_CHAIN_MIN, "mkfs (0x%08X) must mark end of chain".format(theirRoot))
            assertEquals(FatEntries.ENTRY_2, ourRoot)
            assertTrue(
                a.copyOfRange(12, 512).all { it == 0.toByte() },
                "every remaining FAT entry must read as a free cluster",
            )
        } finally {
            ours.close()
        }
    }

    private fun hex(b: ByteArray): String {
        val s = b.joinToString("") { "%02x".format(it) }
        return if (s.length > 20) s.take(17) + "..." else s
    }
}
