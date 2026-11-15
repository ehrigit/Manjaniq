package com.manjaniq.quickconnect

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end verification of the Quick Connect tunnel on the JVM.
 *
 * Topology under test:
 *
 *   fake Android app (built packets) → TunStack → REAL mock SOCKS5 server
 *                                          ↑           ↓
 *                                     canned upstream / echo
 *
 * A [MockSocks5Server] speaks genuine SOCKS5 (greeting, CONNECT parse,
 * reply bytes) so the tunnel's SOCKS client is exercised against the real
 * protocol, not a stubbed interface. Everything the stack writes to the TUN
 * is captured in [captured] for assertion.
 *
 * These tests open real loopback sockets; they do not touch the network.
 */
class TunStackIntegrationTest {

    private val appIp = TunPackets.parseIpv4Literal("10.111.222.2")!!
    private val serverIp = TunPackets.parseIpv4Literal("93.184.216.34")!!
    private val dnsIp = TunPackets.parseIpv4Literal("1.1.1.1")!!

    private val captured = Collections.synchronizedList(mutableListOf<ByteArray>())
    private val logs = Collections.synchronizedList(mutableListOf<String>())
    private val stacks = mutableListOf<TunStack>()

    @After
    fun tearDown() {
        stacks.forEach { it.close() }
        stacks.clear()
        captured.clear()
        logs.clear()
    }

    // ------------------------------------------------------------ fixtures

    private fun newStack(socksPort: Int): TunStack =
        TunStack(
            proxyHost = "127.0.0.1",
            proxyPort = socksPort,
            sendToTun = { packet -> captured.add(packet) },
            protectSocket = { true },
            log = { message -> logs.add(message) },
        ).also { stacks.add(it) }

    /** Crafts a packet as if it came from an Android app going out the TUN. */
    private fun appTcpPacket(
        srcPort: Int,
        dstPort: Int,
        seq: Int,
        ack: Int,
        flags: Int,
        payload: ByteArray = ByteArray(0),
    ): ByteArray = TunPackets.buildTcp(
        srcIp = appIp,
        dstIp = serverIp,
        srcPort = srcPort,
        dstPort = dstPort,
        seq = seq,
        ack = ack,
        flags = flags,
        window = 65535,
        payload = payload,
        payloadOffset = 0,
        payloadLength = payload.size,
    )

    private fun appDnsPacket(srcPort: Int, payload: ByteArray, dnsServer: Int = dnsIp): ByteArray =
        TunPackets.buildUdp(
            srcIp = appIp,
            dstIp = dnsServer,
            srcPort = srcPort,
            dstPort = 53,
            payload = payload,
            payloadOffset = 0,
            payloadLength = payload.size,
        )

    private fun await(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        if (!condition()) {
            throw AssertionError(
                "condition not met within ${timeoutMs}ms; logs=$logs; captured=${captured.map { describe(it) }}",
            )
        }
    }

    private fun capturedPackets(): List<Triple<TunPackets.Ipv4Packet, TunPackets.TcpSegment?, TunPackets.UdpDatagram?>> =
        synchronized(captured) {
            captured.mapNotNull { raw ->
                val ip = TunPackets.parseIpv4(raw, raw.size) ?: return@mapNotNull null
                when (ip.protocol) {
                    TunPackets.PROTO_TCP -> Triple(ip, TunPackets.parseTcp(raw, ip.payloadOffset, ip.payloadLength), null)
                    TunPackets.PROTO_UDP -> Triple(ip, null, TunPackets.parseUdp(raw, ip.payloadOffset, ip.payloadLength))
                    else -> Triple(ip, null, null)
                }
            }
        }

    private fun describe(raw: ByteArray): String {
        val ip = TunPackets.parseIpv4(raw, raw.size) ?: return "unparsable(${raw.size}B)"
        val proto = when (ip.protocol) {
            TunPackets.PROTO_TCP -> "TCP"
            TunPackets.PROTO_UDP -> "UDP"
            else -> "proto=${ip.protocol}"
        }
        return "$proto ${TunPackets.ipString(ip.srcIp)}->${TunPackets.ipString(ip.dstIp)} len=${raw.size}"
    }

    // ------------------------------------------------------------ tests

