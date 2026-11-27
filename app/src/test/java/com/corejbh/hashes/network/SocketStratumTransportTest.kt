package com.corejbh.hashes.network

import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The socket transport against local sockets: what a connect that fails must do
 * with the socket it opened, and that a peer which accepts and then stays silent
 * cannot hold the connecting thread.
 *
 * Nothing here talks to a pool. The TLS case runs the real `SSLSocketFactory`
 * against a plain server that never answers the ClientHello, which is the shape
 * of a pool that accepts the TCP connection and then stops talking.
 */
class SocketStratumTransportTest {

    /** Runs `connect()` on a daemon thread so a regression fails the test instead of hanging it. */
    private fun connectOnThread(transport: StratumTransport, joinMillis: Long): Throwable? {
        val failure = AtomicReference<Throwable?>(null)
        val thread = Thread {
            try {
                transport.connect()
            } catch (thrown: Throwable) {
                failure.set(thrown)
            }
        }
        thread.isDaemon = true
        thread.start()
        thread.join(joinMillis)
        assertFalse("connect() did not return within $joinMillis ms", thread.isAlive)
        return failure.get()
    }

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use {
        it.localPort
    }

    @Test
    fun `a tls peer that never answers cannot block the connect`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val accepted = AtomicReference<Socket?>(null)
            val acceptor = Thread {
                try {
                    accepted.set(server.accept())
                } catch (_: IOException) {
                    // The test closes the server before a late accept.
                }
            }
            acceptor.isDaemon = true
            acceptor.start()

            val timeoutMs = 500
            val transport = SocketStratumTransport(
                PoolEndpoint(secure = true, host = "127.0.0.1", port = server.localPort),
                connectTimeoutMs = timeoutMs,
            )

            val startedAt = System.nanoTime()
            val failure = connectOnThread(transport, joinMillis = timeoutMs + 4_000L)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L

            if (failure == null) {
                fail("connect() to a silent TLS peer returned without an error")
            }
            assertTrue(
                "expected a read timeout from the handshake, got $failure",
                failure is SocketTimeoutException,
            )
            // The read timeout is what ends the handshake, so it has to be the
            // connect timeout (plus scheduling slack), not an OS default.
            assertTrue("connect() took ${elapsedMs}ms, more than the timeout", elapsedMs < timeoutMs + 3_000L)

            // The socket that was opened must be closed, not left half-open.
            val held = accepted.get()
            assertTrue("the server never accepted the connection", held != null)
            held!!.use { socket ->
                socket.soTimeout = 3_000
                var read = 0
                while (read >= 0) {
                    read = socket.getInputStream().read()
                }
            }

            // And the transport must be unusable but harmless afterwards.
            assertNull(transport.readLine())
            try {
                transport.writeLine("x")
                fail("writeLine() worked on a transport whose connect failed")
            } catch (_: IOException) {
                // Expected: there is no writer.
            }
            transport.close()
        }
    }

    @Test
    fun `a refused connection reports the failure and leaves nothing open`() {
        val port = freePort()
        val transport = SocketStratumTransport(
            PoolEndpoint(secure = true, host = "127.0.0.1", port = port),
            connectTimeoutMs = 2_000,
        )

        val failure = connectOnThread(transport, joinMillis = 5_000L)

        assertTrue("expected a connect failure, got $failure", failure is ConnectException)
        assertNull(transport.readLine())
        transport.close()
    }

    @Test
    fun `a plain tcp connection reads the line the peer sends`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val server2 = Thread {
                try {
                    server.accept().use { socket ->
                        socket.getOutputStream().write("hello\n".toByteArray(Charsets.UTF_8))
                        socket.getOutputStream().flush()
                    }
                } catch (_: IOException) {
                    // The test closes the server first when it is done.
                }
            }
            server2.isDaemon = true
            server2.start()

            val transport = SocketStratumTransport(
                PoolEndpoint(secure = false, host = "127.0.0.1", port = server.localPort),
                connectTimeoutMs = 2_000,
            )
            transport.connect()
            try {
                assertEquals("hello", transport.readLine())
                assertNull(transport.readLine())
            } finally {
                transport.close()
            }
        }
    }
}
