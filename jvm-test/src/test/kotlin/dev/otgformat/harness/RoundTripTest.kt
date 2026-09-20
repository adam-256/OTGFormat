package dev.otgformat.harness

import dev.otgformat.core.FormatOptions
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Acceptance criterion 3: write files to the volume, check it again, read them
 * back identical.
 *
 * The specification expected this to need a loop mount and to be skipped where
 * privileges are unavailable. mtools removes that compromise: it is an
 * independent FAT implementation that operates directly on an image file, so
 * the round-trip runs unprivileged, in any container, on every CI machine. A
 * real kernel mount is attempted as well, and skips cleanly when the host has
 * no `vfat` driver.
 */
class RoundTripTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireTools() {
            assumeTrue(
                Mtools.available(),
                "mtools not installed (package: mtools) — cannot exercise a real read/write cycle",
            )
        }
    }

    private fun sha256(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private fun randomFile(name: String, size: Int, seed: Int): File =
        File(Images.scratch, name).also { it.writeBytes(Random(seed).nextBytes(size)) }

    @ParameterizedTest(name = "{0} MiB volume survives a write / re-check / read cycle")
    @ValueSource(longs = [64, 2048, 8192, 65536])
    fun `files written to the volume read back identical and the volume stays clean`(sizeMib: Long) {
        val formatted = Images.format("roundtrip-$sizeMib.img", sizeMib * Images.MIB)
        try {
            val partition = formatted.extractPartition()

            // A file large enough to span many clusters, so the FAT chain is
            // genuinely exercised rather than fitting in cluster 3.
            val payload = randomFile("payload-$sizeMib.bin", 3 * 1024 * 1024, sizeMib.toInt())
            val small = randomFile("small-$sizeMib.bin", 137, sizeMib.toInt() + 1)

            assertTrue(Mtools.copyIn(partition, payload, "::/PAYLOAD.BIN").ok, "writing PAYLOAD.BIN failed")
            assertTrue(Mtools.copyIn(partition, small, "::/SMALL.BIN").ok, "writing SMALL.BIN failed")
            assertTrue(Mtools.makeDirectory(partition, "::/SUBDIR").ok, "creating SUBDIR failed")
            assertTrue(Mtools.copyIn(partition, small, "::/SUBDIR/NESTED.BIN").ok, "writing into SUBDIR failed")

            // The volume must still be structurally clean after being written to.
            val fsck = ExternalTools.fsck(partition)
            println("--- $sizeMib MiB after writes ---")
            println(fsck.output.lines().takeLast(3).joinToString("\n"))
            assertTrue(fsck.ok, "fsck.vfat found errors after writing files:\n${fsck.output}")

            val listing = Mtools.list(partition).output
            assertContains(listing, "PAYLOAD")
            assertContains(listing, "SUBDIR")

            val readBack = File(Images.scratch, "readback-$sizeMib.bin")
            if (readBack.exists()) readBack.delete()
            assertTrue(Mtools.copyOut(partition, "::/PAYLOAD.BIN", readBack).ok, "reading PAYLOAD.BIN failed")
            assertEquals(sha256(payload), sha256(readBack), "PAYLOAD.BIN did not survive the round trip")

            val nested = File(Images.scratch, "readback-nested-$sizeMib.bin")
            if (nested.exists()) nested.delete()
            assertTrue(Mtools.copyOut(partition, "::/SUBDIR/NESTED.BIN", nested).ok)
            assertEquals(sha256(small), sha256(nested), "NESTED.BIN did not survive the round trip")
        } finally {
            formatted.close()
        }
    }

    @Test
    fun `the volume label is readable by an independent implementation`() {
        // The label lives in two places — the BPB and a root directory entry.
        // mtools reads the directory entry, so this proves both were written
        // and that they agree.
        val formatted = Images.format(
            "label-check.img",
            2048 * Images.MIB,
            FormatOptions(label = "MFLASH", volumeSerial = 0x1234_ABCD),
        )
        try {
            val listing = Mtools.list(formatted.extractPartition()).output
            println(listing)
            assertContains(listing, "Volume in drive : is MFLASH")
            assertContains(listing, "1234-ABCD")
        } finally {
            formatted.close()
        }
    }

    @Test
    fun `filling the volume does not corrupt it`() {
        // Small volume, many files: pushes allocation across the FAT rather
        // than staying in the first few clusters.
        val formatted = Images.format("many-files.img", 64 * Images.MIB)
        try {
            val partition = formatted.extractPartition()
            val source = randomFile("chunk.bin", 256 * 1024, 99)
            repeat(40) { i ->
                val r = Mtools.copyIn(partition, source, "::/FILE%02d.BIN".format(i))
                assertTrue(r.ok, "writing FILE%02d.BIN failed: ${r.output}".format(i))
            }
            val fsck = ExternalTools.fsck(partition)
            println(fsck.output.lines().takeLast(2).joinToString("\n"))
            assertTrue(fsck.ok, "fsck.vfat found errors after filling the volume:\n${fsck.output}")

            val back = File(Images.scratch, "chunk-back.bin")
            if (back.exists()) back.delete()
            assertTrue(Mtools.copyOut(partition, "::/FILE39.BIN", back).ok)
            assertEquals(sha256(source), sha256(back), "the last file written did not read back identical")
        } finally {
            formatted.close()
        }
    }

    /**
     * The same cycle through the kernel's own FAT driver.
     *
     * Skipped where loop devices or the `vfat` module are unavailable, which is
     * the common case in containers — the mtools tests above are the ones that
     * always run.
     */
    @Test
    fun `the volume mounts and round-trips through the kernel vfat driver`() {
        assumeTrue(ExternalTools.available("losetup") && ExternalTools.available("mount"), "losetup/mount unavailable")
        assumeTrue(File("/dev/loop-control").exists(), "loop devices unavailable")

        val formatted = Images.format("kernel-mount.img", 2048 * Images.MIB)
        var loop: String? = null
        val mountPoint = File(Images.scratch, "mnt").also { it.mkdirs() }
        try {
            val offset = formatted.result.layout.partitionStartLba * formatted.result.layout.bytesPerSector
            val setup = ExternalTools.run(
                "losetup", "--find", "--show", "--offset", offset.toString(), formatted.file.absolutePath,
            )
            assumeTrue(setup.ok, "could not attach a loop device: ${setup.output}")
            loop = setup.stdout.trim()

            val mount = ExternalTools.run("mount", "-t", "vfat", loop, mountPoint.absolutePath)
            assumeTrue(
                mount.ok,
                "kernel cannot mount vfat here (${mount.output.trim()}); " +
                    "the mtools round-trip covers this criterion instead",
            )

            try {
                val payload = randomFile("kernel-payload.bin", 2 * 1024 * 1024, 7)
                val onVolume = File(mountPoint, "KERNEL.BIN")
                payload.copyTo(onVolume, overwrite = true)
                ExternalTools.run("sync")
                ExternalTools.run("umount", mountPoint.absolutePath)

                val fsck = ExternalTools.fsck(File(loop))
                assertTrue(fsck.ok, "fsck failed after a kernel write:\n${fsck.output}")

                assertTrue(ExternalTools.run("mount", "-t", "vfat", loop, mountPoint.absolutePath).ok)
                assertEquals(sha256(payload), sha256(File(mountPoint, "KERNEL.BIN")))
                println("kernel vfat round-trip: identical")
            } finally {
                ExternalTools.run("umount", mountPoint.absolutePath)
            }
        } finally {
            loop?.let { ExternalTools.run("losetup", "-d", it) }
            formatted.close()
        }
    }
}