    @Test
    fun `full bidirectional TCP session passes through the mock SOCKS5 proxy`() {
        val socks = MockSocks5Server()
        try {
            val stack = newStack(socks.port)
            val srcPort = 51000
            val isn = 0x0BADCAFE.toInt()

            // 1. App → server SYN
            val syn = appTcpPacket(srcPort, 80, seq = isn, ack = 0, flags = TunPackets.TCP_SYN)
            stack.handlePacket(syn, syn.size)

            // 2. Stack → app SYN-ACK
            val synAck = capturedPackets().firstOrNull { (_, tcp, _) ->
                tcp != null && tcp.flags and TunPackets.TCP_SYN != 0 && tcp.flags and TunPackets.TCP_ACK != 0
            }
            assertNotNull("stack must answer the SYN with a SYN-ACK", synAck)
            val serverIsn = synAck!!.second!!.seq
            assertEquals(serverIp, synAck.first.srcIp)
            assertEquals(appIp, synAck.first.dstIp)
            assertEquals(80, synAck.second!!.srcPort)
            assertEquals(srcPort, synAck.second!!.dstPort)
            assertEquals(isn + 1, synAck.second!!.ack)
            // SYN-ACK must be a checksummed packet: verify both checksums.
            assertEquals(0, TunPackets.checksum(captured[0], 0, 20))
            assertEquals(
                0,
                TunPackets.tcpChecksum(serverIp, appIp, captured[0], synAck.first.payloadOffset, synAck.first.payloadLength),
            )

            // 3. App → server ACK (completes the handshake)
            captured.clear()
            val ack = appTcpPacket(srcPort, 80, seq = isn + 1, ack = serverIsn + 1, flags = TunPackets.TCP_ACK)
            stack.handlePacket(ack, ack.size)

            // 4. App → server data. The mock SOCKS is told to echo it back.
            captured.clear()
            val payload = "GET / HTTP/1.0\r\n\r\n".toByteArray()
            val data = appTcpPacket(
                srcPort, 80, seq = isn + 1, ack = serverIsn + 1,
                flags = TunPackets.TCP_ACK or TunPackets.TCP_PSH, payload = payload,
            )
            stack.handlePacket(data, data.size)

            // 5. The mock SOCKS server must receive the CONNECT for 93.184.216.34:80
            //    and the exact request bytes behind it.
            assertTrue("SOCKS server saw no connection", socks.awaitConnects(1, 5_000))
            assertEquals(serverIp, socks.connectDstIp(0))
            assertEquals(80, socks.connectDstPort(0))
            assertTrue("SOCKS server did not receive the app payload", socks.awaitBytes(0, payload.size, 5_000))
            assertArrayEquals(payload, socks.received(0))

            // 6a. The mock echoes the request back (upstream → app direction).
            await(5_000) {
                synchronized(captured) {
                    captured.any { r ->
                        val ip = TunPackets.parseIpv4(r, r.size) ?: return@any false
                        if (ip.protocol != TunPackets.PROTO_TCP) return@any false
                        val tcp = TunPackets.parseTcp(r, ip.payloadOffset, ip.payloadLength) ?: return@any false
                        r.copyOfRange(tcp.payloadOffset, tcp.payloadOffset + tcp.payloadLength).contentEquals(payload)
                    }
                }
            }
            val echoRaw = synchronized(captured) {
                captured.first { r ->
                    val ip = TunPackets.parseIpv4(r, r.size) ?: return@first false
                    if (ip.protocol != TunPackets.PROTO_TCP) return@first false
                    val tcp = TunPackets.parseTcp(r, ip.payloadOffset, ip.payloadLength) ?: return@first false
                    r.copyOfRange(tcp.payloadOffset, tcp.payloadOffset + tcp.payloadLength).contentEquals(payload)
                }
            }
            run {
                val ip = TunPackets.parseIpv4(echoRaw, echoRaw.size)!!
                val tcp = TunPackets.parseTcp(echoRaw, ip.payloadOffset, ip.payloadLength)!!
                assertEquals(serverIp, ip.srcIp)
                assertEquals(appIp, ip.dstIp)
                assertEquals(isn + 1 + payload.size, tcp.ack)
                assertEquals(0, TunPackets.checksum(echoRaw, 0, 20))
                assertEquals(0, TunPackets.tcpChecksum(serverIp, appIp, echoRaw, ip.payloadOffset, ip.payloadLength))
            }

            // 6b. Server-initiated data also comes out of the TUN.
            val reply = "HTTP/1.0 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray()
            socks.write(0, reply)
            await(5_000) {
                synchronized(captured) {
                    captured.any { r ->
                        val ip = TunPackets.parseIpv4(r, r.size) ?: return@any false
                        if (ip.protocol != TunPackets.PROTO_TCP) return@any false
                        val tcp = TunPackets.parseTcp(r, ip.payloadOffset, ip.payloadLength) ?: return@any false
                        r.copyOfRange(tcp.payloadOffset, tcp.payloadOffset + tcp.payloadLength).contentEquals(reply)
                    }
                }
            }
        } finally {
            socks.close()
        }
    }

