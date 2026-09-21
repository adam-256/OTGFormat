package dev.otgformat.usb

import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.nio.ByteBuffer
import java.util.Locale

/** One check, with a result a non-specialist can read and a detail a developer can use. */
data class SelfTestStep(
    val name: String,
    val ok: Boolean,
    val detail: String,
)

/**
 * The outcome of a self-test, formatted for copy and paste.
 */
data class SelfTestReport(
    val steps: List<SelfTestStep>,
    val notes: List<String> = emptyList(),
) {
    val passed: Boolean get() = steps.all { it.ok }

    val headline: String
        get() = if (passed) "All checks passed. This device can be formatted."
        else "Something did not work. The failing step is marked below."

    fun asText(): String = buildString {
        appendLine("OTGFormat self-test")
        appendLine(headline)
        appendLine()
        steps.forEach { appendLine("${if (it.ok) "OK  " else "FAIL"}  ${it.name}: ${it.detail}") }
        if (notes.isNotEmpty()) {
            appendLine()
            notes.forEach { appendLine(it) }
        }
    }
}

/**
 * Exercises everything a format needs, without writing a single byte.
 *
 * A formatter that cannot check its own access path before using it asks the
 * user to find out the hard way. Every step here is a read, so running it on a
 * drive full of data is safe, and the result is a block of text that can be
 * sent to someone else as-is rather than a logcat trace to interpret.
 *
 * It also measures what this project could not determine without hardware:
 * which transfer sizes the bridge actually accepts, and how fast it is.
 */
object UsbSelfTest {

    /** Transfer sizes to probe, in bytes. The default chunk is in the middle. */
    val PROBE_SIZES = listOf(32 * 1024, 64 * 1024, 128 * 1024, 256 * 1024, 512 * 1024, 1024 * 1024)

