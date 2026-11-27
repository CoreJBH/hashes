package com.corejbh.hashes.network

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * One Stratum connection: a line-oriented socket.
 *
 * TLS connections verify the pool's certificate and host name
 * (`endpointIdentificationAlgorithm = "HTTPS"`), so a certificate for another
 * host or an unsigned certificate ends the connection instead of sending the
 * payout address to it. TCP keepalive is on for both schemes.
 */
interface StratumTransport {
    val isSecure: Boolean

    /** Opens the socket and completes a TLS handshake when the endpoint is secure. */
    fun connect()

    /** One line, or null when the peer closed the connection. */
    fun readLine(): String?

    fun writeLine(line: String)

    /** 0 means wait indefinitely. Used to bound the handshake only. */
    fun setReadTimeout(millis: Int)

    fun close()
}

class SocketStratumTransport(
    private val endpoint: PoolEndpoint,
    private val connectTimeoutMs: Int = 15_000,
) : StratumTransport {

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    override val isSecure: Boolean
        get() = endpoint.secure

    override fun connect() {
        val raw = Socket()
        var opened: Socket? = null
        try {
            raw.tcpNoDelay = true
            raw.keepAlive = true
            raw.connect(InetSocketAddress(endpoint.host, endpoint.port), connectTimeoutMs)

            val connected: Socket = if (endpoint.secure) {
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val ssl = factory.createSocket(raw, endpoint.host, endpoint.port, true) as SSLSocket
                opened = ssl
                ssl.sslParameters =
                    ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                // The handshake reads before it writes, so the read timeout has to
                // be in place first: a host that accepts the TCP connection and
                // then never answers the ClientHello would otherwise block this
                // thread for ever instead of failing after connectTimeoutMs.
                ssl.soTimeout = connectTimeoutMs
                ssl.startHandshake()
                ssl
            } else {
                opened = raw
                raw
            }
            connected.soTimeout = connectTimeoutMs
            socket = connected
            reader = BufferedReader(InputStreamReader(connected.getInputStream(), Charsets.UTF_8))
            writer = BufferedWriter(OutputStreamWriter(connected.getOutputStream(), Charsets.UTF_8))
        } catch (failure: Throwable) {
            // A handshake that fails and does not close leaves a half-open socket
            // behind and keeps the transport unusable, so release what was opened
            // here and report the failure to the caller.
            socket = null
            reader = null
            writer = null
            try {
                opened?.close()
            } catch (_: IOException) {
                // Nothing left to release on that socket.
            }
            try {
                raw.close()
            } catch (_: IOException) {
                // Closing the underlying socket twice is harmless.
            }
            throw failure
        }
    }

    override fun readLine(): String? = reader?.readLine()

    override fun writeLine(line: String) {
        val out = writer ?: throw IOException("not connected")
        synchronized(this) {
            out.write(line)
            out.write("\n")
            out.flush()
        }
    }

    override fun setReadTimeout(millis: Int) {
        try {
            socket?.soTimeout = millis
        } catch (_: IOException) {
            // The socket is already gone; the read loop reports it.
        }
    }

    override fun close() {
        synchronized(this) {
            try {
                socket?.close()
            } catch (_: IOException) {
                // Closing an already broken socket is not an error.
            }
            socket = null
            reader = null
            writer = null
        }
    }
}