    @Test
    fun `dns query is relayed as dns over tcp and the response returns to the app`() {
        val dnsQuery = dnsQueryBytes(id = 0xBEEF, name = "example.com")
        val dnsResponse = dnsResponseFor(dnsQuery, id = 0xBEEF)
        val socks = MockSocks5Server(dnsResponse = dnsResponse)
        try {
            val stack = newStack(socks.port)
            val srcPort = 51002
            val query = appDnsPacket(srcPort, dnsQuery)
            stack.handlePacket(query, query.size)

            await(5_000) { capturedPackets().any { (_, _, udp) -> udp != null && udp.dstPort == srcPort } }

            val raw = synchronized(captured) { captured.first { r ->
                val ip = TunPackets.parseIpv4(r, r.size)!!
                ip.protocol == TunPackets.PROTO_UDP
            } }
            val ip = TunPackets.parseIpv4(raw, raw.size)!!
            val udp = TunPackets.parseUdp(raw, ip.payloadOffset, ip.payloadLength)!!
            assertEquals(dnsIp, ip.srcIp)
            assertEquals(appIp, ip.dstIp)
            assertEquals(53, udp.srcPort)
            val payload = raw.copyOfRange(udp.payloadOffset, udp.payloadOffset + udp.payloadLength)
            assertArrayEquals(dnsResponse, payload)
            // The SOCKS side must have seen a CONNECT to 1.1.1.1:53.
            assertTrue(socks.awaitConnects(1, 5_000))
            assertEquals(dnsIp, socks.connectDstIp(0))
            assertEquals(53, socks.connectDstPort(0))
        } finally {
            socks.close()
        }
    }

    @Test
    fun `refused socks connect tears the app connection down with an rst`() {
        val socks = MockSocks5Server(refuse = true)
        try {
            val stack = newStack(socks.port)
            val srcPort = 51003
            val isn = 12345
            val syn = appTcpPacket(srcPort, 80, seq = isn, ack = 0, flags = TunPackets.TCP_SYN)
            stack.handlePacket(syn, syn.size)
            captured.clear()
            val data = appTcpPacket(
                srcPort, 80, seq = isn + 1, ack = 1,
                flags = TunPackets.TCP_ACK or TunPackets.TCP_PSH, payload = "ping".toByteArray(),
            )
            stack.handlePacket(data, data.size)

            await(5_000) {
                capturedPackets().any { (_, tcp, _) ->
                    tcp != null && tcp.flags and TunPackets.TCP_RST != 0
                }
            }
        } finally {
            socks.close()
        }
    }

    @Test
    fun `two concurrent connections stay independent`() {
        val socks = MockSocks5Server()
        try {
            val stack = newStack(socks.port)
            val portA = 51200
            val portB = 51201
            listOf(portA to 1111, portB to 2222).forEach { (port, isn) ->
                val syn = appTcpPacket(port, 443, seq = isn, ack = 0, flags = TunPackets.TCP_SYN)
                stack.handlePacket(syn, syn.size)
            }
            val synAcks = capturedPackets().filter { (_, tcp, _) ->
                tcp != null && tcp.flags and TunPackets.TCP_SYN != 0
            }
            assertEquals(2, synAcks.size)
            assertEquals(setOf(portA, portB), synAcks.map { it.second!!.dstPort }.toSet())
            // Complete both handshakes and push different payloads.
            val ackA = synAcks.first { it.second!!.dstPort == portA }.second!!
            val ackB = synAcks.first { it.second!!.dstPort == portB }.second!!
            val ackAPacket = appTcpPacket(portA, 443, seq = 1111 + 1, ack = ackA.seq + 1, flags = TunPackets.TCP_ACK)
            stack.handlePacket(ackAPacket, ackAPacket.size)
            val ackBPacket = appTcpPacket(portB, 443, seq = 2222 + 1, ack = ackB.seq + 1, flags = TunPackets.TCP_ACK)
            stack.handlePacket(ackBPacket, ackBPacket.size)
            val dataA = appTcpPacket(
                portA, 443, seq = 1112, ack = ackA.seq + 1,
                flags = TunPackets.TCP_ACK or TunPackets.TCP_PSH, payload = "alpha".toByteArray(),
            )
            val dataB = appTcpPacket(
                portB, 443, seq = 2223, ack = ackB.seq + 1,
                flags = TunPackets.TCP_ACK or TunPackets.TCP_PSH, payload = "bravo".toByteArray(),
            )
            stack.handlePacket(dataA, dataA.size)
            stack.handlePacket(dataB, dataB.size)

            assertTrue(socks.awaitConnects(2, 5_000))
            await(5_000) { socks.received(0).contentEquals("alpha".toByteArray()) || socks.received(1).contentEquals("alpha".toByteArray()) }
            val all = socks.received(0) + socks.received(1)
            assertTrue(all.toList().windowed(5).any { it == "alpha".toByteArray().toList() })
            assertTrue(all.toList().windowed(5).any { it == "bravo".toByteArray().toList() })
            assertEquals(2, stack.connectionCount())
        } finally {
            socks.close()
        }
    }

