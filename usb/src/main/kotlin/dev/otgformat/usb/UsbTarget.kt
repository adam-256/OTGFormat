package dev.otgformat.usb

/**
 * What is physically plugged in, as the user needs to see it.
 *
 * Deliberately a plain data class with no Android types: the Android layer
 * builds one from a `UsbDevice`, and everything that decides how dangerous a
 * format is stays testable on the JVM.
 */
data class UsbTarget(
    val vendorName: String?,
    val productName: String?,
    val serialNumber: String?,
    val vendorId: Int,
    val productId: Int,
    val blockSize: Int,
    val sectorCount: Long,
) {
    val capacityBytes: Long get() = sectorCount * blockSize

    /**
     * Capacity in decimal GB, which is how the number on the casing is
     * calculated.
     *
     * Showing GiB here would be a trust problem, not a precision one: a stick
     * sold as "64GB" reports about 57 GiB, and a user asked to confirm "57.3
     * GiB" against a label reading 64GB cannot tell whether they picked the
     * right device.
     */
    val capacityGb: Double get() = capacityBytes / 1_000_000_000.0

    fun capacityLabel(): String = when {
        capacityBytes >= 1_000_000_000L -> String.format(java.util.Locale.ROOT, "%.1f GB", capacityGb)
        else -> String.format(java.util.Locale.ROOT, "%.0f MB", capacityBytes / 1_000_000.0)
    }

    /** Vendor and product as reported, falling back to the USB IDs. */
    fun displayName(): String {
        val vendor = vendorName?.trim().orEmpty()
        val product = productName?.trim().orEmpty()
        val name = listOf(vendor, product).filter { it.isNotEmpty() }.joinToString(" ")
        return name.ifEmpty { String.format(java.util.Locale.ROOT, "USB device %04x:%04x", vendorId, productId) }
    }

    /**
     * The identity block shown before any write.
     *
     * Everything here exists so the user can check it against the object in
     * their hand — the serial in particular is the only field that
     * distinguishes two identical sticks.
     */
    fun describe(): String = buildString {
        appendLine(displayName())
        appendLine("  capacity : ${capacityLabel()}")
        appendLine("  serial   : ${serialNumber?.trim()?.ifEmpty { null } ?: "(not reported)"}")
        appendLine(String.format(java.util.Locale.ROOT, "  USB ID   : %04x:%04x", vendorId, productId))
        append("  geometry : $sectorCount sectors of $blockSize bytes")
    }
}
