package dev.otgformat.app

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import me.jahnen.libaums.core.usb.UsbCommunication
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Our own USB transport for libaums.
 *
 * libaums' own implementation is used by default, but it cannot work on this
 * project's target hardware for two reasons, both confirmed on a Pixel 10.
 *
 * **It never selects the alternate setting.** A USB 3 drive exposes interface 0
 * twice: alternate setting 0 is bulk-only transport, alternate setting 1 is
 * UAS. The kernel prefers UAS, so that is the setting left active — and the
 * bulk-only endpoints from setting 0 simply do not exist while it is. Claiming
 * the interface is not enough; the alternate setting has to be selected, which
 * is exactly what `UsbDeviceConnection.setInterface` is for. libaums never
 * calls it, so every transfer went to an endpoint that was not there.
 *
 * **Its reset and halt-clearing are native.** `libusb-lib.so` in libaums 0.10.0
 * predates the 16 KB memory pages that recent Pixels use, so `System
 * .loadLibrary` fails there and every native call throws `UnsatisfiedLinkError`
 * — including the `ErrNo` lookup, which is why its errors report "errno 0
 * null". Both operations are ordinary USB control requests, so they are done
 * here with `controlTransfer` and no native code at all.
 *
 * Implementing the interface directly also removes the need to set libaums'
 * global transport preference, which was a process-wide mutable static.
 */
internal class DirectUsbCommunication(
    private val connection: UsbDeviceConnection,
    override val usbInterface: UsbInterface,
    override val inEndpoint: UsbEndpoint,
    override val outEndpoint: UsbEndpoint,
) : UsbCommunication {

    private var closed = false

    /**
     * Advancing the buffer position by the number of bytes moved is part of the
     * contract: `ScsiBlockDevice` loops on partial transfers until the position
     * reaches the limit, so a transport that leaves the position alone spins
     * forever on any short read.
     */
    override fun bulkOutTransfer(src: ByteBuffer): Int {
        check(!closed) { "device is closed" }
        val moved = connection.bulkTransfer(
            outEndpoint, src.array(), src.position(), src.remaining(), TIMEOUT,
        )
        if (moved < 0) throw IOException("bulk write to endpoint 0x%02x failed".format(outEndpoint.address))
        src.position(src.position() + moved)
        return moved
    }

    override fun bulkInTransfer(dest: ByteBuffer): Int {
        check(!closed) { "device is closed" }
        val moved = connection.bulkTransfer(
            inEndpoint, dest.array(), dest.position(), dest.remaining(), TIMEOUT,
        )
        if (moved < 0) throw IOException("bulk read from endpoint 0x%02x failed".format(inEndpoint.address))
        dest.position(dest.position() + moved)
        return moved
    }

    override fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray,
        length: Int,
    ): Int {
        check(!closed) { "device is closed" }
        return connection.controlTransfer(requestType, request, value, index, buffer, length, TIMEOUT)
    }

    /**
     * Standard CLEAR_FEATURE(ENDPOINT_HALT).
     *
     * bmRequestType 0x02 is host-to-device, standard, endpoint recipient;
     * bRequest 0x01 is CLEAR_FEATURE; wValue 0 is ENDPOINT_HALT; wIndex is the
     * endpoint address. No native code required.
     */
    override fun clearFeatureHalt(endpoint: UsbEndpoint) {
        val result = connection.controlTransfer(
            REQUEST_TYPE_CLEAR_ENDPOINT_HALT, REQUEST_CLEAR_FEATURE, ENDPOINT_HALT,
            endpoint.address, null, 0, TIMEOUT,
        )
        if (result < 0) {
            throw IOException("could not clear halt on endpoint 0x%02x".format(endpoint.address))
        }
    }

    /**
     * Bulk-Only Mass Storage Reset, then clear both endpoints.
     *
     * This is the reset the mass-storage class defines, rather than a
     * port-level reset: it puts the device's command state back to idle without
     * dropping the connection, so the claim and the alternate setting survive.
     */
    override fun resetDevice() {
        val result = connection.controlTransfer(
            REQUEST_TYPE_BULK_ONLY_RESET, REQUEST_BULK_ONLY_RESET, 0,
            usbInterface.id, null, 0, TIMEOUT,
        )
        if (result < 0) throw IOException("bulk-only mass storage reset was refused")
        clearFeatureHalt(inEndpoint)
        clearFeatureHalt(outEndpoint)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
    }

    companion object {
        private const val TIMEOUT = UsbCommunication.TRANSFER_TIMEOUT

        private const val REQUEST_TYPE_CLEAR_ENDPOINT_HALT = 0x02
        private const val REQUEST_CLEAR_FEATURE = 0x01
        private const val ENDPOINT_HALT = 0x00

        private const val REQUEST_TYPE_BULK_ONLY_RESET = 0x21
        private const val REQUEST_BULK_ONLY_RESET = 0xFF
    }
}