    @Test
    fun `close tears down every connection`() {
        val socks = MockSocks5Server()
        try {
            val stack = newStack(socks.port)
            val syn = appTcpPacket(51300, 80, seq = 999, ack = 0, flags = TunPackets.TCP_SYN)
            stack.handlePacket(syn, syn.size)
            assertTrue(stack.connectionCount() >= 1)
            stack.close()
            assertEquals(0, stack.connectionCount())
        } finally {
            socks.close()
        }
    }

    // ------------------------------------------------------- dns fixtures

    private fun dnsQueryBytes(id: Int, name: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((id ushr 8) and 0xFF); out.write(id and 0xFF)
        out.write(0x01); out.write(0x00) // RD=1
        out.write(0x00); out.write(0x01) // QDCOUNT=1
        out.write(0x00); out.write(0x00) // ANCOUNT
        out.write(0x00); out.write(0x00) // NSCOUNT
        out.write(0x00); out.write(0x00) // ARCOUNT
        for (label in name.split('.')) {
            out.write(label.length)
            out.write(label.toByteArray())
        }
        out.write(0x00)
        out.write(0x00); out.write(0x01) // QTYPE=A
        out.write(0x00); out.write(0x01) // QCLASS=IN
        return out.toByteArray()
    }

    private fun dnsResponseFor(query: ByteArray, id: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((id ushr 8) and 0xFF); out.write(id and 0xFF)
        out.write(0x81); out.write(0x80) // RD, RA, no error
        out.write(0x00); out.write(0x01) // QDCOUNT=1
        out.write(0x00); out.write(0x01) // ANCOUNT=1
        out.write(0x00); out.write(0x00); out.write(0x00); out.write(0x00)
        out.write(query, 12, query.size - 12) // echo the question section
        out.write(0xc0); out.write(0x0c) // name pointer to the question
        out.write(0x00); out.write(0x01) // TYPE=A
        out.write(0x00); out.write(0x01) // CLASS=IN
        out.write(0x00); out.write(0x00); out.write(0x01); out.write(0x2c) // TTL=300
        out.write(0x00); out.write(0x04) // RDLENGTH=4
        out.write(93); out.write(184); out.write(216); out.write(34)
        return out.toByteArray()
    }

    /** Reads exactly [n] bytes (used by the mock). */
    private fun readExact(input: InputStream, n: Int): ByteArray? {
        val buffer = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val read = try {
                input.read(buffer, offset, n - offset)
            } catch (e: java.io.IOException) {
                return null
            }
            if (read < 0) return null
            offset += read
        }
        return buffer
    }
}

/**
 * A genuine SOCKS5 server with no authentication, with deterministic behavior:
 *  - CONNECT to port 53 → answers the TCP-framed DNS query with [dnsResponse]
 *    (or a canned failure when [refuse]);
 *  - CONNECT to any other port → echoes every received byte back, unless
 *    [refuse] is set, in which case it rejects with "not allowed" (rep=0x02);
 *  - remembers what it received per connection index (0-based accept order).
 */
