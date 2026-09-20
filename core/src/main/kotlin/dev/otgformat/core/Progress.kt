package dev.otgformat.core

/**
 * Phases of a format, in the order they occur.
 *
 * Reported separately from raw sector counts because the phases have wildly
 * different durations: on a 128 GB stick, [ZERO_FAT] is essentially the entire
 * wait and everything else is instantaneous. A UI that shows only a single bar
 * looks stuck; a UI that names the phase does not.
 */
enum class Phase {
    /** Erasing stale partition tables and filesystem signatures. */
    WIPE_SIGNATURES,

    /** Writing the partition table. */
    PARTITION_TABLE,

    /** Clearing the reserved-sector region ahead of the FATs. */
    RESERVED_AREA,

    /** Zeroing both file allocation tables. Almost always the longest phase. */
    ZERO_FAT,

    /** Writing the first three entries of each FAT. */
    INIT_FAT,

    /** Clearing the root directory cluster and writing the volume label entry. */
    ROOT_DIRECTORY,

    /** Writing the boot sector, FSInfo, and their backups. */
    BOOT_SECTORS,

    /** Flushing and reading critical sectors back to confirm they landed. */
    VERIFY,
}

/**
 * Progress and cancellation callback.
 *
 * [sectorsDone] and [sectorsTotal] count only sectors this format will actually
 * write, so the ratio is real rather than indeterminate.
 */
interface Progress {

    fun onProgress(phase: Phase, sectorsDone: Long, sectorsTotal: Long)

    /**
     * Polled between write batches. Returning true aborts with
     * [FormatCancelledException], leaving the device in a partially written
     * (and therefore unmountable) state — callers must say so.
     */
    val isCancelled: Boolean get() = false

    companion object {
        /** A callback that reports nowhere and never cancels. */
        val NONE: Progress = object : Progress {
            override fun onProgress(phase: Phase, sectorsDone: Long, sectorsTotal: Long) {}
        }
    }
}
