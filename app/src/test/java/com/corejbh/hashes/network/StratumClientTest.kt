package com.corejbh.hashes.network

import com.corejbh.hashes.TestVectors
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The Stratum client driven over a fake transport: the handshake, the login
 * order, job delivery, a submitted share and its reply. Nothing here touches a
 * socket, so the protocol sequence is checked without a pool.
 */
class StratumClientTest {

    private class FakeTransport : StratumTransport {
        val written = CopyOnWriteArrayList<String>()
        private val incoming = LinkedBlockingQueue<String>()
        @Volatile
        var closed = false

        override val isSecure: Boolean = true

        override fun connect() = Unit

        override fun readLine(): String? {
            val line = incoming.take()
            return if (line == EOF) null else line
        }

        override fun writeLine(line: String) {
            written += line
        }

        override fun setReadTimeout(millis: Int) = Unit

        override fun close() {
            synchronized(this) {
                if (!closed) {
                    closed = true
                    incoming.offer(EOF)
                }
            }
        }

        fun serverSends(line: String) {
            incoming.offer(line)
        }
    }

    /**
     * A transport whose socket stays open until it is closed, recording the thread
     * that closed it. It stands in for the TLS socket whose `close_notify` the
     * platform refuses to write on the main thread.
     */
    private class HangingTransport(
        private val closeFailure: Exception? = null,
    ) : StratumTransport {
        val written = CopyOnWriteArrayList<String>()
        val closed = CountDownLatch(1)
        val closeThreads = CopyOnWriteArrayList<String>()
        private val incoming = LinkedBlockingQueue<String>()

        @Volatile
        var connected = false

        override val isSecure: Boolean = true

        override fun connect() {
            connected = true
        }

        override fun readLine(): String? {
            val line = incoming.take()
            return if (line == EOF) null else line
        }

        override fun writeLine(line: String) {
            written += line
        }

        override fun setReadTimeout(millis: Int) = Unit

        override fun close() {
            closeThreads += Thread.currentThread().name
            incoming.offer(EOF)
            closed.countDown()
            closeFailure?.let { throw it }
        }
    }

    private companion object {
        const val EOF = "\u0000EOF"
    }

    private fun notify(): String =
        """
        {"id":null,"method":"mining.notify","params":[
          "bf",
          "${TestVectors.BLOCK_PREV_HASH_NOTIFY}",
          "${TestVectors.BLOCK_COINB1}",
          "${TestVectors.BLOCK_COINB2}",
          ["${TestVectors.BLOCK_BRANCH_0}","${TestVectors.BLOCK_BRANCH_1}"],
          "${TestVectors.BLOCK_VERSION}",
          "${TestVectors.BLOCK_NBITS}",
          "${TestVectors.BLOCK_NTIME}",
          true
        ]}
        """.trimIndent()

