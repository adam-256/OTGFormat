package dev.otgformat.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.otgformat.core.SectorDevice
import dev.otgformat.usb.LibaumsSectorDevice
import dev.otgformat.usb.SelfTestReport
import dev.otgformat.usb.SelfTestStep
import dev.otgformat.usb.UsbSelfTest
import dev.otgformat.usb.UsbTarget
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.driver.BlockDeviceDriverFactory
import me.jahnen.libaums.core.usb.UsbCommunication
import java.io.Closeable
import java.io.IOException

/**
 * A mass-storage interface found on an attached USB device.
 *
 * Discovery is done here rather than through `UsbMassStorageDevice` on purpose.
 * That class's `init()` reads and parses the partition table, and throws when it
 * cannot — which is the normal state of the blank, corrupt or
 * previously-half-formatted stick someone reaches for this app to fix. A
 * formatter must be able to open a drive whose contents make no sense at all,
 * so it takes the interface and endpoints itself and builds the raw block
 * device directly.
 */
data class MassStorageCandidate(
    val device: UsbDevice,
    val usbInterface: UsbInterface,
    val inEndpoint: UsbEndpoint,
    val outEndpoint: UsbEndpoint,
) {
    val key: String get() = "${device.deviceName}#${usbInterface.id}"
}

/** A claimed device, open for raw sector access, and the resources to release. */
class OpenTarget(
    val target: UsbTarget,
    val sectorDevice: SectorDevice,
    private val communication: UsbCommunication,
) : Closeable {
    override fun close() {
        runCatching { communication.close() }
    }
}

/**
 * USB enumeration, permission and raw block access.
 */
