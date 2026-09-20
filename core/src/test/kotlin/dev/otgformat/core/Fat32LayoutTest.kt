package dev.otgformat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Fat32LayoutTest {

    private val MIB = 1024L * 1024
    private val GIB = 1024L * MIB

    private fun layoutFor(diskBytes: Long, options: FormatOptions = FormatOptions()): Fat32Layout =
        Formatter.plan(UntouchableDevice(sectorCount = diskBytes / 512), options)

    @Test
    fun `planning reads nothing from the device`() {
        // UntouchableDevice throws on any access; reaching an assertion proves
        // the user can be shown the full plan before authorising a single write.
        val layout = layoutFor(8 * GIB)
        assertTrue(layout.countOfClusters > 0)
    }

    /**
     * The invariant that actually matters: every cluster must have a FAT entry
     * describing it. fatgen103's closed-form formula is an approximation, so it
     * is swept rather than trusted.
     */
    @Test
    fun `every accepted geometry has a FAT large enough to describe its clusters`() {
        var accepted = 0
        var rejected = 0
        val sizes = buildList {
            // Dense near the FAT32 floor, where the formula is tightest.
            for (mib in 16..600 step 1) add(mib * MIB)
            // Then across the whole practical range, including awkward sizes
            // that are not round numbers of anything.
            for (mib in 600..64_000 step 377) add(mib * MIB)
            add(2 * GIB); add(8 * GIB); add(16 * GIB); add(32 * GIB); add(64 * GIB)
            add(128 * GIB); add(256 * GIB); add(1024 * GIB)
        }

        for (size in sizes) {
            val layout = try {
                layoutFor(size)
            } catch (e: FormatException) {
                rejected++
                continue
            }
            accepted++
            assertTrue(
                layout.fatEntryCapacity >= layout.countOfClusters + 2,
                "size=${size / MIB} MiB: FAT holds ${layout.fatEntryCapacity} entries but the volume has " +
                    "${layout.countOfClusters} clusters",
            )
            assertTrue(
                layout.countOfClusters >= ClusterSizeTable.MIN_FAT32_CLUSTERS,
                "size=${size / MIB} MiB: only ${layout.countOfClusters} clusters",
            )
            assertTrue(
                layout.firstDataSector + layout.countOfClusters * layout.sectorsPerCluster <= layout.totalSectors,
                "size=${size / MIB} MiB: data area runs past the end of the partition",
            )
        }
        println("geometry sweep: $accepted accepted, $rejected rejected as too small for FAT32")
        assertTrue(accepted > 100, "sweep should accept a wide range of sizes")
        assertTrue(rejected > 0, "sweep should include sizes that are correctly refused")
    }

    @Test
    fun `auto cluster size follows Microsoft's table where the table applies`() {
        // The table is defined by volume size; the partition is 1 MiB smaller
        // than the disk, which keeps each case inside its intended band.
        assertEquals(8, layoutFor(2 * GIB).sectorsPerCluster, "4 KiB clusters up to 8 GiB")
        assertEquals(8, layoutFor(8 * GIB).sectorsPerCluster, "4 KiB clusters at 8 GiB")
        assertEquals(16, layoutFor(12 * GIB).sectorsPerCluster, "8 KiB clusters from 8 to 16 GiB")
        assertEquals(32, layoutFor(24 * GIB).sectorsPerCluster, "16 KiB clusters from 16 to 32 GiB")
        assertEquals(64, layoutFor(64 * GIB).sectorsPerCluster, "32 KiB clusters above 32 GiB")
    }

    /**
     * Microsoft's table bottoms out at 4 KiB clusters, which need ~256 MiB
     * before a volume reaches 65525 clusters. Its stated 32 MiB floor is the
     * floor for 512-byte clusters, so below 256 MiB the cluster size has to
     * step down or a perfectly formattable 64 MiB stick gets refused.
     */
    @Test
    fun `auto cluster size steps down below the table's range`() {
        val small = layoutFor(64 * MIB)
        assertEquals(1, small.sectorsPerCluster, "64 MiB needs 512 B clusters to be legal FAT32")
        assertTrue(small.countOfClusters >= ClusterSizeTable.MIN_FAT32_CLUSTERS)
        assertTrue(
            small.warnings.any { "too small for the standard" in it },
            "stepping down from the table should be reported, not silent: ${small.warnings}",
        )
    }

    @Test
    fun `a 16 MiB volume is rejected with a cluster-count explanation`() {
        val e = assertFailsWith<FormatException> { layoutFor(16 * MIB) }
        assertTrue(
            "65525" in e.message!! || "clusters" in e.message!!,
            "the error must explain the cluster-count rule, got: ${e.message}",
        )
    }

    @Test
    fun `an oversized cluster override that yields too few clusters is refused`() {
        // 32 KiB clusters on a 256 MiB volume gives ~8000 clusters: FAT16 by
        // definition. Refusing beats writing a volume fsck will condemn.
        val e = assertFailsWith<FormatException> {
            layoutFor(256 * MIB, FormatOptions(clusterSize = ClusterSize.Bytes(32 * 1024)))
        }
        assertTrue("65525" in e.message!!, e.message!!)
        assertTrue("smaller cluster size" in e.message!!, "should suggest the fix: ${e.message}")
    }

    @Test
    fun `cluster overrides must divide the sector size and fit in one byte`() {
        assertFailsWith<FormatException> {
            Formatter.plan(
                UntouchableDevice(blockSize = 4096, sectorCount = 8 * GIB / 4096),
                FormatOptions(clusterSize = ClusterSize.Bytes(512)),
            )
        }
        assertFailsWith<FormatException> {
            layoutFor(64 * GIB, FormatOptions(clusterSize = ClusterSize.Bytes(128 * 1024)))
        }
    }

    @Test
    fun `clusters above 32 KiB are allowed but flagged as unmountable on Windows`() {
        val layout = layoutFor(256 * GIB, FormatOptions(clusterSize = ClusterSize.Bytes(64 * 1024)))
        assertEquals(128, layout.sectorsPerCluster)
        assertTrue(layout.warnings.any { "32 KiB" in it }, layout.warnings.toString())
    }

    @Test
    fun `4096-byte-sector media get a correct FAT size rather than a 512-byte assumption`() {
        // fatgen103's literal "256" is bytesPerSector/2. Hard-coding it would
        // under-allocate the FAT by 8x on a 4Kn device.
        val layout = Formatter.plan(UntouchableDevice(blockSize = 4096, sectorCount = 16 * GIB / 4096), FormatOptions())
        assertTrue(
            layout.fatEntryCapacity >= layout.countOfClusters + 2,
            "4Kn FAT holds ${layout.fatEntryCapacity}, needs ${layout.countOfClusters + 2}",
        )
    }

    @Test
    fun `fixed structural constants match the specification`() {
        val layout = layoutFor(8 * GIB)
        assertEquals(32, layout.reservedSectors)
        assertEquals(2, layout.numFats)
        assertEquals(2048L, layout.partitionStartLba)
        assertEquals(32L, layout.firstFatSector)
        assertEquals(32 + 2 * layout.fatSizeSectors, layout.firstDataSector)
        assertEquals(layout.firstDataSector, layout.clusterToSector(2))
    }

    @Test
    fun `superfloppy scheme puts the filesystem at sector zero`() {
        val layout = layoutFor(8 * GIB, FormatOptions(partitionScheme = PartitionScheme.SUPERFLOPPY))
        assertEquals(0L, layout.partitionStartLba)
        assertEquals(8 * GIB / 512, layout.totalSectors)
    }

    @Test
    fun `GPT is refused explicitly rather than silently producing MBR`() {
        val e = assertFailsWith<FormatException> {
            layoutFor(8 * GIB, FormatOptions(partitionScheme = PartitionScheme.GPT))
        }
        assertTrue("GPT" in e.message!! && "not implemented" in e.message!!)
    }
}
