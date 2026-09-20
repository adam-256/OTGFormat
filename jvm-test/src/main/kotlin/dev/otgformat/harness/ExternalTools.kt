package dev.otgformat.harness

import java.io.File
import java.util.concurrent.TimeUnit

/** Result of running a system tool. */
data class ProcResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val output: String get() = (stdout + stderr).trim()
    val ok: Boolean get() = exitCode == 0
}

/**
 * Bridge to the real filesystem tools.
 *
 * The entire value of Phase 0 rests on validating against implementations we
 * did not write. `mkfs.vfat` is the oracle for structure and `fsck.vfat` is the
 * oracle for validity; agreeing with ourselves proves nothing.
 */
object ExternalTools {

    /** Directories holding the dosfstools binaries, which are not always on PATH. */
    private val SEARCH_PATH = listOf("/sbin", "/usr/sbin", "/bin", "/usr/bin", "/usr/local/sbin", "/usr/local/bin")

    fun find(tool: String): String? {
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir ->
            val f = File(dir, tool)
            if (f.canExecute()) return f.absolutePath
        }
        SEARCH_PATH.forEach { dir ->
            val f = File(dir, tool)
            if (f.canExecute()) return f.absolutePath
        }
        return null
    }

    fun available(tool: String): Boolean = find(tool) != null

    fun run(vararg command: String, timeoutSeconds: Long = 300, env: Map<String, String> = emptyMap()): ProcResult {
        val resolved = (listOf(find(command[0]) ?: command[0]) + command.drop(1)).toTypedArray()
        val builder = ProcessBuilder(*resolved).redirectErrorStream(false)
        builder.environment().putAll(env)
        val process = builder.start()
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("${command.joinToString(" ")} timed out after ${timeoutSeconds}s")
        }
        return ProcResult(process.exitValue(), out, err)
    }

    /**
     * Runs `fsck.vfat -n` (never repair) with a verification pass.
     *
     * `fsck.vfat` has no offset option, so it must be pointed at a file that
     * begins with the boot sector. Callers pass an extracted partition.
     */
    fun fsck(image: File): ProcResult = run("fsck.vfat", "-n", "-v", "-V", image.absolutePath)

    fun fileType(image: File): ProcResult = run("file", "-b", image.absolutePath)
}

/**
 * Copies a partition out of a disk image into a standalone file.
 *
 * Done in-process rather than by shelling out to `dd` so that sparseness is
 * preserved deliberately: the output is pre-sized and only non-zero chunks are
 * written, which keeps a 64 GiB image's extraction to a few megabytes.
 */
object PartitionExtractor {

    fun extract(disk: FileSectorDevice, startLba: Long, sectors: Long, out: File): File {
        val bps = disk.blockSize
        val chunkSectors = maxOf(1, (1 shl 22) / bps)
        FileSectorDevice.create(out, sectors * bps, bps).use { dst ->
            val buf = ByteArray(chunkSectors * bps)
            var done = 0L
            while (done < sectors) {
                val n = minOf(chunkSectors.toLong(), sectors - done).toInt()
                val slice = if (n * bps == buf.size) buf else ByteArray(n * bps)
                disk.read(startLba + done, slice)
                // Skip all-zero runs; the destination is already a hole there.
                if (slice.any { it != 0.toByte() }) dst.write(done, slice)
                done += n
            }
            dst.flush()
        }
        return out
    }
}

/**
 * mtools: reads and writes FAT volumes in a plain file, with no kernel driver
 * and no privileges.
 *
 * This is what makes the read/write round-trip a test that runs everywhere
 * rather than one that is skipped on every machine without root and a `vfat`
 * module. mtools is an independent FAT implementation, so a file that survives
 * a write-unmount-recheck-read cycle through it is real evidence.
 */
object Mtools {

    private val ENV = mapOf("MTOOLS_SKIP_CHECK" to "1")

    fun available(): Boolean = ExternalTools.available("mcopy") && ExternalTools.available("mdir")

    /** Copies a host file into the volume at [target], e.g. `::/DATA.BIN`. */
    fun copyIn(image: File, source: File, target: String): ProcResult =
        ExternalTools.run("mcopy", "-i", image.absolutePath, source.absolutePath, target, env = ENV)

    /** Copies [source] out of the volume to a host file. */
    fun copyOut(image: File, source: String, destination: File): ProcResult =
        ExternalTools.run("mcopy", "-i", image.absolutePath, source, destination.absolutePath, env = ENV)

    fun makeDirectory(image: File, path: String): ProcResult =
        ExternalTools.run("mmd", "-i", image.absolutePath, path, env = ENV)

    fun list(image: File, path: String = "::/"): ProcResult =
        ExternalTools.run("mdir", "-i", image.absolutePath, path, env = ENV)
}
