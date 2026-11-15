package com.manjaniq.quickconnect

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * On-device verification of the Quick Connect tunnel primitives.
 *
 * The Android VPN consent dialog cannot be granted programmatically, so this
 * test does not drive VpnService; instead it pushes real packets through the
 * same userspace TCP/SOCKS5 stack the service feeds ([TunStack]) and talks to
 * a genuine loopback SOCKS5 server, proving on the ART runtime:
 * SYN in -> SYN-ACK out, handshake, data in -> SOCKS CONNECT -> echo back out.
 *
 * No network access is used or required.
 */
@RunWith(AndroidJUnit4::class)
class QuickConnectTunnelTest {

    private val appIp = TunPackets.parseIpv4Literal("10.111.222.2")!!
    private val serverIp = TunPackets.parseIpv4Literal("93.184.216.34")!!

    private data class Parsed(val ip: TunPackets.Ipv4Packet, val tcp: TunPackets.TcpSegment?)

    private fun parse(raw: ByteArray): Parsed? {
        val ip = TunPackets.parseIpv4(raw, raw.size) ?: return null
        if (ip.protocol != TunPackets.PROTO_TCP) return null
        return Parsed(ip, TunPackets.parseTcp(raw, ip.payloadOffset, ip.payloadLength))
    }

    @Test
    fun tunnelRelaysBidirectionalTrafficOnDevice() {
        val socks = LoopbackSocks()
        try {
            val emitted = Collections.synchronizedList(mutableListOf<ByteArray>())
            val stack = TunStack(
                proxyHost = "127.0.0.1",
                proxyPort = socks.port,
                sendToTun = { packet -> emitted.add(packet) },
                protectSocket = { true },
                log = {},
            )
            val srcPort = 52000
            val isn = 0x12340000

            fun appPacket(seq: Int, ack: Int, flags: Int, payload: ByteArray = ByteArray(0)): ByteArray =
                TunPackets.buildTcp(
                    srcIp = appIp, dstIp = serverIp, srcPort = srcPort, dstPort = 80,
                    seq = seq, ack = ack, flags = flags, window = 65535,
                    payload = payload, payloadOffset = 0, payloadLength = payload.size,
                )

            // 1. App -> stack: SYN
            val syn = appPacket(seq = isn, ack = 0, flags = TunPackets.TCP_SYN)
            stack.handlePacket(syn, syn.size)

            // 2. Stack -> app: SYN-ACK addressed back to the app
            val synAck = synchronized(emitted) {
                emitted.firstNotNullOfOrNull { raw ->
                    parse(raw)?.takeIf { p -> p.tcp != null && (p.tcp.flags and TunPackets.TCP_SYN) != 0 }
                }
            }
            assertNotNull("stack must answer SYN with a SYN-ACK", synAck)
            val synAckTcp = synAck!!.tcp!!
            assertEquals(serverIp, synAck.ip.srcIp)
            assertEquals(appIp, synAck.ip.dstIp)
            assertEquals(isn + 1, synAckTcp.ack)
            val serverIsn = synAckTcp.seq

            // 3. App -> stack: ACK completes the handshake
            val ackPacket = appPacket(seq = isn + 1, ack = serverIsn + 1, flags = TunPackets.TCP_ACK)
            stack.handlePacket(ackPacket, ackPacket.size)

            // 4. App -> stack: payload data
            emitted.clear()
            val payload = "on-device-payload".toByteArray()
            val data = appPacket(seq = isn + 1, ack = serverIsn + 1,
                flags = TunPackets.TCP_ACK or TunPackets.TCP_PSH, payload = payload)
            stack.handlePacket(data, data.size)

            // 5. The SOCKS server must observe the CONNECT and the exact payload
            assertTrue("SOCKS server saw no CONNECT", socks.awaitConnects(1, 10_000))
            assertTrue("SOCKS server received no payload", socks.awaitBytes(payload.size, 10_000))
            assertArrayEquals(payload, socks.receivedBytes())

            // 6. Echoed bytes must come back out of the tunnel toward the app
            await(10_000) {
                synchronized(emitted) {
                    emitted.any { raw ->
                        val p = parse(raw)
                        p != null && p.tcp != null && p.tcp.payloadLength == payload.size
                    }
                }
            }
            val echoRaw = synchronized(emitted) {
                emitted.first { raw ->
                    val p = parse(raw)
                    p != null && p.tcp != null && p.tcp.payloadLength == payload.size
                }
            }
            val echo = parse(echoRaw)!!
            val echoTcp = echo.tcp!!
            assertEquals(serverIp, echo.ip.srcIp)
            assertEquals(appIp, echo.ip.dstIp)
            assertArrayEquals(
                payload,
                echoRaw.copyOfRange(echoTcp.payloadOffset, echoTcp.payloadOffset + echoTcp.payloadLength),
            )
            assertEquals(isn + 1 + payload.size, echoTcp.ack)

            stack.close()
            assertEquals(0, stack.connectionCount())
        } finally {
            socks.close()
        }
    }