    /**
     * @param recover called after the deliberately out-of-range read, to put
     *   the device back in a usable state. An out-of-range read makes a drive
     *   stall its bulk endpoint, and a stalled endpoint stays stalled until the
     *   halt is cleared, so this check runs last and hands back a clean device.
     */
    fun run(
        driver: BlockDeviceDriver,
        initialise: Boolean = true,
        recover: () -> Unit = {},
    ): SelfTestReport {
        val steps = mutableListOf<SelfTestStep>()
        val notes = mutableListOf<String>()

        // --- the device answers at all --------------------------------------
        if (initialise) {
            val init = runCatching { driver.init() }
            steps += SelfTestStep(
                "Device responds",
                init.isSuccess,
                if (init.isSuccess) "INQUIRY and READ CAPACITY succeeded"
                else "failed: ${init.exceptionOrNull()?.describe()}",
            )
            if (init.isFailure) return SelfTestReport(steps, notes)
        }

        val blockSize = runCatching { driver.blockSize }.getOrDefault(0)
        steps += SelfTestStep(
            "Sector size",
            blockSize >= 512 && blockSize and (blockSize - 1) == 0,
            "$blockSize bytes",
        )
        if (blockSize < 512) return SelfTestReport(steps, notes)

        val reported = runCatching { driver.blocks }.getOrDefault(-1L)

        // --- capacity, and which convention this driver uses -----------------
        val convention = LibaumsSectorDevice.conventionOf(driver)
        val probed = runCatching { LibaumsSectorDevice.determineSectorCount(driver, blockSize, convention) }
        val sectorCount = probed.getOrDefault(-1L)
        steps += SelfTestStep(
            "Capacity",
            sectorCount > 0,
            if (sectorCount <= 0) "could not be determined"
            else "$sectorCount sectors, ${formatGb(sectorCount * blockSize)} " +
                "(driver reported $reported as " +
                when (convention) {
                    CapacityConvention.LAST_BLOCK_ADDRESS -> "the last block address"
                    CapacityConvention.BLOCK_COUNT -> "a block count"
                } + ")",
        )
        if (sectorCount <= 0) return SelfTestReport(steps, notes)

        // --- the sectors a format has to reach -------------------------------
        val first = readSector(driver, 0, blockSize)
        steps += SelfTestStep(
            "First sector readable",
            first.isSuccess,
            first.fold({ describeSector0(it) }, { "failed: ${it.describe()}" }),
        )

        val last = readSector(driver, sectorCount - 1, blockSize)
        steps += SelfTestStep(
            "Last sector readable",
            last.isSuccess,
            // Getting this one wrong is how a stale backup GPT survives a
            // format, so it is checked explicitly rather than assumed.
            last.fold({ "sector ${sectorCount - 1} read" }, { "failed: ${it.describe()}" }),
        )

        // --- what the bridge will actually carry ------------------------------
        val accepted = mutableListOf<String>()
        var bestSize = 0
        var bestRate = 0.0
        for (size in PROBE_SIZES) {
            if (size / blockSize > sectorCount) continue
            val buffer = ByteArray(size)
            val started = System.nanoTime()
            val attempt = runCatching {
                driver.read(0, ByteBuffer.wrap(buffer))
            }
            val elapsed = (System.nanoTime() - started).coerceAtLeast(1)
            if (attempt.isSuccess) {
                val mbPerSecond = size.toDouble() / (elapsed / 1_000_000_000.0) / 1_000_000.0
                accepted += String.format(Locale.ROOT, "%d KiB %.1f MB/s", size / 1024, mbPerSecond)
                if (mbPerSecond > bestRate) {
                    bestRate = mbPerSecond
                    bestSize = size
                }
            } else {
                accepted += "${size / 1024} KiB rejected"
            }
        }
        steps += SelfTestStep(
            "Transfer sizes",
            accepted.any { "rejected" !in it },
            accepted.joinToString(", "),
        )
        if (bestSize > 0) {
            notes += "Fastest transfer size was ${bestSize / 1024} KiB " +
                String.format(Locale.ROOT, "at %.1f MB/s.", bestRate) +
                " The app formats with ${LibaumsSectorDevice.DEFAULT_MAX_TRANSFER_BYTES / 1024} KiB."
        }

        // --- last, because it deliberately upsets the device -----------------
        // A drive answers an out-of-range read by stalling its bulk endpoint,
        // and everything after that fails until the halt is cleared. So this
        // runs after every other check, and the device is put back afterwards.
        val pastEnd = readSector(driver, sectorCount, blockSize)
        steps += SelfTestStep(
            "Refuses reads past the end",
            pastEnd.isFailure,
            if (pastEnd.isFailure) "as expected"
            else "the device accepted a read past its own capacity, which means it is not " +
                "reporting its size honestly",
        )
        if (pastEnd.isSuccess) {
            notes += "Warning: this device answers reads beyond the capacity it reports. That is a " +
                "hallmark of counterfeit flash. Formatting will still verify what it writes, but do " +
                "not trust this device with anything important."
        }
        runCatching { recover() }

        return SelfTestReport(steps, notes)
    }

    private fun readSector(driver: BlockDeviceDriver, sector: Long, blockSize: Int): Result<ByteArray> =
        runCatching {
            val buffer = ByteArray(blockSize)
            driver.read(sector, ByteBuffer.wrap(buffer))
            buffer
        }

    /** Says what is on sector 0 now, so the user can tell they picked the right device. */
    private fun describeSector0(sector: ByteArray): String {
        val signature = (sector[510].toInt() and 0xFF) or ((sector[511].toInt() and 0xFF) shl 8)
        val blank = sector.all { it == 0.toByte() }
        return when {
            blank -> "currently blank"
            signature == 0xAA55 -> "currently holds a partition table or boot sector"
            else -> "currently holds data with no partition signature"
        }
    }

    private fun formatGb(bytes: Long): String =
        String.format(Locale.ROOT, "%.1f GB", bytes / 1_000_000_000.0)

    private fun Throwable.describe(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}
