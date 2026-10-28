package com.acecore.app.core.scripts

/**
 * Minimal reader for Ruby's Marshal format 4.8 — enough to decode RPG Maker script containers
 * (Data/Scripts.rxdata / .rvdata / .rvdata2) without a Ruby interpreter.
 *
 * Supported: nil, true, false, Fixnum, Bignum (as Long when it fits), Float, String (incl. encoding ivars),
 * Symbol, symbol links, object links, Array, Hash. Anything else throws [MarshalException].
 */
class RubyMarshal private constructor(private val b: ByteArray) {
    private var pos = 0
    private val symbols = ArrayList<String>()
    private val objects = ArrayList<Any?>()

    /** A Ruby String's raw bytes (encoding is decided by the caller). */
    class RString(val bytes: ByteArray) {
        fun text(): String = String(bytes, Charsets.UTF_8)
        override fun toString() = text()
    }

    data class RSymbol(val name: String)

    private fun byte(): Int {
        if (pos >= b.size) throw MarshalException("Unexpected end of data at $pos")
        return b[pos++].toInt() and 0xFF
    }

    private fun bytes(n: Int): ByteArray {
        if (n < 0 || pos + n > b.size) throw MarshalException("Bad length $n at $pos")
        return b.copyOfRange(pos, pos + n).also { pos += n }
    }

    /** Ruby's packed integer encoding (w_long). */
    private fun long(): Long {
        val c = b.getOrNull(pos++)?.toInt() ?: throw MarshalException("Unexpected end of data")
        return when {
            c == 0 -> 0L
            c in 5..127 -> (c - 5).toLong()
            c in -128..-5 -> (c + 5).toLong()
            c in 1..4 -> {
                var x = 0L
                for (i in 0 until c) x = x or ((byte().toLong()) shl (8 * i))
                x
            }
            else -> { // -4..-1
                val n = -c
                var x = -1L
                for (i in 0 until n) {
                    x = x and (0xFFL shl (8 * i)).inv()
                    x = x or (byte().toLong() shl (8 * i))
                }
                x
            }
        }
    }

    private fun len(): Int {
        val l = long()
        if (l < 0 || l > Int.MAX_VALUE) throw MarshalException("Bad length $l")
        return l.toInt()
    }

    private fun symbolBody(): String = String(bytes(len()), Charsets.UTF_8).also { symbols += it }

    private fun register(o: Any?): Any? { objects += o; return o }

    private fun value(): Any? {
        return when (val t = byte().toChar()) {
            '0' -> null
            'T' -> true
            'F' -> false
            'i' -> long()
            ':' -> RSymbol(symbolBody())
            ';' -> RSymbol(symbols.getOrNull(len()) ?: throw MarshalException("Bad symbol link"))
            '@' -> objects.getOrNull(len()) ?: throw MarshalException("Bad object link")
            '"' -> register(RString(bytes(len())))
            'I' -> { // value followed by instance variables (e.g. :E => true for UTF-8 strings)
                val v = value()
                val n = len()
                repeat(n) { value(); value() }
                v
            }
            '[' -> {
                val n = len()
                val list = ArrayList<Any?>(minOf(n, 65_536))
                register(list)
                repeat(n) { list += value() }
                list
            }
            '{', '}' -> {
                val n = len()
                val map = LinkedHashMap<Any?, Any?>()
                register(map)
                repeat(n) { val k = value(); map[k] = value() }
                if (t == '}') value() // default value
                map
            }
            'f' -> register(String(bytes(len()), Charsets.US_ASCII).let { s ->
                when (s) { "inf" -> Double.POSITIVE_INFINITY; "-inf" -> Double.NEGATIVE_INFINITY; "nan" -> Double.NaN; else -> s.toDoubleOrNull() ?: 0.0 }
            })
            'l' -> {
                val sign = byte().toChar()
                val words = len()
                val raw = bytes(words * 2)
                var x = 0L
                for (i in raw.indices.reversed()) x = (x shl 8) or (raw[i].toLong() and 0xFF)
                register(if (sign == '-') -x else x)
            }
            else -> throw MarshalException("Unsupported Marshal type '$t' at ${pos - 1}")
        }
    }

    companion object {
        fun read(data: ByteArray): Any? {
            if (data.size < 2 || data[0].toInt() != 4 || data[1].toInt() != 8) throw MarshalException("Not Ruby Marshal 4.8 data")
            return RubyMarshal(data).apply { pos = 2 }.value()
        }
    }
}

class MarshalException(message: String) : Exception(message)
