package com.manjaniq.quickconnect

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-side verification of the tunnel's packet math. The property that
 * matters most: a re-checksummed segment (IP header or TCP) verifies to zero.
 */
class TunPacketsTest {

    private val srcIp = TunPackets.parseIpv4Literal("10.1.2.3")!!
    private val dstIp = TunPackets.parseIpv4Literal("93.184.216.34")!!

    @Test
    fun `parseIpv4Literal round trip`() {
        assertEquals(0x0A010203, srcIp)
        assertEquals("10.1.2.3", TunPackets.ipString(srcIp))
        assertEquals("93.184.216.34", TunPackets.ipString(dstIp))
        assertNull(TunPackets.parseIpv4Literal("999.1.2.3"))
        assertNull(TunPackets.parseIpv4Literal("1.2.3"))
        assertNull(TunPackets.parseIpv4Literal("a.b.c.d"))
    }

    @Test
    fun `ipv4 header checksum verifies to zero`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val packet = TunPackets.buildIpv4(srcIp, dstIp, TunPackets.PROTO_TCP, payload, 0, payload.size)
        val parsed = TunPackets.parseIpv4(packet, packet.size)
        assertNotNull(parsed)
        assertEquals(TunPackets.PROTO_TCP, parsed!!.protocol)
        // Verifying a header that includes its checksum yields zero.
        assertEquals(0, TunPackets.checksum(packet, 0, 20))
        assertEquals(20, parsed.payloadOffset)
        assertEquals(payload.size, parsed.payloadLength)
        assertArrayEquals(payload, packet.copyOfRange(20, packet.size))
    }

    @Test
    fun `ipv4 rejects garbage`() {
        assertNull(TunPackets.parseIpv4(ByteArray(0), 0))
        val truncated = ByteArray(10)
        assertNull(TunPackets.parseIpv4(truncated, truncated.size))
        val notIpv4 = ByteArray(20).apply { this[0] = 0x60 } // IPv6 version nibble
        assertNull(TunPackets.parseIpv4(notIpv4, notIpv4.size))
    }

    @Test
    fun `tcp segment round trip with checksum verification`() {
        val payload = "GET / HTTP/1.1\r\n\r\n".toByteArray()
        val packet = TunPackets.buildTcp(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = 51000,
            dstPort = 443,
            seq = 123_456,
            ack = 789,
            flags = TunPackets.TCP_ACK or TunPackets.TCP_PSH,
            window = 65535,
            payload = payload,
            payloadOffset = 0,
            payloadLength = payload.size,
        )
        val ip = TunPackets.parseIpv4(packet, packet.size)
        assertNotNull(ip)
        assertEquals(TunPackets.PROTO_TCP, ip!!.protocol)
        assertEquals(0, TunPackets.checksum(packet, 0, 20))

        val segment = TunPackets.parseTcp(packet, ip.payloadOffset, ip.payloadLength)
        assertNotNull(segment)
        assertEquals(51000, segment!!.srcPort)
        assertEquals(443, segment.dstPort)
        assertEquals(123_456, segment.seq)
        assertEquals(789, segment.ack)
        assertEquals(TunPackets.TCP_ACK or TunPackets.TCP_PSH, segment.flags)
        assertEquals(65535, segment.window)
        assertEquals(payload.size, segment.payloadLength)
        // Checksum property: pseudo-header + full segment verifies to zero.
        assertEquals(
            0,
            TunPackets.tcpChecksum(srcIp, dstIp, packet, ip.payloadOffset, ip.payloadLength),
        )
        assertArrayEquals(
            payload,
            packet.copyOfRange(segment.payloadOffset, segment.payloadOffset + segment.payloadLength),
        )
    }

    @Test
    fun `tcp syn carries mss option and parses`() {
        val packet = TunPackets.buildTcp(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = 51001,
            dstPort = 443,
            seq = 4242,
            ack = 99,
            flags = TunPackets.TCP_SYN or TunPackets.TCP_ACK,
            window = 65535,
            payload = ByteArray(0),
            payloadOffset = 0,
            payloadLength = 0,
            mss = 1400,
        )
        val ip = TunPackets.parseIpv4(packet, packet.size)!!
        val segment = TunPackets.parseTcp(packet, ip.payloadOffset, ip.payloadLength)!!
        assertEquals(24, segment.dataOffset)
        assertEquals(0, segment.payloadLength)
        assertEquals(
            0,
            TunPackets.tcpChecksum(srcIp, dstIp, packet, ip.payloadOffset, ip.payloadLength),
        )
        // MSS option bytes: kind=2, len=4, value=1400.
        assertEquals(0x02, packet[ip.payloadOffset + 20].toInt() and 0xFF)
        assertEquals(0x04, packet[ip.payloadOffset + 21].toInt() and 0xFF)
        assertEquals(1400, TunPackets.readShort(packet, ip.payloadOffset + 22))
    }

    @Test
    fun `udp datagram round trip`() {
        val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0x10, 0x20)
        val packet = TunPackets.buildUdp(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = 53000,
            dstPort = 53,
            payload = payload,
            payloadOffset = 0,
            payloadLength = payload.size,
        )
        val ip = TunPackets.parseIpv4(packet, packet.size)!!
        assertEquals(TunPackets.PROTO_UDP, ip.protocol)
        val datagram = TunPackets.parseUdp(packet, ip.payloadOffset, ip.payloadLength)!!
        assertEquals(53000, datagram.srcPort)
        assertEquals(53, datagram.dstPort)
        assertEquals(payload.size, datagram.payloadLength)
        assertArrayEquals(
            payload,
            packet.copyOfRange(datagram.payloadOffset, datagram.payloadOffset + datagram.payloadLength),
        )
    }

    @Test
    fun `sequence number comparison wraps`() {
        assertTrue(TunPackets.seqLess(Int.MAX_VALUE, Int.MIN_VALUE))
        assertFalse(TunPackets.seqLess(Int.MIN_VALUE, Int.MAX_VALUE))
        assertTrue(TunPackets.seqLessOrEqual(7, 7))
        assertTrue(TunPackets.seqLessOrEqual(6, 7))
        assertFalse(TunPackets.seqLessOrEqual(8, 7))
    }
}
