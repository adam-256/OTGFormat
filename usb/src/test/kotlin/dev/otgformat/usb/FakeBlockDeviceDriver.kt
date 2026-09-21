package dev.otgformat.usb

import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.io.IOException
import java.nio.ByteBuffer

/** One transfer libaums was asked to perform, recorded for assertions. */
data class Transfer(
    val write: Boolean,
    val lba: Long,
    val bytes: Int,
    val bufferPosition: Int,
    val bufferLimit: Int,
    val arrayOffset: Int,
    val backingArraySize: Int,
)

/**
 * An in-memory [BlockDeviceDriver] that behaves the way a real one does.
 *
 * It addresses by logical block, enforces libaums' own
 * `remaining() % blockSize == 0` precondition, fails reads and writes past the
 * end of the medium, and records every transfer so tests can assert on the
 * shape of the buffers the adapter hands over.
 */
class FakeBlockDeviceDriver(
    val trueSectorCount: Long,
    override val blockSize: Int = 512,
    // BLOCK_COUNT matches what LibaumsSectorDevice.conventionOf reports for
    // anything that is not a ScsiBlockDevice, so the fake does not contradict
    // the rule under test. Tests that want the other convention pass it.
    private val convention: CapacityConvention = CapacityConvention.BLOCK_COUNT,
    /** Reports a capacity but accepts reads beyond it, like a lying device. */
    private val unboundedReads: Boolean = false,
) : BlockDeviceDriver {

    /** Reads at or above this sector fail, as a drive near its end might. */
    var failReadsFrom: Long = Long.MAX_VALUE

    val store = ByteArray((trueSectorCount * blockSize).toInt())
    val transfers = mutableListOf<Transfer>()
    var initCalled = 0; private set

    override val blocks: Long
        get() = when (convention) {
            CapacityConvention.BLOCK_COUNT -> trueSectorCount
            CapacityConvention.LAST_BLOCK_ADDRESS -> trueSectorCount - 1
        }

    override fun init() {
        initCalled++
    }

    override fun read(deviceOffset: Long, buffer: ByteBuffer) {
        val length = record(write = false, lba = deviceOffset, buffer = buffer)
        if (deviceOffset >= failReadsFrom) throw IOException("read at $deviceOffset refused")
        val from = (deviceOffset * blockSize).toInt()
        if (unboundedReads && deviceOffset + length / blockSize > trueSectorCount) {
            buffer.put(ByteArray(length))
            return
        }
        buffer.put(store, from, length)
    }

    override fun write(deviceOffset: Long, buffer: ByteBuffer) {
        val length = record(write = true, lba = deviceOffset, buffer = buffer)
        val into = (deviceOffset * blockSize).toInt()
        buffer.get(store, into, length)
    }

    private fun record(write: Boolean, lba: Long, buffer: ByteBuffer): Int {
        // libaums' own precondition, reproduced so a violation fails here
        // rather than on a stick.
        require(buffer.remaining() % blockSize == 0) { "buffer.remaining() must be multiple of blockSize!" }
        val length = buffer.remaining()
        transfers += Transfer(
            write = write,
            lba = lba,
            bytes = length,
            bufferPosition = buffer.position(),
            bufferLimit = buffer.limit(),
            arrayOffset = buffer.arrayOffset(),
            backingArraySize = buffer.array().size,
        )
        if (lba < 0 || !unboundedReads && lba + length / blockSize > trueSectorCount) {
            throw IOException("access to sector $lba is past the end of a $trueSectorCount sector device")
        }
        return length
    }

    /** ByteBuffer.get/put on an absolute store, mirroring the adapter's own view. */
    fun sector(index: Long): ByteArray =
        store.copyOfRange((index * blockSize).toInt(), ((index + 1) * blockSize).toInt())
}
