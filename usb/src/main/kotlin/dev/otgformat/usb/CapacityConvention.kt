package dev.otgformat.usb

/**
 * What a [me.jahnen.libaums.core.driver.BlockDeviceDriver] means by `blocks`.
 *
 * libaums' two implementations disagree, and one of them disagrees with the
 * interface's own documentation, so the meaning has to be decided per driver
 * rather than assumed. See [LibaumsSectorDevice.determineSectorCount].
 */
enum class CapacityConvention {
    /** `blocks` is the number of blocks, as `FileBlockDeviceDriver` reports. */
    BLOCK_COUNT,

    /** `blocks` is the last valid address, as `ScsiBlockDevice` reports. */
    LAST_BLOCK_ADDRESS,
}
