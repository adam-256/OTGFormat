package dev.otgformat.app

import dev.otgformat.core.Phase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** What the format is doing, as the UI needs to show it. */
sealed interface FormatState {

    data object Idle : FormatState

    data class Running(
        val deviceName: String,
        val phase: Phase,
        val sectorsDone: Long,
        val sectorsTotal: Long,
    ) : FormatState {
        val fraction: Float get() = if (sectorsTotal <= 0) 0f else (sectorsDone.toFloat() / sectorsTotal)

        /**
         * What the user is told is happening.
         *
         * Named per phase because the durations are wildly uneven: on a large
         * stick the FAT clear is essentially the whole wait and everything else
         * is instant. A single unlabelled bar sitting at 4% for four minutes
         * reads as a hang.
         */
        val phaseLabel: String get() = when (phase) {
            Phase.WIPE_SIGNATURES -> "Erasing old partition tables"
            Phase.PARTITION_TABLE -> "Writing the partition table"
            Phase.RESERVED_AREA -> "Preparing the reserved area"
            Phase.ZERO_FAT -> "Clearing the file allocation tables"
            Phase.INIT_FAT -> "Initialising the file allocation tables"
            Phase.ROOT_DIRECTORY -> "Creating the root directory"
            Phase.BOOT_SECTORS -> "Writing the boot sectors"
            Phase.VERIFY -> "Verifying what was written"
        }
    }

    data class Done(val deviceName: String, val summary: String) : FormatState

    data class Failed(val message: String, val advice: String? = null) : FormatState

    data class Cancelled(val message: String) : FormatState
}

/**
 * The single place format state lives.
 *
 * The work runs in a foreground service so Android will not kill it when the
 * user switches away, which means the UI cannot own the state. A process-wide
 * holder keeps the two decoupled without a binder: the service writes, the
 * Activity observes, and a rotation or a trip to the home screen loses nothing.
 */
object FormatController {

    private val _state = MutableStateFlow<FormatState>(FormatState.Idle)
    val state: StateFlow<FormatState> = _state.asStateFlow()

    private val cancelRequested = AtomicBoolean(false)

    /**
     * Whether the service has taken the job.
     *
     * Deliberately separate from [state]. Re-entrancy has to be judged by what
     * the service is doing, not by what the screen is showing: the UI marks a
     * format as running the moment the button is pressed, so a guard that read
     * the displayed state would always find a format already in progress and
     * refuse to start the real one.
     */
    private val claimed = AtomicBoolean(false)

    val isRunning: Boolean get() = _state.value is FormatState.Running

    /** Takes the job, or returns false if a format is already under way. */
    internal fun claim(): Boolean = claimed.compareAndSet(false, true)

    internal fun release() {
        claimed.set(false)
    }

    internal fun update(state: FormatState) {
        _state.value = state
    }

    internal fun beginRun() {
        cancelRequested.set(false)
    }

    internal fun shouldCancel(): Boolean = cancelRequested.get()

    /**
     * Asks the running format to stop at the next write boundary.
     *
     * Cancelling does not undo anything: the device is left partly written and
     * will not mount. The UI says so.
     */
    fun requestCancel() {
        cancelRequested.set(true)
    }

    fun reset() {
        if (!isRunning) _state.value = FormatState.Idle
    }
}
