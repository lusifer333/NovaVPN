package com.novavpn.engine.xray

import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Karing-style real-HTTP relay verification against a live SOCKS5 inbound.
 *
 * sing-box's `urltest` outbound decides "working?" with one REAL HTTP
 * request through the tunnel: connect to https://www.gstatic.com/generate_204
 * and require HTTP 204. A TCP/TLS handshake proves nothing about data
 * relay — a server can accept the handshake and then drop every packet
 * ("handshake-yes / data-no"). [httpRoundTrip] replicates the urltest
 * semantics end-to-end:
 *
 *   SOCKS5 CONNECT (domain, timed) → TLS (SNI + hostname verification) →
 *   GET /generate_204 → 204 response, timed end-to-end.
 *
 * Pure JVM (no android.*, no dagger) so the same code runs in JVM unit
 * tests and on device.
 */
object TrafficProbe {
    private val tag = "TrafficProbe"

    /** Karing default urltest target (Google's connectivity-check CDN). */
    const val TEST_HOST = "www.gstatic.com"
    const val TEST_PORT = 443
    const val TEST_PATH = "/generate_204"

    private const val SOCKS_VERSION = 0x05
    private const val SOCKS_CMD_CONNECT = 0x01
    private const val SOCKS_ATYP_IPV4 = 0x01
    private const val SOCKS_ATYP_DOMAIN = 0x03
    private const val SOCKS_ATYP_IPV6 = 0x04

    /**
     * Full real-delay round-trip through a SOCKS5 proxy, Karing-style:
     *
     *  SOCKS5 CONNECT (timed) → TLS handshake → HTTP GET → 204 reply.
     *
     * Returns the total elapsed milliseconds (CONNECT + TLS + request +
     * response) or null when any step fails or the status is not 204.
     * The CONNECT uses ATYP=DOMAIN so the relay server resolves the name
     * itself — exactly like a browser request, no local DNS involved.
     */
    fun httpRoundTrip(
        proxyHost: String,
        proxyPort: Int,
        host: String = TEST_HOST,
        port: Int = TEST_PORT,
        path: String = TEST_PATH,
        timeoutMs: Int = 8_000
    ): Long? {
        var socket: Socket? = null
        return try {
            val start = System.nanoTime()
            socket = Socket()
            socket.connect(InetSocketAddress(proxyHost, proxyPort), timeoutMs)
            socket.soTimeout = timeoutMs
            val out = socket.getOutputStream()
            val inp = socket.getInputStream()

            // Greeting: VER=5, NMETHODS=1, METHOD=0 (no auth).
            out.write(byteArrayOf(SOCKS_VERSION.toByte(), 0x01, 0x00)); out.flush()
            if (inp.read() != SOCKS_VERSION || inp.read() != 0x00) {
                Timber.tag(tag).w("http rt: socks greeting rejected")
                return null
            }

            // CONNECT host:port — ATYP=DOMAIN, so the tunnel relays the
            // name to the remote server for resolution.
            val hostBytes = host.toByteArray(Charsets.US_ASCII)
            out.write(
                byteArrayOf(
                    SOCKS_VERSION.toByte(), SOCKS_CMD_CONNECT.toByte(), 0x00,
                    SOCKS_ATYP_DOMAIN.toByte(), hostBytes.size.toByte()
                )
            )
            out.write(hostBytes)
            out.write(byteArrayOf((port ushr 8).toByte(), port.toByte()))
            out.flush()

            // Reply: VER REP RSV ATYP BND.ADDR BND.PORT — drain the fixed
            // header, then the address part (varies by ATYP).
            val ver = inp.read(); val rep = inp.read(); val rsv = inp.read(); val atyp = inp.read()
            if (ver != SOCKS_VERSION || rep != 0x00 || rsv != 0x00) {
                Timber.tag(tag).w("http rt: CONNECT rejected (rep=%d)", rep)
                return null
            }
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
                SOCKS_ATYP_IPV6 -> {
                    val bnd = ByteArray(18) // 16 addr + 2 port
                    var n = 0
                    while (n < 18) { val r = inp.read(bnd, n, 18 - n); if (r <= 0) return null; n += r }
                }
                else -> return null
            }

            // TLS over the CONNECTed stream: SNI + hostname verification,
            // exactly like any real HTTPS client (system trust store).
            val sslContext = SSLContext.getInstance("TLS").apply { init(null, null, null) }
            val ssl = sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
            socket = ssl // close the TLS socket (and underlying stream) on exit
            ssl.soTimeout = timeoutMs
            val params = ssl.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS"
            ssl.sslParameters = params
            ssl.startHandshake()

            // HTTP/1.1 GET on the encrypted stream.
            val request = buildString {
                append("GET ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(host).append("\r\n")
                append("User-Agent: NovaVPN/1.0 (urltest)\r\n")
                append("Accept: */*\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            val sslOut = ssl.getOutputStream()
            sslOut.write(request.toByteArray(Charsets.US_ASCII)); sslOut.flush()

            // Status line + headers; elapsed = full round-trip (CONNECT +
            // TLS + request + response). 204 is the required answer.
            val reader = ssl.getInputStream().bufferedReader(Charsets.US_ASCII)
            val statusLine = reader.readLine() ?: return null
            val ms = (System.nanoTime() - start) / 1_000_000
            var line = reader.readLine()
            while (line != null && line.isNotEmpty()) {
                line = reader.readLine()
            }
            val status = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
            if (status == 204) ms else null
        } catch (e: SocketTimeoutException) {
            Timber.tag(tag).w("http rt: timeout")
            null
        } catch (e: Exception) {
            Timber.tag(tag).w("http rt: threw (%s)", e.message)
            null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }
}
