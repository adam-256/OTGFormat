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
import dev.otgformat.usb.UsbTarget
import me.jahnen.libaums.core.driver.BlockDeviceDriverFactory
import me.jahnen.libaums.core.usb.UsbCommunication
import me.jahnen.libaums.core.usb.UsbCommunicationFactory
import java.io.Closeable
import java.io.IOException

/**
 * A mass-storage interface found on an attached USB device.
 *
 * Discovery is done here rather than through `UsbMassStorageDevice` on purpose.
 * That class's `init()` reads and parses the partition table, and throws when it
 * cannot — which is the normal state of a blank, corrupt or
 * previously-half-formatted stick, i.e. exactly the device someone reaches for
 * this app to fix. A formatter must be able to open a drive whose contents make
 * no sense at all, so it takes the interface and endpoints itself and builds
 * the raw block device directly.
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
     * combination the transport below speaks.
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
     * Asks the system for permission to use [device].
     *
     * The PendingIntent must be mutable: the system fills in
     * `EXTRA_DEVICE` and `EXTRA_PERMISSION_GRANTED` before delivering it, and
     * an immutable one silently arrives with neither.
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
     *
     * The broadcast is our own action, delivered via our own PendingIntent, so
     * it is registered as not-exported — required from API 34 and correct
     * before it.
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

        // Argument order is (out, in) — swapping them sends every command down
        // the read endpoint and nothing works.
        val communication = UsbCommunicationFactory.createUsbCommunication(
            usbManager,
            candidate.device,
            candidate.usbInterface,
            candidate.outEndpoint,
            candidate.inEndpoint,
        )

        try {
            // LUN 0: a flash drive has exactly one logical unit. Multi-slot
            // card readers expose more, but they present each slot as its own
            // interface, so 0 is right for each of them too.
            val driver = BlockDeviceDriverFactory.createBlockDevice(communication, 0)
            val sectorDevice = LibaumsSectorDevice.open(driver)

            val target = UsbTarget(
                vendorName = candidate.device.manufacturerName,
                productName = candidate.device.productName,
                // Reading the serial needs the permission we just checked for;
                // some devices still refuse, and a missing serial is not fatal.
                serialNumber = runCatching { candidate.device.serialNumber }.getOrNull(),
                vendorId = candidate.device.vendorId,
                productId = candidate.device.productId,
                blockSize = sectorDevice.blockSize,
                sectorCount = sectorDevice.sectorCount,
            )
            return OpenTarget(target, sectorDevice, communication)
        } catch (e: Throwable) {
            runCatching { communication.close() }
            throw e
        }
    }

    companion object {
        /** Our own action; the system delivers it back through our PendingIntent. */
        const val ACTION_USB_PERMISSION = "dev.otgformat.app.USB_PERMISSION"

        private const val INTERFACE_SUBCLASS_SCSI = 6
        private const val INTERFACE_PROTOCOL_BULK_ONLY = 80
    }
}
