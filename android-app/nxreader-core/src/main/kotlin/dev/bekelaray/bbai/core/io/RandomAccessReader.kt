package dev.bekelaray.bbai.core.io

import java.io.Closeable
import kotlin.math.min

interface RandomAccessReader : Closeable {
    val size: Long

    suspend fun readAt(position: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Int

    override fun close() = Unit
}

class SliceReadSource(
    private val parent: RandomAccessReader,
    private val baseOffset: Long,
    override val size: Long,
) : RandomAccessReader {
    override suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position < 0 || position >= size) return -1
        val boundedLength = min(length.toLong(), size - position).toInt()
        return parent.readAt(baseOffset + position, buffer, offset, boundedLength)
    }

    override fun close() = Unit
}

class OwnedSliceReadSource(
    private val parent: RandomAccessReader,
    private val delegate: SliceReadSource,
) : RandomAccessReader {
    override val size: Long
        get() = delegate.size

    override suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        delegate.readAt(position, buffer, offset, length)

    override fun close() {
        parent.close()
    }
}

suspend fun RandomAccessReader.readExactAt(position: Long, length: Int): ByteArray {
    require(length >= 0)
    val buffer = ByteArray(length)
    var total = 0
    while (total < length) {
        val read = readAt(position + total, buffer, total, length - total)
        if (read <= 0) break
        total += read
    }
    return if (total == length) buffer else buffer.copyOf(total)
}

fun ByteArray.leInt(offset: Int): Int {
    return (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)
}

fun ByteArray.leUShort(offset: Int): Int {
    return (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8)
}

fun ByteArray.leLong(offset: Int): Long {
    var value = 0L
    for (index in 0 until 8) {
        value = value or ((this[offset + index].toLong() and 0xFF) shl (index * 8))
    }
    return value
}

fun ByteArray.ascii(offset: Int, length: Int): String {
    if (offset !in indices || length <= 0) return ""
    val upperBound = min(offset + length, size)
    val end = (offset until upperBound)
        .firstOrNull { this[it] == 0.toByte() }
        ?: upperBound
    return copyOfRange(offset, end).decodeToString().trim()
}

fun ByteArray.hex(offset: Int, length: Int): String =
    if (offset !in indices || length <= 0) "" else copyOfRange(offset, min(offset + length, size)).joinToString(separator = "") { "%02X".format(it) }
