package dev.otgformat.usb

import dev.otgformat.core.Fat32Layout
import dev.otgformat.core.ClusterSize
import dev.otgformat.core.FormatException
import dev.otgformat.core.FormatOptions
import dev.otgformat.core.Formatter
import dev.otgformat.core.PartitionScheme
import dev.otgformat.core.SectorDevice

/** The result of turning what the user typed into a plan. */
sealed interface PlanOutcome {

    /** The options are valid and this is exactly what will be written. */
    data class Ready(val options: FormatOptions, val layout: Fat32Layout) : PlanOutcome

    /** The options cannot produce a valid volume. [reason] is shown verbatim. */
    data class Rejected(val reason: String) : PlanOutcome
}

/**
 * Turns UI fields into a validated plan, without touching the device.
 *
 * This exists as its own testable unit for two reasons.
 *
 * `FormatOptions` validates the volume label in its constructor and throws on a
 * bad one. A UI that builds `FormatOptions` inline — in a property getter read
 * during layout, say — turns a user typing a full stop into a crash. Every path
 * that can throw is funnelled through here and comes back as a message.
 *
 * And `Formatter.plan` is guaranteed not to perform I/O, which means the whole
 * outcome (geometry, warnings, refusals) can be recomputed on every keystroke
 * from the device's dimensions alone, with the drive left alone until the user
 * commits. [PlanningDevice] enforces that guarantee rather than trusting it.
 */
object FormatPlanner {

    fun plan(
        target: UsbTarget,
        label: String,
        clusterBytes: Int,
        scheme: PartitionScheme,
        bootable: Boolean,
    ): PlanOutcome {
        val options = try {
            FormatOptions(
                label = label.trim().ifEmpty { null },
                clusterSize = if (clusterBytes <= 0) ClusterSize.Auto else ClusterSize.Bytes(clusterBytes),
                partitionScheme = scheme,
                bootable = bootable,
            )
        } catch (e: FormatException) {
            return PlanOutcome.Rejected(e.message ?: "These options are not valid.")
        } catch (e: IllegalArgumentException) {
            return PlanOutcome.Rejected(e.message ?: "These options are not valid.")
        }

        return try {
            PlanOutcome.Ready(options, Formatter.plan(PlanningDevice(target.blockSize, target.sectorCount), options))
        } catch (e: FormatException) {
            PlanOutcome.Rejected(e.message ?: "This device cannot be formatted with these options.")
        } catch (e: IllegalArgumentException) {
            PlanOutcome.Rejected(e.message ?: "This device cannot be formatted with these options.")
        }
    }
}

/**
 * Stands in for the real device while a plan is computed.
 *
 * Every access throws, so "planning does not touch the drive" is enforced here
 * rather than left as a comment that could quietly stop being true.
 */
internal class PlanningDevice(
    override val blockSize: Int,
    override val sectorCount: Long,
) : SectorDevice {
    override fun read(sector: Long, dst: ByteArray): Unit = error("planning must not read the device")
    override fun write(sector: Long, src: ByteArray): Unit = error("planning must not write the device")
    override fun flush(): Unit = error("planning must not flush the device")
}
