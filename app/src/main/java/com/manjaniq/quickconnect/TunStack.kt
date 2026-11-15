package com.manjaniq.quickconnect

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * A minimal userspace IPv4 network stack that terminates the TCP connections
 * inside the Quick Connect TUN interface and relays them through a SOCKS5
 * proxy (the selected core's loopback listener, e.g. `127.0.0.1:1819`).
 *
 * Scope (deliberate):
 *  - TCP: full connection tracking, handshake, retransmission, half-close.
 *    This is what carries browsing, streaming and API traffic.
 *  - UDP: only DNS (destination port 53) is carried, relayed as DNS-over-TCP
 *    through the same SOCKS5 proxy, because the cores behind this tunnel are
 *    TCP SOCKS5 upstreams and neither offers UDP ASSOCIATE. All other UDP
 *    (QUIC, NTP, games) is dropped; applications fall back to TCP.
 *  - Fragmented IPv4 packets are dropped (no reassembly); the TUN path has a
 *    1500 MTU so DF traffic never needs it.
 *
 * Nothing here touches Android APIs; the owning VpnService is responsible
 * for the TUN file descriptor and for `protect()`.
 */
internal class TunStack(
    private val proxyHost: String,
    private val proxyPort: Int,
    /** Writes a complete IPv4 packet into the TUN interface. Must be thread-safe. */
    private val sendToTun: (ByteArray) -> Unit,
    private val protectSocket: (Socket) -> Boolean,
    private val log: (String) -> Unit,
) {

    data class ConnKey(val srcIp: Int, val srcPort: Int, val dstIp: Int, val dstPort: Int)

    @Volatile
    private var running = true

    private val conns = ConcurrentHashMap<ConnKey, TcpConnection>()
    private val dialExecutor = Executors.newCachedThreadPool { r ->
        thread(name = "manjaniq-tun-dial", isDaemon = true) { r.run() }
    }
    private val dnsExecutor = Executors.newCachedThreadPool { r ->
        thread(name = "manjaniq-tun-dns", isDaemon = true) { r.run() }
    }

    @Volatile
    var packetsIn: Long = 0
        private set

    @Volatile
    var packetsOut: Long = 0
        private set

    private val bytesRelayed = AtomicLong(0)

    private val isnSource = java.util.concurrent.atomic.AtomicInteger(0x3159_2A00)

    private val sweeper = thread(name = "manjaniq-tun-sweeper", isDaemon = true, start = false) {
        while (running) {
            try {
                sweep()
            } catch (t: Throwable) {
                log("sweeper error: ${t.javaClass.simpleName}: ${t.message}")
            }
            try {
                Thread.sleep(SWEEP_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return@thread
            }
        }
    }

    init {
        sweeper.start()
    }

    // ------------------------------------------------------------- ingress

    fun handlePacket(raw: ByteArray, length: Int = raw.size) {
        if (!running) return
        packetsIn++
        val ip = TunPackets.parseIpv4(raw, length) ?: return
        if (ip.moreFragments || ip.fragmentOffset != 0) return // no reassembly
        when (ip.protocol) {
            TunPackets.PROTO_TCP -> handleTcp(ip, raw)
            TunPackets.PROTO_UDP -> handleUdp(ip, raw)
        }
    }

    private fun handleTcp(ip: TunPackets.Ipv4Packet, raw: ByteArray) {
        val seg = TunPackets.parseTcp(raw, ip.payloadOffset, ip.payloadLength) ?: return
        val key = ConnKey(ip.srcIp, seg.srcPort, ip.dstIp, seg.dstPort)
        val existing = conns[key]
        if (existing == null) {
            val wantsOpen =
                seg.flags and TunPackets.TCP_SYN != 0 && seg.flags and TunPackets.TCP_ACK == 0
            if (wantsOpen) {
                val conn = TcpConnection(key, nextIsn(), seg.seq + 1)
                conns[key] = conn
                conn.onSynReceived()
                val dstIp = ip.dstIp
                val dstPort = seg.dstPort
                dialExecutor.execute { dialUpstream(conn, dstIp, dstPort) }
            } else if (seg.flags and TunPackets.TCP_RST == 0) {
                sendRst(
                    ip.dstIp, seg.dstPort, ip.srcIp, seg.srcPort,
                    if (seg.flags and TunPackets.TCP_ACK != 0) seg.ack else 0,
                    seg.seq + seg.payloadLength + if (seg.flags and TunPackets.TCP_FIN != 0) 1 else 0,
                )
            }
            return
        }
        existing.onSegmentReceived(raw, seg)
    }

    private fun handleUdp(ip: TunPackets.Ipv4Packet, raw: ByteArray) {
        val udp = TunPackets.parseUdp(raw, ip.payloadOffset, ip.payloadLength) ?: return
        if (udp.dstPort != 53) return // only DNS is carried; everything else is dropped
        val query = raw.copyOfRange(udp.payloadOffset, udp.payloadOffset + udp.payloadLength)
        val replyToIp = ip.srcIp
        val replyToPort = udp.srcPort
        val dnsServerIp = ip.dstIp
        dnsExecutor.execute {
            val response = dnsOverTcp(dnsServerIp, query)
            if (response != null && running) {
                val packet = TunPackets.buildUdp(
                    dnsServerIp, replyToIp, 53, replyToPort,
                    response, 0, response.size,
                )
                packetsOut++
                sendToTun(packet)
            }
        }
    }

    // ------------------------------------------------------- packet output

    private fun emit(packet: ByteArray) {
        packetsOut++
        sendToTun(packet)
    }

    private fun sendRst(
        srcIp: Int,
        srcPort: Int,
        dstIp: Int,
        dstPort: Int,
        seq: Int,
        ack: Int,
    ) {
        emit(
            TunPackets.buildTcp(
                srcIp, dstIp, srcPort, dstPort,
                seq = seq, ack = ack,
                flags = TunPackets.TCP_RST or TunPackets.TCP_ACK,
                window = 0,
                payload = EMPTY, payloadOffset = 0, payloadLength = 0,
            ),
        )
    }

    private fun sweep() {
        val now = System.currentTimeMillis()
        for (conn in conns.values) conn.onSweep(now)
    }

    /** Tears down every connection, the dial/DNS executors and the sweeper. */
    fun close() {
        running = false
        sweeper.interrupt()
        for (conn in conns.values) conn.abort()
        conns.clear()
        dialExecutor.shutdownNow()
        dnsExecutor.shutdownNow()
    }

    fun connectionCount(): Int = conns.size

    fun bytesRelayed(): Long = bytesRelayed.get()

    // -------------------------------------------------------------- socks5

    private fun socksConnect(dstIp: Int, dstPort: Int): Socket {
        val socket = Socket()
        socket.tcpNoDelay = true
        // Bound every blocking phase of the handshake; cleared for the data phase.
        socket.soTimeout = SOCKS_REPLY_TIMEOUT_MS
        socket.connect(InetSocketAddress(proxyHost, proxyPort), SOCKS_CONNECT_TIMEOUT_MS)
        // The proxy lives on the loopback interface, which the VPN never captures;
        // protect() is belt-and-braces for custom proxy hosts.
        try {
            protectSocket(socket)
        } catch (t: Throwable) {
            log("protect() failed, continuing on loopback: ${t.message}")
        }
        val output = socket.getOutputStream()
        val input = socket.getInputStream()

        output.write(byteArrayOf(0x05, 0x01, 0x00)) // ver=5, 1 method: no-auth
        output.flush()
        val greeting = readExact(input, 2)
            ?: throw IOException("SOCKS5 greeting refused or timed out")
        if (greeting[0] != 0x05.toByte() || greeting[1] != 0x00.toByte()) {
            throw IOException("SOCKS5 no-auth method not accepted")
        }

        val request = ByteArray(10)
        request[0] = 0x05 // ver
        request[1] = 0x01 // cmd = CONNECT
        request[2] = 0x00 // reserved
        request[3] = 0x01 // atyp = IPv4
        TunPackets.writeInt(request, 4, dstIp)
        TunPackets.writeShort(request, 8, dstPort)
        output.write(request)
        output.flush()

        socket.soTimeout = SOCKS_REPLY_TIMEOUT_MS
        val reply = readExact(input, 4) ?: throw IOException("SOCKS5 reply timed out")
        if (reply[0] != 0x05.toByte()) throw IOException("SOCKS5 bad reply version")
        val code = reply[1].toInt() and 0xFF
        if (code != 0x00) throw IOException("SOCKS5 connect refused (rep=$code)")
        val addressType = reply[3].toInt() and 0xFF
        val addressLength = when (addressType) {
            0x01 -> 4
            0x04 -> 16
            0x03 -> {
                val lengthByte = readExact(input, 1)
                    ?: throw IOException("SOCKS5 reply truncated at address length")
                lengthByte[0].toInt() and 0xFF
            }
            else -> throw IOException("SOCKS5 reply with unknown address type $addressType")
        }
        readExact(input, addressLength) ?: throw IOException("SOCKS5 reply truncated at address")
        readExact(input, 2) ?: throw IOException("SOCKS5 reply truncated at port")
        socket.soTimeout = 0 // back to blocking mode for the data phase
        return socket
    }

    /** Blocking read of exactly [n] bytes, relying on the socket's soTimeout. */
    private fun readExact(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var offset = 0
        try {
            while (offset < n) {
                val read = input.read(buf, offset, n - offset)
                if (read < 0) return null
                offset += read
            }
        } catch (e: IOException) {
            return null
        }
        return buf
    }

    private fun dnsOverTcp(dnsServerIp: Int, query: ByteArray): ByteArray? {
        var socket: Socket? = null
        return try {
            socket = socksConnect(dnsServerIp, 53)
            socket.soTimeout = DNS_TIMEOUT_MS
            val output = socket.getOutputStream()
            val input = socket.getInputStream()
            output.write((query.size ushr 8) and 0xFF)
            output.write(query.size and 0xFF)
            output.write(query)
            output.flush()
            val lengthBytes = readExact(input, 2) ?: return null
            val responseLength = TunPackets.readShort(lengthBytes, 0)
            if (responseLength <= 0 || responseLength > 64 * 1024) return null
            readExact(input, responseLength)
        } catch (t: Throwable) {
            null
        } finally {
            try {
                socket?.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun dialUpstream(conn: TcpConnection, dstIp: Int, dstPort: Int) {
        val socket = try {
            socksConnect(dstIp, dstPort)
        } catch (t: Throwable) {
            log("SOCKS5 dial ${TunPackets.ipString(dstIp)}:$dstPort failed: ${t.message}")
            conn.onUpstreamDialFailed()
            return
        }
        conn.onUpstreamConnected(socket)
    }

    private fun nextIsn(): Int =
        (isnSource.getAndAdd(0x0000_0FA1) and 0x7FFF_FFFF) or 0x1000_0000

    // --------------------------------------------------------- connection

    private enum class TcpState { SYN_RECEIVED, ESTABLISHED, CLOSED }

    private class SentSegment(
        val seq: Int,
        val payload: ByteArray,
        val isFin: Boolean,
        var sentAt: Long,
        var retransmits: Int = 0,
    ) {
        val end: Int get() = seq + payload.size + if (isFin) 1 else 0
    }

    private inner class TcpConnection(
        private val key: ConnKey,
        private val isn: Int,
        private var rcvNxt: Int,
    ) {
        private val lock = Any()

        @Volatile
        var state = TcpState.SYN_RECEIVED
            private set

        @Volatile
        private var lastActivity = System.currentTimeMillis()

        private var synAckSentAt = 0L

        private var sndUna = isn + 1
        private var sndNxt = isn + 1
        private var peerWindow = INITIAL_PEER_WINDOW

        private val unacked = ArrayDeque<SentSegment>()

        /** Upstream → TUN bytes queued for transmission (drained window-wise). */
        private val pendingOut = ByteArrayOutputStream()
        private var outConsumed = 0

        /** TUN → upstream bytes buffered until the SOCKS5 dial completes. */
        private val pendingIn = ByteArrayOutputStream()

        private var socksReady = false

        /** TUN side sent FIN (the app closed its write direction). */
        private var appHalfClosed = false

        /** Upstream sent EOF; we owe the app a FIN once the queue drains. */
        private var upstreamEof = false

        @Volatile
        private var ourFinSent = false

        @Volatile
        private var closed = false
        private var upstream: Socket? = null

        // -------------------------------------------------- TUN → TCP side

        fun onSynReceived() {
            synchronized(lock) {
                sendSynAckLocked()
            }
        }

        fun onSegmentReceived(raw: ByteArray, seg: TunPackets.TcpSegment) {
            synchronized(lock) {
                lastActivity = System.currentTimeMillis()
                if (closed) return

                if (seg.flags and TunPackets.TCP_RST != 0) {
                    abortLocked()
                    return
                }

                var wantsAck = false

                // Cumulative ACK bookkeeping.
                if (seg.flags and TunPackets.TCP_ACK != 0) {
                    val ack = seg.ack
                    if (TunPackets.seqLessOrEqual(sndUna, ack) &&
                        TunPackets.seqLessOrEqual(ack, sndNxt)
                    ) {
                        while (unacked.isNotEmpty()) {
                            val head = unacked.first()
                            if (TunPackets.seqLessOrEqual(head.end, ack)) {
                                unacked.removeFirst()
                            } else {
                                break
                            }
                        }
                        sndUna = ack
                    }
                    // Any ACK carries a usable window hint; last one wins.
                    peerWindow = seg.window
                    if (state == TcpState.SYN_RECEIVED) state = TcpState.ESTABLISHED
                }

                // In-order payload from the app; deliver toward SOCKS.
                if (seg.payloadLength > 0) {
                    if (seg.seq == rcvNxt) {
                        writeUpstreamLocked(raw, seg.payloadOffset, seg.payloadLength)
                        rcvNxt += seg.payloadLength
                        wantsAck = true
                    } else if (TunPackets.seqLessOrEqual(seg.seq, rcvNxt - 1) &&
                        TunPackets.seqLess(rcvNxt, seg.seq + seg.payloadLength)
                    ) {
                        // Overlapping retransmission: trim the already-acked prefix.
                        val skip = rcvNxt - seg.seq
                        writeUpstreamLocked(raw, seg.payloadOffset + skip, seg.payloadLength - skip)
                        rcvNxt = seg.seq + seg.payloadLength
                        wantsAck = true
                    } else if (TunPackets.seqLess(rcvNxt, seg.seq)) {
                        // Out of order: drop and repeat the current ACK.
                        wantsAck = true
                    }
                }

                // FIN from the app: consume the sequence number, half-close upstream.
                if (seg.flags and TunPackets.TCP_FIN != 0) {
                    val finSeq = seg.seq + seg.payloadLength
                    when {
                        finSeq == rcvNxt -> {
                            rcvNxt += 1
                            appHalfClosed = true
                            halfCloseUpstreamLocked()
                            wantsAck = true
                        }
                        TunPackets.seqLess(finSeq, rcvNxt) -> {
                            // Retransmitted FIN we already consumed: re-ACK it.
                            wantsAck = true
                        }
                    }
                }

                drainLocked()

                if (wantsAck) sendAckLocked()

                // Our FIN fully acknowledged and nothing queued: the conn is done.
                if (ourFinSent && unacked.isEmpty() && pendingOut.size() == outConsumed) {
                    closeLocked()
                }
            }
        }

        fun onUpstreamDialFailed() {
            synchronized(lock) {
                if (closed) return
                closed = true
                conns.remove(key)
                state = TcpState.CLOSED
                sendRstLocked()
                closeUpstreamLocked()
            }
        }

        fun onUpstreamConnected(socket: Socket) {
            synchronized(lock) {
                if (closed) {
                    try {
                        socket.close()
                    } catch (_: IOException) {
                    }
                    return
                }
                upstream = socket
                socksReady = true
                val buffered = pendingIn.toByteArray()
                pendingIn.reset()
                if (buffered.isNotEmpty()) {
                    try {
                        val output = socket.getOutputStream()
                        output.write(buffered)
                        output.flush()
                        bytesRelayed.addAndGet(buffered.size.toLong())
                    } catch (t: Throwable) {
                        log("upstream write failed for $key: ${t.message}")
                        abortLocked()
                        return
                    }
                }
                drainLocked()
            }
            // The reader blocks until upstream EOF; keep it off the dial executor.
            thread(name = "manjaniq-tun-read-${key.srcPort.and(0xFFFF)}", isDaemon = true) {
                pumpUpstream(socket)
            }
        }

        private fun pumpUpstream(socket: Socket) {
            val input = try {
                socket.getInputStream()
            } catch (t: Throwable) {
                onUpstreamEof()
                return
            }
            val buffer = ByteArray(16 * 1024)
            try {
                while (running && !closed) {
                    // Back-pressure: stop reading while the peer window is the
                    // bottleneck; the kernel buffers fill and TCP flow control
                    // propagates to the origin server automatically.
                    while (pendingOutSize() > MAX_PENDING_OUT && running && !closed) {
                        Thread.sleep(20)
                    }
                    val n = input.read(buffer)
                    if (n < 0) break
                    bytesRelayed.addAndGet(n.toLong())
                    synchronized(lock) {
                        if (closed) return
                        pendingOut.write(buffer, 0, n)
                        drainLocked()
                    }
                }
            } catch (_: IOException) {
                // Upstream vanished; treated as EOF below.
            } catch (t: Throwable) {
                log("upstream read error for $key: ${t.message}")
            }
            onUpstreamEof()
        }

        private fun pendingOutSize(): Int = synchronized(lock) { pendingOut.size() - outConsumed }

        private fun onUpstreamEof() {
            synchronized(lock) {
                if (closed) return
                upstreamEof = true
                // Bytes may still be queued behind an exhausted peer window;
                // drainLocked() emits our FIN once they are all handed over.
                drainLocked()
                if (unacked.isEmpty() && pendingOut.size() == outConsumed) closeLocked()
            }
        }

        // ----------------------------------------------------- housekeeping

        fun onSweep(now: Long) {
            synchronized(lock) {
                if (closed) {
                    conns.remove(key)
                    return
                }
                // Handshake never completed: retransmit SYN-ACK, then give up.
                if (state == TcpState.SYN_RECEIVED) {
                    if (now - synAckSentAt > SYN_ACK_TIMEOUT_MS) {
                        abortLocked()
                        return
                    }
                    if (now - synAckSentAt >= RETRANSMIT_MS) {
                        sendSynAckLocked()
                    }
                    return
                }
                // Retransmit anything unacknowledged for too long (go-back-N).
                val stale = unacked.firstOrNull()
                if (stale != null && now - stale.sentAt >= RETRANSMIT_MS) {
                    var aborted = false
                    for (segment in unacked) {
                        if (now - segment.sentAt < RETRANSMIT_MS) continue
                        segment.retransmits += 1
                        if (segment.retransmits > MAX_RETRANSMITS) {
                            aborted = true
                            break
                        }
                        emitSegmentLocked(segment)
                    }
                    if (aborted) {
                        abortLocked()
                        return
                    }
                }
                if (now - lastActivity > IDLE_TIMEOUT_MS) abortLocked()
            }
        }

        // -------------------------------------------------------- internals

        private fun writeUpstreamLocked(raw: ByteArray, offset: Int, length: Int) {
            if (appHalfClosed || closed) return // the app closed its write direction
            val socket = upstream
            if (socksReady && socket != null) {
                try {
                    val output = socket.getOutputStream()
                    output.write(raw, offset, length)
                    output.flush()
                    bytesRelayed.addAndGet(length.toLong())
                } catch (t: Throwable) {
                    log("upstream write failed for $key: ${t.message}")
                    abortLocked()
                }
            } else {
                if (pendingIn.size() + length > MAX_PENDING_IN) {
                    log("pending-upstream buffer exceeded for $key; dropping connection")
                    abortLocked()
                    return
                }
                pendingIn.write(raw, offset, length)
            }
        }

        private fun halfCloseUpstreamLocked() {
            val socket = upstream ?: return
            try {
                socket.shutdownOutput()
            } catch (t: Throwable) {
                log("upstream shutdownOutput failed for $key: ${t.message}")
                abortLocked()
            }
        }

        /** Moves bytes from pendingOut into window-bounded TCP segments. Caller holds the lock. */
        private fun drainLocked() {
            if (closed) return
            // Snapshot the queue once per drain pass; the lock is held throughout.
            val snapshot = if (pendingOut.size() > outConsumed) pendingOut.toByteArray() else null
            if (snapshot != null) {
                while (true) {
                    val remaining = snapshot.size - outConsumed
                    if (remaining <= 0) break
                    val inFlight = sndNxt - sndUna
                    val room = peerWindow - inFlight
                    if (room <= 0) return // window exhausted; resume when ACKs arrive
                    val chunk = minOf(MSS, remaining, room)
                    val payload = snapshot.copyOfRange(outConsumed, outConsumed + chunk)
                    val segment = SentSegment(
                        seq = sndNxt,
                        payload = payload,
                        isFin = false,
                        sentAt = System.currentTimeMillis(),
                    )
                    unacked.addLast(segment)
                    sndNxt += chunk
                    outConsumed += chunk
                    emitSegmentLocked(segment)
                }
            }
            if (outConsumed >= pendingOut.size()) {
                outConsumed = 0
                pendingOut.reset()
                // Upstream already hit EOF and the wire is clear: send our FIN.
                if (upstreamEof && !ourFinSent) sendFinLocked()
            }
        }

        private fun sendSynAckLocked() {
            synAckSentAt = System.currentTimeMillis()
            emit(
                TunPackets.buildTcp(
                    key.dstIp, key.srcIp, key.dstPort, key.srcPort,
                    seq = isn, ack = rcvNxt,
                    flags = TunPackets.TCP_SYN or TunPackets.TCP_ACK,
                    window = ADVERTISED_WINDOW,
                    payload = EMPTY, payloadOffset = 0, payloadLength = 0,
                    mss = MSS,
                ),
            )
        }

        private fun emitSegmentLocked(segment: SentSegment) {
            segment.sentAt = System.currentTimeMillis()
            val flags = TunPackets.TCP_ACK or
                (if (segment.payload.isNotEmpty()) TunPackets.TCP_PSH else 0) or
                (if (segment.isFin) TunPackets.TCP_FIN else 0)
            emit(
                TunPackets.buildTcp(
                    key.dstIp, key.srcIp, key.dstPort, key.srcPort,
                    seq = segment.seq, ack = rcvNxt,
                    flags = flags,
                    window = ADVERTISED_WINDOW,
                    payload = segment.payload, payloadOffset = 0, payloadLength = segment.payload.size,
                ),
            )
        }

        private fun sendAckLocked() {
            emit(
                TunPackets.buildTcp(
                    key.dstIp, key.srcIp, key.dstPort, key.srcPort,
                    seq = sndNxt, ack = rcvNxt,
                    flags = TunPackets.TCP_ACK,
                    window = ADVERTISED_WINDOW,
                    payload = EMPTY, payloadOffset = 0, payloadLength = 0,
                ),
            )
        }

        private fun sendFinLocked() {
            ourFinSent = true
            val segment = SentSegment(sndNxt, EMPTY, isFin = true, sentAt = System.currentTimeMillis())
            sndNxt += 1
            unacked.addLast(segment)
            emit(
                TunPackets.buildTcp(
                    key.dstIp, key.srcIp, key.dstPort, key.srcPort,
                    seq = segment.seq, ack = rcvNxt,
                    flags = TunPackets.TCP_ACK or TunPackets.TCP_FIN,
                    window = ADVERTISED_WINDOW,
                    payload = EMPTY, payloadOffset = 0, payloadLength = 0,
                ),
            )
        }

        private fun sendRstLocked() {
            emit(
                TunPackets.buildTcp(
                    key.dstIp, key.srcIp, key.dstPort, key.srcPort,
                    seq = sndNxt, ack = rcvNxt,
                    flags = TunPackets.TCP_RST or TunPackets.TCP_ACK,
                    window = 0,
                    payload = EMPTY, payloadOffset = 0, payloadLength = 0,
                ),
            )
        }

        private fun abortLocked() {
            if (closed) return
            closed = true
            state = TcpState.CLOSED
            conns.remove(key)
            closeUpstreamLocked()
        }

        /** Abrupt teardown initiated by the owner (service stop): no packets sent. */
        fun abort() {
            synchronized(lock) {
                closed = true
                state = TcpState.CLOSED
                conns.remove(key)
                closeUpstreamLocked()
            }
        }

        private fun closeLocked() {
            if (closed) return
            closed = true
            state = TcpState.CLOSED
            conns.remove(key)
            closeUpstreamLocked()
        }

        private fun closeUpstreamLocked() {
            val socket = upstream
            upstream = null
            socksReady = false
            try {
                socket?.close()
            } catch (_: IOException) {
            }
        }
    }

    private companion object {
        const val MSS = 1400
        const val ADVERTISED_WINDOW = 65535
        const val INITIAL_PEER_WINDOW = 14600
        const val MAX_PENDING_IN = 1024 * 1024
        const val MAX_PENDING_OUT = 1024 * 1024
        const val SWEEP_INTERVAL_MS = 500L
        const val RETRANSMIT_MS = 1000L
        const val MAX_RETRANSMITS = 30
        const val SYN_ACK_TIMEOUT_MS = 10_000L
        const val IDLE_TIMEOUT_MS = 120_000L
        const val SOCKS_CONNECT_TIMEOUT_MS = 8_000
        const val SOCKS_REPLY_TIMEOUT_MS = 8_000
        const val DNS_TIMEOUT_MS = 6_000
        val EMPTY = ByteArray(0)
    }
}
