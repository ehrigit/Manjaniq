package com.manjaniq.quickconnect

import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal IPv4 / TCP / UDP packet helpers for the Quick Connect tunnel.
 *
 * These functions are deliberately free of Android dependencies so they can
 * be exercised by host-side unit tests (see TunPacketsTest). Addresses are
 * carried as raw big-endian [Int]s (the four bytes of the address, read in
 * network order into the high and low halves of the Int); ports and sequence
 * numbers follow RFC 793 semantics with wrapping arithmetic.
 */
internal object TunPackets {

    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    const val TCP_FIN = 0x001
    const val TCP_SYN = 0x002
    const val TCP_RST = 0x004
    const val TCP_PSH = 0x008
    const val TCP_ACK = 0x010

    private val ipId = AtomicInteger(1)

    fun nextIpId(): Int = ipId.getAndIncrement() and 0xFFFF

    // ------------------------------------------------------------------ IPs

    /** dotted-quad "1.2.3.4" -> Int, or null when the string is not an IPv4 literal. */
    fun parseIpv4Literal(text: String): Int? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        var result = 0
        for (part in parts) {
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            result = (result shl 8) or value
        }
        return result
    }

    fun ipString(ip: Int): String =
        "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"

    fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 24) and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 3] = (value and 0xFF).toByte()
    }

    fun readShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    fun writeShort(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 1] = (value and 0xFF).toByte()
    }

    // -------------------------------------------------------------- checks

    private fun addWord(sum: Long, word: Int): Long = sum + (word.toLong() and 0xFFFF)

    private fun onesComplementSum(bytes: ByteArray, offset: Int, length: Int, seed: Long): Long {
        var sum = seed
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum = addWord(sum, readShort(bytes, i))
            i += 2
        }
        if (i < end) sum = addWord(sum, (bytes[i].toInt() and 0xFF) shl 8)
        return sum
    }

    private fun fold(sum: Long): Int {
        var s = sum
        while (s ushr 16 != 0L) s = (s and 0xFFFF) + (s ushr 16)
        return (s.toInt() and 0xFFFF) xor 0xFFFF
    }

    /** RFC 1071 one's-complement checksum over [length] bytes. */
    fun checksum(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Int =
        fold(onesComplementSum(bytes, offset, length, 0L))

    /** Pseudo-header + segment checksum for TCP. */
    fun tcpChecksum(srcIp: Int, dstIp: Int, segment: ByteArray, offset: Int, length: Int): Int =
        transportChecksum(srcIp, dstIp, PROTO_TCP, segment, offset, length)

    /** Pseudo-header + segment checksum for UDP. */
    fun udpChecksum(srcIp: Int, dstIp: Int, segment: ByteArray, offset: Int, length: Int): Int =
        transportChecksum(srcIp, dstIp, PROTO_UDP, segment, offset, length)

    private fun transportChecksum(
        srcIp: Int,
        dstIp: Int,
        protocol: Int,
        segment: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        var sum = 0L
        sum = addWord(sum, srcIp ushr 16)
        sum = addWord(sum, srcIp and 0xFFFF)
        sum = addWord(sum, dstIp ushr 16)
        sum = addWord(sum, dstIp and 0xFFFF)
        sum = addWord(sum, protocol)
        sum = addWord(sum, length)
        return fold(onesComplementSum(segment, offset, length, sum))
    }

    // --------------------------------------------------------------- IPv4

    data class Ipv4Packet(
        val totalLength: Int,
        val protocol: Int,
        val srcIp: Int,
        val dstIp: Int,
        val payloadOffset: Int,
        val payloadLength: Int,
        val moreFragments: Boolean,
        val fragmentOffset: Int,
    )

    fun parseIpv4(packet: ByteArray, length: Int): Ipv4Packet? {
        if (length < 20 || length > packet.size) return null
        val versionIhl = packet[0].toInt() and 0xFF
        if (versionIhl ushr 4 != 4) return null
        val ihl = (versionIhl and 0x0F) * 4
        if (ihl < 20 || ihl > length) return null
        val totalLength = readShort(packet, 2)
        if (totalLength > length || totalLength < ihl) return null
        val flagsFragment = readShort(packet, 6)
        val moreFragments = (flagsFragment and 0x2000) != 0
        val fragmentOffset = flagsFragment and 0x1FFF
        val protocol = packet[9].toInt() and 0xFF
        val srcIp = readInt(packet, 12)
        val dstIp = readInt(packet, 16)
        return Ipv4Packet(
            totalLength = totalLength,
            protocol = protocol,
            srcIp = srcIp,
            dstIp = dstIp,
            payloadOffset = ihl,
            payloadLength = totalLength - ihl,
            moreFragments = moreFragments,
            fragmentOffset = fragmentOffset,
        )
    }

    fun buildIpv4(
        srcIp: Int,
        dstIp: Int,
        protocol: Int,
        payload: ByteArray,
        payloadOffset: Int,
        payloadLength: Int,
        ttl: Int = 64,
        id: Int = nextIpId(),
    ): ByteArray {
        val total = 20 + payloadLength
        val out = ByteArray(total)
        out[0] = 0x45 // version 4, IHL 5 (no options)
        out[1] = 0
        writeShort(out, 2, total)
        writeShort(out, 4, id and 0xFFFF)
        out[6] = 0x40 // DF: do not fragment
        out[7] = 0
        out[8] = ttl.toByte()
        out[9] = protocol.toByte()
        writeInt(out, 12, srcIp)
        writeInt(out, 16, dstIp)
        System.arraycopy(payload, payloadOffset, out, 20, payloadLength)
        val headerChecksum = checksum(out, 0, 20)
        writeShort(out, 10, headerChecksum)
        return out
    }

    // ---------------------------------------------------------------- TCP

    data class TcpSegment(
        val srcPort: Int,
        val dstPort: Int,
        val seq: Int,
        val ack: Int,
        val dataOffset: Int, // full TCP header length in bytes
        val flags: Int,
        val window: Int,
        val payloadOffset: Int, // absolute offset into the packet array
        val payloadLength: Int,
    )

    fun parseTcp(packet: ByteArray, offset: Int, length: Int): TcpSegment? {
        if (length < 20) return null
        val srcPort = readShort(packet, offset)
        val dstPort = readShort(packet, offset + 2)
        val seq = readInt(packet, offset + 4)
        val ack = readInt(packet, offset + 8)
        val offsetFlags = readShort(packet, offset + 12)
        val dataOffset = (offsetFlags ushr 12) * 4
        if (dataOffset < 20 || dataOffset > length) return null
        val flags = offsetFlags and 0x01FF
        val window = readShort(packet, offset + 14)
        return TcpSegment(
            srcPort = srcPort,
            dstPort = dstPort,
            seq = seq,
            ack = ack,
            dataOffset = dataOffset,
            flags = flags,
            window = window,
            payloadOffset = offset + dataOffset,
            payloadLength = length - dataOffset,
        )
    }

    fun buildTcp(
        srcIp: Int,
        dstIp: Int,
        srcPort: Int,
        dstPort: Int,
        seq: Int,
        ack: Int,
        flags: Int,
        window: Int,
        payload: ByteArray,
        payloadOffset: Int,
        payloadLength: Int,
        /** When > 0, an MSS option is emitted; only meaningful on SYN segments. */
        mss: Int = 0,
    ): ByteArray {
        val headerLength = if (mss > 0) 24 else 20
        val total = headerLength + payloadLength
        val segment = ByteArray(total)
        writeShort(segment, 0, srcPort)
        writeShort(segment, 2, dstPort)
        writeInt(segment, 4, seq)
        writeInt(segment, 8, ack)
        val offsetWords = headerLength / 4
        segment[12] = ((offsetWords shl 4) or ((flags ushr 8) and 0x0F)).toByte()
        segment[13] = (flags and 0xFF).toByte()
        writeShort(segment, 14, window)
        // segment[16..17] checksum placeholder, segment[18..19] urgent pointer = 0
        if (mss > 0) {
            segment[20] = 0x02 // MSS kind
            segment[21] = 0x04 // MSS length
            writeShort(segment, 22, mss)
        }
        System.arraycopy(payload, payloadOffset, segment, headerLength, payloadLength)
        val segmentChecksum = tcpChecksum(srcIp, dstIp, segment, 0, total)
        writeShort(segment, 16, segmentChecksum)
        return buildIpv4(srcIp, dstIp, PROTO_TCP, segment, 0, total)
    }

    /** Wrapping sequence-number comparison (RFC 793). */
    fun seqLess(a: Int, b: Int): Boolean = (a - b) < 0

    fun seqLessOrEqual(a: Int, b: Int): Boolean = (a - b) <= 0

    // ---------------------------------------------------------------- UDP

    data class UdpDatagram(
        val srcPort: Int,
        val dstPort: Int,
        val payloadOffset: Int, // absolute offset into the packet array
        val payloadLength: Int,
    )

    fun parseUdp(packet: ByteArray, offset: Int, length: Int): UdpDatagram? {
        if (length < 8) return null
        val srcPort = readShort(packet, offset)
        val dstPort = readShort(packet, offset + 2)
        val udpLength = readShort(packet, offset + 4)
        if (udpLength < 8 || udpLength > length) return null
        return UdpDatagram(
            srcPort = srcPort,
            dstPort = dstPort,
            payloadOffset = offset + 8,
            payloadLength = udpLength - 8,
        )
    }

    fun buildUdp(
        srcIp: Int,
        dstIp: Int,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
        payloadOffset: Int,
        payloadLength: Int,
    ): ByteArray {
        val total = 8 + payloadLength
        val segment = ByteArray(total)
        writeShort(segment, 0, srcPort)
        writeShort(segment, 2, dstPort)
        writeShort(segment, 4, total)
        // Checksum 0 means "not computed" — legal and universally accepted for IPv4 UDP.
        writeShort(segment, 6, 0)
        System.arraycopy(payload, payloadOffset, segment, 8, payloadLength)
        return buildIpv4(srcIp, dstIp, PROTO_UDP, segment, 0, total)
    }
}