    private suspend fun awaitUntil(timeoutMs: Long = 4_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(20)
        }
        fail("condition not met within ${timeoutMs}ms")
    }

    @Test
    fun `handshake, job and share round trip`() = runBlocking {
        val transport = FakeTransport()
        val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val client = StratumClient(
            transportFactory = { transport },
            scope = clientScope,
            idleReconnectMs = 60_000,
        )
        val events = CopyOnWriteArrayList<WorkEvent>()
        val collectorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val collector = collectorScope.launch { client.events.collect { events += it } }

        try {
            client.connect(PoolEndpoint(true, "pool.test", 4333), "bc1qexample.hashes", "x")

            awaitUntil { transport.written.any { it.contains("mining.subscribe") } }
            val subscribe = JSONObject(transport.written.first { it.contains("mining.subscribe") })
            assertEquals("Hashes/0.1.0", subscribe.getJSONArray("params").getString(0))

            transport.serverSends(
                """{"id":1,"result":[[["mining.notify","sess-1"]],"aabbccdd",4],"error":null}"""
            )

            awaitUntil { transport.written.any { it.contains("mining.authorize") } }
            val authorize = JSONObject(transport.written.first { it.contains("mining.authorize") })
            assertEquals("bc1qexample.hashes", authorize.getJSONArray("params").getString(0))
            transport.serverSends("""{"id":2,"result":true,"error":null}""")

            awaitUntil { events.any { it is WorkEvent.SessionStarted } }
            val session = events.filterIsInstance<WorkEvent.SessionStarted>().first()
            assertEquals("aabbccdd", session.extranonce1)
            assertEquals(4, session.extranonce2Size)

            transport.serverSends(notify())
            awaitUntil { events.any { it is WorkEvent.Job } }
            val job = events.filterIsInstance<WorkEvent.Job>().first().job
            assertEquals("bf", job.jobId)
            assertTrue(job.cleanJobs)

            awaitUntil { client.isConnected }
            client.submit(
                ShareSubmission(
                    jobId = job.jobId,
                    extranonce2Hex = "00000001",
                    networkTime = job.networkTime,
                    nonce = TestVectors.BLOCK_NONCE,
                )
            )
            awaitUntil { transport.written.any { it.contains("mining.submit") } }
            val submit = JSONObject(transport.written.first { it.contains("mining.submit") })
            assertEquals("0f2b5710", submit.getJSONArray("params").getString(4))

            transport.serverSends("""{"id":100,"result":true,"error":null}""")
            awaitUntil { events.any { it is WorkEvent.ShareAccepted } }
        } finally {
            collector.cancel()
            collectorScope.cancel()
            client.disconnect()
            clientScope.cancel()
        }
    }

    @Test
    fun `a share submitted without a connection is rejected instead of queued`() = runBlocking {
        val client = StratumClient(transportFactory = { FakeTransport() })
        val collectorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        // Subscribe before the share is submitted: the event flow does not replay,
        // which is exactly how the view model collects it.
        val rejection = collectorScope.async {
            client.events.filterIsInstance<WorkEvent.ShareRejected>().first()
        }
        try {
            delay(200)
            client.submit(ShareSubmission("bf", "00000001", "4d1b2237", 1))
            assertEquals("not connected", rejection.await().reason)
        } finally {
            collectorScope.cancel()
            client.disconnect()
        }
    }

    @Test
    fun `a refused authorization is reported and the socket closed`() = runBlocking {
        val transport = FakeTransport()
        val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val client = StratumClient(
            transportFactory = { transport },
            scope = clientScope,
            idleReconnectMs = 60_000,
        )
        val events = CopyOnWriteArrayList<WorkEvent>()
        val collectorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val collector = collectorScope.launch { client.events.collect { events += it } }
        try {
            client.connect(PoolEndpoint(false, "pool.test", 3333), "bc1qexample.hashes", "x")
            awaitUntil { transport.written.any { it.contains("mining.subscribe") } }
            transport.serverSends(
                """{"id":1,"result":[[["mining.notify","s"]],"aabbccdd",4],"error":null}"""
            )
            awaitUntil { transport.written.any { it.contains("mining.authorize") } }
            transport.serverSends("""{"id":2,"result":null,"error":[24,"Unauthorized worker",null]}""")
            awaitUntil { events.any { it is WorkEvent.Disconnected } }
            val disconnected = events.filterIsInstance<WorkEvent.Disconnected>().first()
            assertTrue(disconnected.reason.contains("authorize"))
            assertTrue(disconnected.retryInMillis >= 2_000)
        } finally {
            collector.cancel()
            collectorScope.cancel()
            client.disconnect()
            clientScope.cancel()
        }
    }

    @Test
    fun `disconnect closes the socket away from the calling thread`() = runBlocking {
        val transport = HangingTransport()
        val clientScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> }
        )
        val client = StratumClient(
            transportFactory = { transport },
            scope = clientScope,
            idleReconnectMs = 60_000,
        )
        val caller = Thread.currentThread().name
        try {
            client.connect(PoolEndpoint(true, "pool.test", 4333), "bc1qexample.hashes", "x")
            awaitUntil { transport.written.any { it.contains("mining.subscribe") } }

            /* The terminal's STOP button runs this on the main thread. A TLS
             * socket close writes a record there, which the platform refuses. */
            client.disconnect()

            assertTrue("the socket was never closed", transport.closed.await(2, TimeUnit.SECONDS))
            assertTrue(
                "closed on the calling thread: ${transport.closeThreads}",
                transport.closeThreads.none { it == caller },
            )
        } finally {
            clientScope.cancel()
        }
    }

    @Test
    fun `a close that throws does not escape disconnect`() = runBlocking {
        val transport = HangingTransport(closeFailure = RuntimeException("NetworkOnMainThreadException"))
        val clientScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> }
        )
        val client = StratumClient(
            transportFactory = { transport },
            scope = clientScope,
            idleReconnectMs = 60_000,
        )
        try {
            client.connect(PoolEndpoint(true, "pool.test", 4333), "bc1qexample.hashes", "x")
            awaitUntil { transport.written.any { it.contains("mining.subscribe") } }

            client.disconnect()

            assertEquals(ConnectionState.DISCONNECTED, client.state.value)
            assertTrue(transport.closed.await(2, TimeUnit.SECONDS))
        } finally {
            clientScope.cancel()
        }
    }
}