internal class MockSocks5Server(
    private val refuse: Boolean = false,
    dnsResponse: ByteArray? = null,
) : AutoCloseable {

    private val dnsResponse = dnsResponse
    private val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
    private val running = AtomicBoolean(true)
    private val acceptThread = Thread({ acceptLoop() }, "mock-socks-accept").apply {
        isDaemon = true
        start()
    }

    private val connections = Collections.synchronizedList(mutableListOf<MockConnection>())
    private val connectCount = AtomicInteger(0)

    val port: Int get() = server.localPort

    fun awaitConnects(count: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (connectCount.get() >= count) return true
            Thread.sleep(10)
        }
        return connectCount.get() >= count
    }

    fun connectDstIp(index: Int): Int = connections[index].dstIp

    fun connectDstPort(index: Int): Int = connections[index].dstPort

    fun received(index: Int): ByteArray = connections[index].receivedBytes()

    fun awaitBytes(index: Int, count: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (connections.size > index && connections[index].receivedBytes().size >= count) return true
            Thread.sleep(10)
        }
        return connections.size > index && connections[index].receivedBytes().size >= count
    }

    fun write(index: Int, bytes: ByteArray) = connections[index].write(bytes)

    private fun acceptLoop() {
        while (running.get() && !server.isClosed) {
            val socket = try {
                server.accept()
            } catch (e: java.io.IOException) {
                return
            }
            val connection = MockConnection(socket, dnsResponse, refuse)
            connections.add(connection)
            Thread({ connection.run() }, "mock-socks-conn").apply { isDaemon = true }.start()
        }
    }

    override fun close() {
        running.set(false)
        try {
            server.close()
        } catch (_: java.io.IOException) {
        }
        connections.forEach { it.close() }
    }

    internal inner class MockConnection(
        private val socket: Socket,
        private val dnsResponse: ByteArray?,
        private val refuse: Boolean,
    ) {
        @Volatile
        var dstIp: Int = 0
            private set

        @Volatile
        var dstPort: Int = 0
            private set

        private val buffer = ByteArrayOutputStream()
        private val bufferLock = Any()
        private val connectionDone = CountDownLatch(1)

        fun receivedBytes(): ByteArray = synchronized(bufferLock) { buffer.toByteArray() }

        fun write(bytes: ByteArray) {
            try {
                socket.getOutputStream().write(bytes)
                socket.getOutputStream().flush()
            } catch (_: java.io.IOException) {
            }
        }

        fun run() {
            try {
                val input = socket.getInputStream()
                val output = socket.getOutputStream()

                // --- greeting
                val greeting = readExact(input, 3) ?: return
                if (greeting[0] != 0x05.toByte()) return
                output.write(byteArrayOf(0x05, 0x00))
                output.flush()

                // --- CONNECT request
                val request = readExact(input, 10) ?: return
                dstIp = ((request[4].toInt() and 0xFF) shl 24) or
                    ((request[5].toInt() and 0xFF) shl 16) or
                    ((request[6].toInt() and 0xFF) shl 8) or
                    (request[7].toInt() and 0xFF)
                dstPort = ((request[8].toInt() and 0xFF) shl 8) or (request[9].toInt() and 0xFF)
                connectCount.incrementAndGet()

                if (refuse) {
                    // rep=0x02: connection not allowed by ruleset
                    output.write(byteArrayOf(0x05, 0x02, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    output.flush()
                    return
                }
                output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()

                if (dstPort == 53 && dnsResponse != null) {
                    // DNS over TCP: read the 2-byte length prefix then the query,
                    // answer with the canned response.
                    val lengthBytes = readExact(input, 2) ?: return
                    val length = ((lengthBytes[0].toInt() and 0xFF) shl 8) or (lengthBytes[1].toInt() and 0xFF)
                    readExact(input, length) ?: return
                    output.write((dnsResponse.size ushr 8) and 0xFF)
                    output.write(dnsResponse.size and 0xFF)
                    output.write(dnsResponse)
                    output.flush()
                    return
                }

                // Echo mode: relay every byte back until close.
                val chunk = ByteArray(16 * 1024)
                while (running.get()) {
                    val n = try {
                        input.read(chunk)
                    } catch (_: java.io.IOException) {
                        return
                    }
                    if (n < 0) return
                    synchronized(bufferLock) { buffer.write(chunk, 0, n) }
                    try {
                        output.write(chunk, 0, n)
                        output.flush()
                    } catch (_: java.io.IOException) {
                        return
                    }
                    synchronized(bufferLock) {
                        if (buffer.size() > 0) connectionDone.countDown()
                    }
                }
            } finally {
                try {
                    socket.close()
                } catch (_: java.io.IOException) {
                }
            }
        }

        fun close() {
            try {
                socket.close()
            } catch (_: java.io.IOException) {
            }
        }
    }
}
