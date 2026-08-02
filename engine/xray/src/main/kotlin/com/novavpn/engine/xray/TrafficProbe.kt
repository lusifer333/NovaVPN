package com.novavpn.engine.xray

import timber.log.Timber
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Real-traffic relay verification against a live engine SOCKS5 inbound.
 *
 * A plain TLS handshake proves nothing about DATA relay — a server (or
 * Cloudflare worker behind it) can accept the handshake and then drop
 * every packet ("handshake-yes / data-no"). [TrafficProbe] closes that gap
 * with real round-trips through the REAL engine (same binary, same
 * config generation):
 *
 *  1. [connectRoundTrip] — full SOCKS5 CONNECT handshake to the DNS
 *     target + a DNS-over-TCP round-trip (RFC 7766 framing) over the
 *     established stream, timed end-to-end. Proves the member relays TCP
 *     data and yields the real delay.
 *  2. [udpDnsRoundtrip] — SOCKS5 UDP ASSOCIATE (RFC 1928 §7) + a DNS
 *     datagram to the target. Proves the member relays UDP data.
 *
 * Pure JVM (no android.*, no dagger) so the same code runs in JVM unit
 * tests and the host E2E harness against the real xray binary.
 */
object TrafficProbe {
    private val tag = "TrafficProbe"

    /** Default DNS target for the round-trips (Google DNS, universally routed). */
    const val TARGET_HOST = "8.8.8.8"
    const val TARGET_PORT = 53

    private const val SOCKS_VERSION = 0x05
    private const val SOCKS_CMD_CONNECT = 0x01
    private const val SOCKS_CMD_UDP_ASSOCIATE = 0x03
    private const val SOCKS_ATYP_IPV4 = 0x01
    private const val SOCKS_ATYP_DOMAIN = 0x03

    /**
     * RFC 1035 DNS query: header (RD set, QDCOUNT=1) + one A/IN question.
     */
    fun dnsQueryBytes(id: Int, name: String = "example.com"): ByteArray {
        val question = name
            .trimEnd('.')
            .split('.')
            .joinToString("") { it.length.toChar() + it } + "\u0000"
        val q = question.toByteArray(Charsets.US_ASCII)
        return ByteArray(12 + q.size + 4).also { out ->
            out[0] = (id ushr 8).toByte()
            out[1] = id.toByte()
            out[2] = 0x01 // flags: RD
            out[3] = 0x00
            out[5] = 0x01 // QDCOUNT = 1
            System.arraycopy(q, 0, out, 12, q.size)
            val qtype = q.size + 12
            out[qtype] = 0x00 // QTYPE = A
            out[qtype + 1] = 0x01
            out[qtype + 2] = 0x00 // QCLASS = IN
            out[qtype + 3] = 0x01
        }
    }

    /**
     * True when [payload] looks like the DNS reply to our query: matching
     * transaction id, QR=1, and at least one answer record.
     */
    fun validateDnsReply(payload: ByteArray, id: Int): Boolean {
        if (payload.size < 12) return false
        val replyId = ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
        if (replyId != id) return false
        val qr = (payload[2].toInt() and 0x80) != 0
        val ancount = ((payload[6].toInt() and 0xff) shl 8) or (payload[7].toInt() and 0xff)
        return qr && ancount > 0
    }

    /**
     * SOCKS5 CONNECT (RFC 1928): greeting → CONNECT to [target]:[port] →
     * full reply drained (VER REP RSV ATYP + BND.ADDR + BND.PORT, robust
     * to IPv4/domain BND.ADDR). The socket is left open and CONNECTed.
     *
     * Returns null on any failure; otherwise the connected socket paired
     * with the elapsed milliseconds of the CONNECT handshake (the relay
     * dial latency through the tunnel).
     */
    fun socks5Connect(
        proxyHost: String,
        proxyPort: Int,
        target: InetAddress,
        targetPort: Int,
        timeoutMs: Int = 4_000
    ): Pair<Socket, Long>? {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(proxyHost, proxyPort), timeoutMs)
            socket.soTimeout = timeoutMs
            val out = socket.getOutputStream()
            val inp = socket.getInputStream()
            val start = System.nanoTime()

            // Greeting: VER=5, NMETHODS=1, METHOD=0 (no auth).
            out.write(byteArrayOf(SOCKS_VERSION.toByte(), 0x01, 0x00)); out.flush()
            if (inp.read() != SOCKS_VERSION || inp.read() != 0x00) return null