    private fun await(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("condition not met within ${timeoutMs}ms", condition())
    }
}

/** Minimal genuine SOCKS5 server: no-auth; after CONNECT it echoes received bytes back. */
private class LoopbackSocks : AutoCloseable {

    private val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
    private val running = AtomicBoolean(true)
    private val connectCount = AtomicInteger(0)
    private val readBuffer = ByteArray(16 * 1024)
    private val bufferLock = Any()
    private var buffered = ByteArray(0)
    private val accepted = Collections.synchronizedList(mutableListOf<Socket>())

    val port: Int get() = server.localPort

    init {
        Thread({ acceptLoop() }, "loopback-socks").apply { isDaemon = true }.start()
    }

    fun awaitConnects(count: Int, timeoutMs: Long): Boolean = awaitCount(count, timeoutMs)

    fun awaitBytes(count: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            synchronized(bufferLock) {
                if (buffered.size >= count) return true
            }
            Thread.sleep(10)
        }
        return synchronized(bufferLock) { buffered.size >= count }
    }

    fun receivedBytes(): ByteArray = synchronized(bufferLock) { buffered.copyOf() }

    private fun awaitCount(count: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (connectCount.get() >= count) return true
            Thread.sleep(10)
        }
        return connectCount.get() >= count
    }

    private fun acceptLoop() {
        while (running.get() && !server.isClosed) {
            val socket = try {
                server.accept()
            } catch (e: java.io.IOException) {
                return
            }
            accepted.add(socket)
            Thread({ serve(socket) }, "loopback-socks-conn").apply { isDaemon = true }.start()
        }
    }

    private fun serve(socket: Socket) {
        try {
            val input: InputStream = socket.getInputStream()
            val output: OutputStream = socket.getOutputStream()
            // greeting: 05 01 00 -> 05 00
            val greeting = readExact(input, 3) ?: return
            if (greeting[0] != 5.toByte()) return
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()
            // CONNECT request (10 bytes)
            val request = readExact(input, 10) ?: return
            connectCount.incrementAndGet()
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            // echo mode: relay bytes both ways until closed
            while (running.get()) {
                val n = try {
                    input.read(readBuffer)
                } catch (e: java.io.IOException) {
                    return
                }
                if (n < 0) return
                synchronized(bufferLock) {
                    buffered += readBuffer.copyOf(n)
                }
                output.write(readBuffer, 0, n)
                output.flush()
            }
        } finally {
            try {
                socket.close()
            } catch (_: java.io.IOException) {
            }
        }
    }

    private fun readExact(input: InputStream, n: Int): ByteArray? {
        val out = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val read = try {
                input.read(out, offset, n - offset)
            } catch (e: java.io.IOException) {
                return null
            }
            if (read < 0) return null
            offset += read
        }
        return out
    }

    override fun close() {
        running.set(false)
        try {
            server.close()
        } catch (_: java.io.IOException) {
        }
        accepted.forEach {
            try {
                it.close()
            } catch (_: java.io.IOException) {
            }
        }
    }
}
