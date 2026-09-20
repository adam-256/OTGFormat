package dev.otgformat.harness

import dev.otgformat.core.Fat32Layout
import java.io.File

/**
 * Runs the real `mkfs.vfat` with parameters matching one of our layouts, so its
 * output can be diffed against ours.
 *
 * `mkfs.vfat` is the oracle: it is a widely deployed, independently written
 * implementation of the same specification. Any structural disagreement is a
 * bug in our writer until proven otherwise.
 *
 * Flags chosen so that the only remaining differences are genuine ones:
 *  - `--invariant` replaces time- and random-derived values with constants.
 *  - `-a` disables mkfs's data-area alignment, which would otherwise silently
 *    enlarge the reserved region and make the two layouts incomparable.
 *  - `-g 255/63` pins the legacy CHS geometry, which mkfs otherwise guesses.
 */
object MkfsOracle {

    /** The volume ID `--invariant` uses. */
    const val INVARIANT_VOLUME_ID = 0x1234_ABCD

    data class Output(
        val image: File,
        val bootSector: ByteArray,
        val fsInfoSector: ByteArray,
        val firstFatSector: ByteArray,
        val command: String,
    )

    fun format(name: String, diskSizeBytes: Long, layout: Fat32Layout, label: String?): Output {
        val image = Images.image(name)
        if (image.exists()) image.delete()
        // A sparse file of the full disk size; mkfs writes only the filesystem.
        FileSectorDevice.create(image, diskSizeBytes, layout.bytesPerSector).close()

        val command = mutableListOf(
            "mkfs.vfat",
            "-F", "32",
            "-s", layout.sectorsPerCluster.toString(),
            "-S", layout.bytesPerSector.toString(),
            "-R", layout.reservedSectors.toString(),
            "-f", layout.numFats.toString(),
            "-h", layout.partitionStartLba.toString(),
            "-g", "255/63",
            "-a",
            "--invariant",
        )
        if (label != null) command += listOf("-n", label)
        if (layout.partitionStartLba > 0) command += "--offset=${layout.partitionStartLba}"
        command += image.absolutePath
        // mkfs takes the filesystem size in 1024-byte blocks.
        command += (layout.totalSectors * layout.bytesPerSector / 1024).toString()

        val result = ExternalTools.run(*command.toTypedArray())
        check(result.ok) { "${command.joinToString(" ")} failed:\n${result.output}" }

        FileSectorDevice.create0(image, layout.bytesPerSector).use { dev ->
            val start = layout.partitionStartLba
            // mkfs computes its own FAT size, so read its boot sector first and
            // use that to locate its FAT rather than assuming ours.
            val boot = dev.readSector(start)
            val theirFatStart = start + layout.reservedSectors
            return Output(
                image = image,
                bootSector = boot,
                fsInfoSector = dev.readSector(start + 1),
                firstFatSector = dev.readSector(theirFatStart),
                command = command.joinToString(" "),
            )
        }
    }
}