            // CONNECT request: VER=5 CMD=1 RSV=0 ATYP=IPv4 DST.ADDR DST.PORT.
            val ip = target.address
            out.write(
                byteArrayOf(
                    SOCKS_VERSION.toByte(), SOCKS_CMD_CONNECT.toByte(), 0x00,
                    SOCKS_ATYP_IPV4.toByte(),
                    ip[0], ip[1], ip[2], ip[3],
                    (targetPort ushr 8).toByte(), targetPort.toByte()
                )
            ); out.flush()

            // Reply: VER REP RSV ATYP BND.ADDR BND.PORT — drain EXACTLY the
            // fixed header first, then the address part (varies by ATYP).
            val ver = inp.read(); val rep = inp.read(); val rsv = inp.read(); val atyp = inp.read()
            if (ver != SOCKS_VERSION || rep != 0x00 || rsv != 0x00) return null
            when (atyp) {
                SOCKS_ATYP_IPV4 -> {
                    val bnd = ByteArray(6) // 4 addr + 2 port
                    var n = 0
                    while (n < 6) { val r = inp.read(bnd, n, 6 - n); if (r <= 0) return null; n += r }
                }
                SOCKS_ATYP_DOMAIN -> {
                    val len = inp.read(); if (len <= 0 || len > 255) return null
                    val bnd = ByteArray(len + 2)
                    var n = 0
                    while (n < len + 2) { val r = inp.read(bnd, n, len + 2 - n); if (r <= 0) return null; n += r }
                }
                else -> return null
            }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            socket to elapsedMs
        } catch (e: SocketTimeoutException) {
            Timber.tag(tag).w("socks5 connect: timeout")
            null
        } catch (e: Exception) {
            Timber.tag(tag).w("socks5 connect: threw (%s)", e.message)
            null
        } finally {
            if (socket == null) { try { socket?.close() } catch (_: Exception) {} }
        }
    }

    /**
     * Full real-delay round-trip through a SOCKS5 proxy:
     *
     *  SOCKS5 CONNECT (timed) → DNS-over-TCP query → validated reply.
     *
     * Returns the total elapsed milliseconds (CONNECT handshake + DNS
     * round-trip) or null when any step fails. This is the [com.novavpn.domain.probe.RealDelayProber]
     * measurement: it proves both the relay dial AND actual data relay.
     */
    fun connectRoundTrip(
        proxyHost: String,
        proxyPort: Int,
        target: InetAddress = InetAddress.getByName(TARGET_HOST),
        targetPort: Int = TARGET_PORT,
        timeoutMs: Int = 4_000
    ): Long? {
        val start = System.nanoTime()
        val connected = socks5Connect(proxyHost, proxyPort, target, targetPort, timeoutMs) ?: return null
        val (socket, connectMs) = connected
        try {
            val ok = tcpDnsRoundtrip(socket, target, targetPort, timeoutMs)
            return if (ok) (System.nanoTime() - start) / 1_000_000 else null
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /**
     * DNS-over-TCP round-trip over an established SOCKS5 stream (the socket
     * must already be CONNECTed to [target]:[port]). Returns true when a
     * well-formed DNS reply with ≥1 answer arrives.
     */
    fun tcpDnsRoundtrip(
        socket: Socket,
        target: InetAddress,
        port: Int = TARGET_PORT,
        timeoutMs: Int = 2_500
    ): Boolean {
        return try {
            val oldTimeout = socket.soTimeout
            socket.soTimeout = timeoutMs
            val id = (System.currentTimeMillis() and 0xffff).toInt()
            val query = dnsQueryBytes(id)
            val out = socket.getOutputStream()
            val inp = socket.getInputStream()
            // RFC 7766: 2-byte big-endian length prefix.
            out.write(byteArrayOf((query.size ushr 8).toByte(), query.size.toByte()))
            out.write(query); out.flush()

            val lenHi = inp.read()
            val lenLo = inp.read()
            if (lenHi < 0 || lenLo < 0) {
                Timber.tag(tag).w("tcp dns: stream closed before reply")
                socket.soTimeout = oldTimeout
                return false
            }
            val replyLen = (lenHi shl 8) or lenLo
            if (replyLen <= 0 || replyLen > 4096) {
                Timber.tag(tag).w("tcp dns: implausible reply length %d", replyLen)
                socket.soTimeout = oldTimeout
                return false
            }
            val reply = ByteArray(replyLen)
            var n = 0
            while (n < replyLen) {
                val r = inp.read(reply, n, replyLen - n)
                if (r <= 0) {
                    socket.soTimeout = oldTimeout
                    return false
                }
                n += r
            }
            socket.soTimeout = oldTimeout
            val ok = validateDnsReply(reply, id)
            if (!ok) Timber.tag(tag).w("tcp dns: invalid reply (%d bytes)", reply.size)
            ok
        } catch (e: SocketTimeoutException) {
            Timber.tag(tag).w("tcp dns: timeout")
            false
        } catch (e: Exception) {
            Timber.tag(tag).w("tcp dns: threw (%s)", e.message)
            false
        }
    }

    /**
     * UDP DNS round-trip through SOCKS5 UDP ASSOCIATE (RFC 1928 §7):
     * greeting → ASSOCIATE to 0.0.0.0:0 → relay address from the reply →
     * send a SOCKS5 UDP datagram to [target]:[port] with a DNS query →
     * await the reply datagram. Returns true when a valid DNS reply with
     * ≥1 answer arrives. The control socket is closed on exit.
     */
    fun udpDnsRoundtrip(
        controlHost: String = "127.0.0.1",
        controlPort: Int,
        target: InetAddress,
        port: Int = TARGET_PORT,
        timeoutMs: Int = 2_500
    ): Boolean {
        var control: Socket? = null
        var udp: java.net.DatagramSocket? = null
        return try {
            control = Socket()
            control.connect(InetSocketAddress(controlHost, controlPort), 2_000)
            control.soTimeout = timeoutMs
            val out = control.getOutputStream()
            val inp = control.getInputStream()

            // Greeting.
            out.write(byteArrayOf(SOCKS_VERSION.toByte(), 0x01, 0x00)); out.flush()
            if (inp.read() != SOCKS_VERSION || inp.read() != 0x00) return false

            // UDP ASSOCIATE to 0.0.0.0:0 (wildcard).
            out.write(byteArrayOf(SOCKS_VERSION.toByte(), SOCKS_CMD_UDP_ASSOCIATE.toByte(), 0x00, SOCKS_ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0))
            out.flush()
            if (inp.read() != SOCKS_VERSION || inp.read() != 0x00) {
                Timber.tag(tag).w("udp dns: ASSOCIATE rejected")
                return false
            }
            // Reply: VER REP RSV ATYP BND.ADDR(4) BND.PORT(2). RSV must be
            // consumed BEFORE ATYP — reading it as ATYP rejects every reply.
            if (inp.read() != 0x00) {
                Timber.tag(tag).w("udp dns: ASSOCIATE RSV != 0")
                return false
            }
            val atyp = inp.read()
            if (atyp != SOCKS_ATYP_IPV4) {
                Timber.tag(tag).w("udp dns: ASSOCIATE relay ATYP=%d", atyp)
                return false
            }
            val addr = ByteArray(4)
            var n = 0
            while (n < 4) { val r = inp.read(addr, n, 4 - n); if (r <= 0) return false; n += r }
            val portHi = inp.read(); val portLo = inp.read()
            if (portHi < 0 || portLo < 0) return false
            val relayHost = InetAddress.getByAddress(addr).hostAddress
            val relayPort = (portHi shl 8) or portLo
            Timber.tag(tag).d("udp dns: ASSOCIATE relay=%s:%d", relayHost, relayPort)

            udp = java.net.DatagramSocket()
            udp.soTimeout = timeoutMs
            val id = (System.currentTimeMillis() and 0xffff).toInt()
            val query = dnsQueryBytes(id)
            // SOCKS5 UDP datagram: RSV(2) FRAG(1) ATYP(1) DST.ADDR(4) DST.PORT(2) payload.
            // FRAG MUST stay 0x00 — xray discards any FRAG != 0 ("discarding
            // fragmented payload"), so ATYP goes at index 3, NOT 2.
            val envelope = ByteArray(10 + query.size)
            envelope[3] = SOCKS_ATYP_IPV4.toByte()
            val ip = target.address
            System.arraycopy(ip, 0, envelope, 4, 4)
            envelope[8] = (port ushr 8).toByte()
            envelope[9] = port.toByte()
            System.arraycopy(query, 0, envelope, 10, query.size)
            udp.send(java.net.DatagramPacket(envelope, envelope.size, InetAddress.getByName(relayHost), relayPort))

            val buf = ByteArray(2048)
            val packet = java.net.DatagramPacket(buf, buf.size)
            udp.receive(packet)
            val reply = packet.data.copyOfRange(0, packet.length)
            if (reply.size < 10) return false
            val dns = reply.copyOfRange(10, reply.size)
            val ok = validateDnsReply(dns, id)
            if (!ok) Timber.tag(tag).w("udp dns: invalid reply (%d bytes)", dns.size)
            ok
        } catch (e: SocketTimeoutException) {
            Timber.tag(tag).w("udp dns: timeout")
            false
        } catch (e: Exception) {
            Timber.tag(tag).w("udp dns: threw (%s)", e.message)
            false
        } finally {
            try { udp?.close() } catch (_: Exception) {}
            try { control?.close() } catch (_: Exception) {}
        }
    }
}