class UsbAccess(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    /**
     * Every mass-storage interface currently attached.
     *
     * The class/subclass/protocol triple is the same one libaums matches:
     * mass storage, SCSI transparent command set, Bulk-Only Transport. That is
     * what every USB flash drive and card reader presents, and the only
     * combination the transport below speaks. A USB 3 drive commonly also
     * exposes a UAS interface (protocol 98); it is deliberately not matched,
     * because nothing here can speak it.
     */
    fun findCandidates(): List<MassStorageCandidate> =
        usbManager.deviceList.values.flatMap { device ->
            (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .filter {
                    it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                        it.interfaceSubclass == INTERFACE_SUBCLASS_SCSI &&
                        it.interfaceProtocol == INTERFACE_PROTOCOL_BULK_ONLY
                }
                .mapNotNull { usbInterface ->
                    var inEndpoint: UsbEndpoint? = null
                    var outEndpoint: UsbEndpoint? = null
                    for (i in 0 until usbInterface.endpointCount) {
                        val endpoint = usbInterface.getEndpoint(i)
                        if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                        if (endpoint.direction == UsbConstants.USB_DIR_OUT) outEndpoint = endpoint
                        else inEndpoint = endpoint
                    }
                    if (inEndpoint == null || outEndpoint == null) null
                    else MassStorageCandidate(device, usbInterface, inEndpoint, outEndpoint)
                }
        }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /**
     * Identity straight from the USB descriptors.
     *
     * Needs no claim and no SCSI, so the user can always be shown which device
     * is selected — even when talking to it fails. Capacity is left unknown
     * until [open] fills it in.
     */
    fun identify(candidate: MassStorageCandidate): UsbTarget = UsbTarget(
        vendorName = candidate.device.manufacturerName,
        productName = candidate.device.productName,
        serialNumber = runCatching { candidate.device.serialNumber }.getOrNull(),
        vendorId = candidate.device.vendorId,
        productId = candidate.device.productId,
    )

    /**
     * Asks the system for permission to use [device].
     *
     * The PendingIntent must be mutable: the system fills in `EXTRA_DEVICE` and
     * `EXTRA_PERMISSION_GRANTED` before delivering it, and an immutable one
     * arrives with neither.
     */
    fun requestPermission(device: UsbDevice) {
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        usbManager.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
    }

    /**
     * Registers [onResult] for permission replies. Returns the receiver so the
     * caller can unregister it.
     */
    fun registerPermissionReceiver(onResult: (UsbDevice?, Boolean) -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                @Suppress("DEPRECATION")
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                onResult(device, intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        return receiver
    }

    // ---- bringing the device up ------------------------------------------

    /**
     * One way of trying to bring a device up.
     *
     * Bridges differ in which of these they tolerate, and a drive that refuses
     * the first rung often works on a later one, so the ladder is walked rather
     * than a single approach being assumed.
     */
    private data class Rung(
        val label: String,
        /** Issue SET_INTERFACE to select the bulk-only alternate setting. */
        val selectAlternateSetting: Boolean,
        /** Issue a bulk-only mass storage reset and clear both endpoint halts. */
        val bulkReset: Boolean,
    )

    /**
     * Ways of bringing a drive up, most likely first.
     *
     * Selecting the alternate setting comes first because it is what a USB 3
     * drive needs: the kernel leaves such a drive in its UAS setting, where the
     * bulk-only endpoints do not exist. The later rungs exist so that a drive
     * which does not need it, or which refuses SET_INTERFACE, still has a way
     * through — and so the report says which one it took.
     */
    private val ladder = listOf(
        Rung("the bulk-only alternate setting selected", selectAlternateSetting = true, bulkReset = false),
        Rung("the bulk-only alternate setting, after a bulk reset", selectAlternateSetting = true, bulkReset = true),
        Rung("the alternate setting left alone", selectAlternateSetting = false, bulkReset = false),
        Rung("the alternate setting left alone, after a bulk reset", selectAlternateSetting = false, bulkReset = true),
    )

    /**
     * Claims [candidate] and opens it for raw sector access.
     *
     * libaums' communication layer claims the interface with `forceClaim`,
     * which detaches the kernel's `usb-storage` driver. That is what makes this
     * possible without root: the permission model explicitly allows an app to
     * take a USB device it has been granted.
     */
    @Throws(IOException::class)
    fun open(candidate: MassStorageCandidate): OpenTarget {
        require(hasPermission(candidate.device)) { "USB permission has not been granted for this device" }
        val failures = mutableListOf<String>()
        for (rung in ladder) {
            try {
                return openVia(candidate, rung).target
            } catch (e: Throwable) {
                failures += "${rung.label}: ${e.describe()}"
            }
        }
        throw IOException(
            "Could not talk to this drive. Tried:\n" + failures.joinToString("\n") { "  - $it" },
        )
    }

    /** A successfully opened device, keeping the raw driver for the self-test. */
    private class Opened(
        val target: OpenTarget,
        val driver: BlockDeviceDriver,
        val communication: UsbCommunication,
    )

    private fun openVia(
        candidate: MassStorageCandidate,
        rung: Rung,
        notes: MutableList<String> = mutableListOf(),
    ): Opened {
        val communication = createCommunication(candidate, rung, notes)
        try {
            val driver = BlockDeviceDriverFactory.createBlockDevice(communication, 0)
            val sectorDevice = LibaumsSectorDevice.open(driver)
            val target = OpenTarget(
                identify(candidate).copy(
                    blockSize = sectorDevice.blockSize,
                    sectorCount = sectorDevice.sectorCount,
                ),
                sectorDevice,
                communication,
            )
            return Opened(target, driver, communication)
        } catch (t: Throwable) {
            runCatching { communication.close() }
            throw t
        }
    }

    /**
     * Opens the transport and performs the Bulk-Only Transport handshake.
     *
     * Three things happen here that libaums' own setup does not do, each one
     * confirmed necessary on real hardware:
     *
     * `setInterface` selects the bulk-only alternate setting. A USB 3 drive
     * exposes interface 0 twice — setting 0 is bulk-only, setting 1 is UAS —
     * and the kernel leaves the UAS setting active, so the bulk-only endpoints
     * do not exist until this call is made. Claiming the interface alone is not
     * enough.
     *
     * The GET MAX LUN request completes the class handshake. It is legal for a
     * single-LUN device to stall it, so a refusal is recorded, not fatal.
     *
     * And the whole thing runs over [DirectUsbCommunication], which uses no
     * native code — libaums' native helpers do not load on recent Pixels.
     */
    private fun createCommunication(
        candidate: MassStorageCandidate,
        rung: Rung,
        notes: MutableList<String> = mutableListOf(),
    ): UsbCommunication {
        val connection = usbManager.openDevice(candidate.device)
            ?: throw IOException("Android would not open this device.")
        try {
            // forceClaim detaches whatever kernel driver holds the interface.
            if (!connection.claimInterface(candidate.usbInterface, true)) {
                throw IOException("could not claim the interface")
            }
            if (rung.selectAlternateSetting) {
                if (!connection.setInterface(candidate.usbInterface)) {
                    throw IOException(
                        "could not select alternate setting ${candidate.usbInterface.alternateSetting}",
                    )
                }
                notes += "Selected alternate setting ${candidate.usbInterface.alternateSetting} " +
                    "(bulk-only transport)."
            }

            val communication = DirectUsbCommunication(
                connection,
                candidate.usbInterface,
                candidate.inEndpoint,
                candidate.outEndpoint,
            )
            try {
                if (rung.bulkReset) communication.resetDevice()
                val maxLun = getMaxLun(communication, candidate)
                notes += if (maxLun >= 0) "GET MAX LUN reported $maxLun."
                else "The drive declined GET MAX LUN, which is allowed."
                return communication
            } catch (t: Throwable) {
                communication.close()
                throw t
            }
        } catch (t: Throwable) {
            runCatching { connection.close() }
            throw t
        }
    }

    /** Returns the highest LUN, or -1 when the device declines to say. */
    private fun getMaxLun(communication: UsbCommunication, candidate: MassStorageCandidate): Int {
        val buffer = ByteArray(1)
        return try {
            val transferred = communication.controlTransfer(
                GET_MAX_LUN_REQUEST_TYPE,
                GET_MAX_LUN_REQUEST,
                0,
                candidate.usbInterface.id,
                buffer,
                1,
            )
            if (transferred < 1) -1 else buffer[0].toInt() and 0xFF
        } catch (e: Exception) {
            -1
        }
    }

    // ---- diagnostics ------------------------------------------------------

    /**
     * Runs the read-only self-test, reporting every stage.
     *
     * Unlike [open] this does not stop at the first thing that works: it
     * records the USB topology, then each rung of the ladder, then the SCSI
     * checks against whichever rung succeeded. Nothing is written, so it is
     * safe on a drive full of data — and because it reports the rungs that
     * failed as well as the one that worked, a drive that only comes up after a
     * reset says so plainly instead of looking healthy.
     */
    fun diagnose(candidate: MassStorageCandidate): SelfTestReport {
        val steps = mutableListOf<SelfTestStep>()
        val notes = mutableListOf<String>()

        steps += SelfTestStep("USB permission", hasPermission(candidate.device), "granted")
        notes += describeTopology(candidate.device)

        if (!hasPermission(candidate.device)) {
            return SelfTestReport(
                listOf(SelfTestStep("USB permission", false, "not granted")),
                notes,
            )
        }

        var opened: Opened? = null
        var workingRung: String? = null
        for (rung in ladder) {
            if (opened != null) break
            val rungNotes = mutableListOf<String>()
            try {
                opened = openVia(candidate, rung, rungNotes)
                workingRung = rung.label
                steps += SelfTestStep(
                    "Open with ${rung.label}",
                    true,
                    (listOf("worked") + rungNotes).joinToString(" "),
                )
            } catch (e: Throwable) {
                steps += SelfTestStep(
                    "Open with ${rung.label}",
                    false,
                    (listOf(e.describe()) + rungNotes).joinToString(" "),
                )
            }
        }

        if (opened == null) {
            notes += "The drive could not be opened by any method. If Android has it mounted, ejecting it " +
                "in Files and running this again is the next thing to try."
            return SelfTestReport(steps, notes)
        }

        return try {
            // The SCSI layer is already initialised by the successful open, so
            // the checks run against the raw driver without a second INQUIRY.
            // The past-end check deliberately stalls the endpoint, so the
            // device is handed a reset afterwards rather than left wedged.
            val scsi = UsbSelfTest.run(
                opened.driver,
                initialise = false,
                recover = { runCatching { opened.communication.resetDevice() } },
            )
            if (workingRung != ladder.first().label) {
                notes += "Note: this drive only came up on \"$workingRung\". The app will do the same thing " +
                    "automatically when formatting."
            }
            SelfTestReport(steps + scsi.steps, notes + scsi.notes)
        } finally {
            opened.target.close()
        }
    }

    /**
     * The whole USB picture, from the descriptors alone.
     *
     * Needs no claim, so it is available even when nothing else works — which
     * is exactly when it is worth having.
     */
    fun describeTopology(device: UsbDevice): String = buildString {
        appendLine("USB topology")
        appendLine(
            String.format(
                java.util.Locale.ROOT,
                "  device %04x:%04x  class %d/%d/%d  %s",
                device.vendorId, device.productId,
                device.deviceClass, device.deviceSubclass, device.deviceProtocol,
                device.deviceName,
            ),
        )
        for (i in 0 until device.interfaceCount) {
            val f = device.getInterface(i)
            val kind = when {
                f.interfaceClass != UsbConstants.USB_CLASS_MASS_STORAGE -> "not mass storage"
                f.interfaceProtocol == INTERFACE_PROTOCOL_BULK_ONLY -> "bulk-only transport (usable)"
                f.interfaceProtocol == INTERFACE_PROTOCOL_UAS -> "UAS (not supported)"
                else -> "mass storage, unknown protocol"
            }
            appendLine(
                "  interface ${f.id} alt ${f.alternateSetting}: " +
                    "${f.interfaceClass}/${f.interfaceSubclass}/${f.interfaceProtocol} — $kind",
            )
            for (e in 0 until f.endpointCount) {
                val ep = f.getEndpoint(e)
                val direction = if (ep.direction == UsbConstants.USB_DIR_OUT) "out" else "in "
                val type = when (ep.type) {
                    UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
                    UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
                    UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isochronous"
                    else -> "control"
                }
                appendLine(
                    String.format(
                        java.util.Locale.ROOT,
                        "    endpoint 0x%02x %s %-11s max packet %d",
                        ep.address, direction, type, ep.maxPacketSize,
                    ),
                )
            }
        }
    }.trimEnd()

    private fun Throwable.describe(): String {
        val own = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
        val root = generateSequence(cause) { it.cause }.lastOrNull()
        return if (root != null && root !== this) {
            "$own (caused by ${root.javaClass.simpleName}: ${root.message ?: "no detail"})"
        } else {
            own
        }
    }

    companion object {
        /** Our own action; the system delivers it back through our PendingIntent. */
        const val ACTION_USB_PERMISSION = "dev.otgformat.app.USB_PERMISSION"

        private const val INTERFACE_SUBCLASS_SCSI = 6
        private const val INTERFACE_PROTOCOL_BULK_ONLY = 80
        private const val INTERFACE_PROTOCOL_UAS = 98

        /** Bulk-Only Transport GET MAX LUN: class request, interface recipient, device-to-host. */
        private const val GET_MAX_LUN_REQUEST_TYPE = 0xA1
        private const val GET_MAX_LUN_REQUEST = 0xFE

    }
}
