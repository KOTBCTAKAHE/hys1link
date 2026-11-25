package io.github.kotbctakahe.hys1link.proto

import java.io.ByteArrayOutputStream

/**
 * Minimal protobuf wire-format codec. Only what the Signal/Link protocol needs:
 * varint, fixed64, length-delimited. Unknown fields are skipped.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    private fun rawVarint(v: Long) {
        var value = v
        while (true) {
            if (value and 0x7FL.inv() == 0L) { out.write(value.toInt()); return }
            out.write(((value and 0x7F) or 0x80).toInt())
            value = value ushr 7
        }
    }

    private fun tag(field: Int, wire: Int) = rawVarint(((field shl 3) or wire).toLong())

    fun uint(field: Int, v: Long?): ProtoWriter { if (v != null) { tag(field, 0); rawVarint(v) }; return this }
    fun uint(field: Int, v: Int?): ProtoWriter = uint(field, v?.toLong())
    fun bool(field: Int, v: Boolean?): ProtoWriter = uint(field, v?.let { if (it) 1L else 0L })
    fun fixed64(field: Int, v: Long?): ProtoWriter {
        if (v != null) { tag(field, 1); for (i in 0 until 8) out.write(((v ushr (8 * i)) and 0xFF).toInt()) }
        return this
    }
    fun bytes(field: Int, v: ByteArray?): ProtoWriter {
        if (v != null) { tag(field, 2); rawVarint(v.size.toLong()); out.write(v) }
        return this
    }
    fun string(field: Int, v: String?): ProtoWriter = bytes(field, v?.toByteArray(Charsets.UTF_8))
    fun message(field: Int, v: ProtoWriter?): ProtoWriter = bytes(field, v?.toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()
}

class ProtoField(val number: Int, val wire: Int, val varint: Long, val data: ByteArray?) {
    val asInt get() = varint.toInt()
    val asLong get() = varint
    val asBool get() = varint != 0L
    val asString get() = String(data ?: ByteArray(0), Charsets.UTF_8)
    val asBytes get() = data ?: ByteArray(0)
    fun asMessage() = ProtoMessage.parse(asBytes)
}

class ProtoMessage private constructor(val fields: List<ProtoField>) {
    fun first(n: Int): ProtoField? = fields.firstOrNull { it.number == n }
    fun all(n: Int): List<ProtoField> = fields.filter { it.number == n }
    fun has(n: Int) = fields.any { it.number == n }

    fun int(n: Int): Int? = first(n)?.asInt
    fun long(n: Int): Long? = first(n)?.asLong
    fun bool(n: Int): Boolean? = first(n)?.asBool
    fun string(n: Int): String? = first(n)?.asString
    fun bytes(n: Int): ByteArray? = first(n)?.asBytes
    fun msg(n: Int): ProtoMessage? = first(n)?.asMessage()

    /** Repeated uint64 that may be packed or unpacked. */
    fun longs(n: Int): List<Long> = all(n).flatMap { f ->
        if (f.wire == 2) ProtoReader(f.asBytes).let { r -> buildList { while (!r.eof()) add(r.readVarint()) } }
        else listOf(f.varint)
    }

    companion object {
        fun parse(bytes: ByteArray): ProtoMessage {
            val r = ProtoReader(bytes)
            val list = ArrayList<ProtoField>()
            while (!r.eof()) {
                val key = r.readVarint()
                val number = (key ushr 3).toInt()
                when (val wire = (key and 7).toInt()) {
                    0 -> list += ProtoField(number, wire, r.readVarint(), null)
                    1 -> list += ProtoField(number, wire, r.readFixed64(), null)
                    2 -> list += ProtoField(number, wire, 0, r.readBytes(r.readVarint().toInt()))
                    5 -> list += ProtoField(number, wire, r.readFixed32(), null)
                    else -> throw IllegalArgumentException("Unsupported wire type $wire")
                }
            }
            return ProtoMessage(list)
        }
    }
}

class ProtoReader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {
    fun eof() = pos >= end
    fun position() = pos
    fun readVarint(): Long {
        var shift = 0
        var result = 0L
        while (shift < 64) {
            require(pos < end) { "Truncated varint" }
            val b = buf[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw IllegalArgumentException("Malformed varint")
    }
    fun readFixed64(): Long {
        require(pos + 8 <= end) { "Truncated fixed64" }
        var v = 0L
        for (i in 0 until 8) v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 8
        return v
    }
    fun readFixed32(): Long {
        require(pos + 4 <= end) { "Truncated fixed32" }
        var v = 0L
        for (i in 0 until 4) v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 4
        return v
    }
    fun readBytes(len: Int): ByteArray {
        require(len >= 0 && pos + len <= end) { "Truncated bytes" }
        val r = buf.copyOfRange(pos, pos + len)
        pos += len
        return r
    }
}
