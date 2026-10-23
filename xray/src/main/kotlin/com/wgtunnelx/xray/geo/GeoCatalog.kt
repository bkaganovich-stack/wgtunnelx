package com.wgtunnelx.xray.geo

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream

/**
 * Reads the category codes (`geosite:<code>` / `geoip:<code>`) out of a v2ray `.dat` file without
 * decoding the domain/CIDR payload.
 *
 * Both files are a protobuf list: `message GeoSiteList { repeated GeoSite entry = 1; }` where the
 * first field of every entry is `string country_code = 1`. Only that string is read; the rest of
 * each entry is skipped, so a 70 MB geosite.dat is scanned in well under a second.
 */
object GeoCatalog {

    fun readCodes(file: File): List<String> =
        BufferedInputStream(file.inputStream(), 64 * 1024).use { readCodes(it) }

    fun readCodes(input: InputStream): List<String> {
        val codes = ArrayList<String>(2048)
        while (true) {
            val key = readVarint(input, allowEof = true) ?: break
            val wireType = (key and 0x7).toInt()
            if (wireType != 2)
                throw IllegalArgumentException("Not a geo .dat list (wire $wireType)")
            val entryLength = readVarint(input)!!
            var consumed = 0L
            // first field of the entry: country_code
            val innerKey = readVarint(input)!!
            consumed += varintSize(innerKey)
            if ((innerKey ushr 3) == 1L && (innerKey and 0x7) == 2L) {
                val codeLength = readVarint(input)!!
                consumed += varintSize(codeLength)
                val bytes = ByteArray(codeLength.toInt())
                readFully(input, bytes)
                consumed += codeLength
                codes += String(bytes, Charsets.UTF_8)
            }
            skipFully(input, entryLength - consumed)
        }
        return codes
    }

    private fun readVarint(input: InputStream, allowEof: Boolean = false): Long? {
        var result = 0L
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (allowEof && shift == 0) return null
                throw EOFException("Truncated varint")
            }
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw IllegalArgumentException("Malformed varint")
        }
    }

    private fun varintSize(value: Long): Int {
        var v = value
        var size = 1
        while (v >= 0x80) {
            v = v ushr 7
            size++
        }
        return size
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) throw EOFException("Truncated entry")
            offset += n
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) throw EOFException("Truncated entry")
                remaining--
            } else remaining -= skipped
        }
    }
}
