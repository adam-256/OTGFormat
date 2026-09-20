package dev.otgformat.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.otgformat.core.Fat32Layout
import dev.otgformat.core.FormatOptions
import dev.otgformat.core.PartitionScheme
import dev.otgformat.core.Phase
import dev.otgformat.usb.Confirmation
import dev.otgformat.usb.ConfirmationPolicy
import dev.otgformat.usb.FormatPlanner
import dev.otgformat.usb.PlanOutcome
import dev.otgformat.usb.SelfTestReport
import dev.otgformat.usb.UsbTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything the one screen needs.
 *
 * The plan is held as a value rather than computed in a getter. Deriving it on
 * read would mean building [FormatOptions] during composition, and that
 * constructor throws on an invalid volume label — a user typing a full stop
 * would crash the screen. [FormatPlanner] turns every such failure into a
 * message instead, and is tested for exactly that.
 */
data class UiState(
    val candidates: List<MassStorageCandidate> = emptyList(),
    val selectedKey: String? = null,
    val needsPermission: Boolean = false,
    val target: UsbTarget? = null,
    val label: String = "",
    val clusterBytes: Int = 0,
    val scheme: PartitionScheme = PartitionScheme.MBR,
    val bootable: Boolean = false,
    val outcome: PlanOutcome? = null,
    val typed: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    val selfTest: SelfTestReport? = null,
    val selfTestRunning: Boolean = false,
) {
    val selected: MassStorageCandidate? get() = candidates.firstOrNull { it.key == selectedKey }

    val layout: Fat32Layout? get() = (outcome as? PlanOutcome.Ready)?.layout
    val options: FormatOptions? get() = (outcome as? PlanOutcome.Ready)?.options
    val planError: String? get() = (outcome as? PlanOutcome.Rejected)?.reason

    val confirmation: Confirmation?
        get() = target?.let { ConfirmationPolicy.forTarget(it, label.ifBlank { null }) }

    /** The format button is live only with a valid plan and a satisfied confirmation. */
    val canFormat: Boolean
        get() = !busy &&
            selected != null &&
            !needsPermission &&
            options != null &&
            confirmation?.let { ConfirmationPolicy.isSatisfied(it, typed) } == true
}

class FormatViewModel(app: Application) : AndroidViewModel(app) {

    private val access = UsbAccess(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val formatState = FormatController.state

    init {
        refresh()
    }

    fun refresh() {
        val candidates = access.findCandidates()
        val keptKey = _state.value.selectedKey?.takeIf { key -> candidates.any { it.key == key } }
        _state.value = _state.value.copy(candidates = candidates, selectedKey = keptKey)
        if (keptKey == null) {
            _state.value = _state.value.copy(target = null, outcome = null)
        }
    }

    fun select(candidate: MassStorageCandidate) {
        _state.value = _state.value.copy(selectedKey = candidate.key, message = null)
        if (!access.hasPermission(candidate.device)) {
            _state.value = _state.value.copy(needsPermission = true, target = null, outcome = null)
            access.requestPermission(candidate.device)
            return
        }
        readTarget(candidate)
    }

    /** Called when the USB permission broadcast comes back. */
    fun onPermissionResult(granted: Boolean) {
        val candidate = _state.value.selected ?: return
        if (!granted) {
            _state.value = _state.value.copy(
                needsPermission = true,
                message = "Permission denied. The device cannot be read or written without it.",
            )
            return
        }
        _state.value = _state.value.copy(needsPermission = false)
        readTarget(candidate)
    }

    /**
     * Opens the device just long enough to learn its identity and size, then
     * releases it.
     *
     * Holding the interface open while the user fills in options would stop the
     * format service from claiming it later, so the claim is kept short.
     * Nothing is written.
     */
    private fun readTarget(candidate: MassStorageCandidate) {
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { access.open(candidate).use { it.target } }
            }
            result.onSuccess { target ->
                _state.value = _state.value.copy(busy = false, target = target, message = null)
                replan()
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    busy = false,
                    target = null,
                    outcome = null,
                    message = "Could not open the device: ${e.message ?: e.javaClass.simpleName}. " +
                        "If Android has it mounted, eject it in Files first — another app holding the " +
                        "interface stops this one from claiming it.",
                )
            }
        }
    }

    fun setLabel(value: String) {
        // Trimmed to the field width here so the user sees the limit as they
        // type; everything else about label validity is the planner's job.
        _state.value = _state.value.copy(label = value.uppercase().take(11))
        replan()
    }

    fun setClusterBytes(value: Int) {
        _state.value = _state.value.copy(clusterBytes = value)
        replan()
    }

    fun setScheme(value: PartitionScheme) {
        _state.value = _state.value.copy(scheme = value)
        replan()
    }

    fun setBootable(value: Boolean) {
        _state.value = _state.value.copy(bootable = value)
        replan()
    }

    fun setTyped(value: String) {
        _state.value = _state.value.copy(typed = value)
    }

    /** Recomputes the plan on every change. Never touches the device. */
    private fun replan() {
        val s = _state.value
        val target = s.target
        _state.value = s.copy(
            outcome = target?.let {
                FormatPlanner.plan(it, s.label, s.clusterBytes, s.scheme, s.bootable)
            },
        )
    }

    fun startFormat() {
        val s = _state.value
        val candidate = s.selected ?: return
        val options = s.options ?: return
        if (!s.canFormat) return
        // Show the running screen immediately; the service takes over the state
        // as soon as it starts.
        FormatController.update(
            FormatState.Running(s.target?.displayName() ?: "device", Phase.WIPE_SIGNATURES, 0, 1),
        )
        FormatService.start(getApplication(), candidate, options)
    }

    /**
     * Runs the read-only self-test and keeps the report for display.
     *
     * This exists so that checking whether a device works is one tap and a
     * block of text that can be sent to someone else verbatim, rather than a
     * logcat capture and a person who has to know what they are looking at.
     */
    fun runSelfTest() {
        val candidate = _state.value.selected ?: return
        _state.value = _state.value.copy(selfTestRunning = true, selfTest = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { access.selfTest(candidate) } }
            _state.value = result.fold(
                onSuccess = { _state.value.copy(selfTestRunning = false, selfTest = it) },
                onFailure = {
                    _state.value.copy(
                        selfTestRunning = false,
                        message = "The self-test could not open the device: " +
                            "${it.message ?: it.javaClass.simpleName}. If Android has the drive mounted, " +
                            "eject it in Files first.",
                    )
                },
            )
        }
    }

    fun dismissSelfTest() {
        _state.value = _state.value.copy(selfTest = null)
    }

    fun cancelFormat() = FormatController.requestCancel()

    fun dismissResult() {
        FormatController.reset()
        _state.value = _state.value.copy(typed = "")
        refresh()
    }
}
