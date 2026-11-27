package com.corejbh.hashes.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BackoffTest {

    @Test
    fun `backoff doubles and caps at sixty seconds`() {
        val backoff = Backoff()
        val delays = (0 until 8).map { backoff.nextDelayMs() }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L, 60_000L), delays)
        assertEquals(8, backoff.attempts)
    }

    @Test
    fun `reset starts the ladder again`() {
        val backoff = Backoff()
        repeat(4) { backoff.nextDelayMs() }
        backoff.reset()
        assertEquals(0, backoff.attempts)
        assertEquals(2_000L, backoff.nextDelayMs())
    }
}

class PoolEndpointTest {

    @Test
    fun `parses a tls endpoint`() {
        val endpoint = PoolEndpoint.parse("stratum+ssl://public-pool.io:4333")
        assertNotNull(endpoint)
        assertEquals(true, endpoint!!.secure)
        assertEquals("public-pool.io", endpoint.host)
        assertEquals(4333, endpoint.port)
        assertEquals("STRATUM+TLS", endpoint.label)
    }

    @Test
    fun `accepts the stratum tls scheme`() {
        val endpoint = PoolEndpoint.parse("stratum+tls://public-pool.io:4333")
        assertEquals(true, endpoint?.secure)
    }

    @Test
    fun `parses a tcp endpoint`() {
        val endpoint = PoolEndpoint.parse("stratum+tcp://solo.ckpool.org:3333")
        assertNotNull(endpoint)
        assertEquals(false, endpoint!!.secure)
        assertEquals("STRATUM+TCP", endpoint.label)
    }

    @Test
    fun `a bare host and port is tls by default`() {
        val endpoint = PoolEndpoint.parse("public-pool.io:4333")
        assertEquals(true, endpoint?.secure)
        assertEquals(4333, endpoint?.port)
    }

    @Test
    fun `a bare hostname uses the tls default port`() {
        val endpoint = PoolEndpoint.parse("public-pool.io")
        assertEquals(4333, endpoint?.port)
    }

    @Test
    fun `a tcp hostname without a port uses 3333`() {
        val endpoint = PoolEndpoint.parse("stratum+tcp://pool.example.org")
        assertEquals(3333, endpoint?.port)
        assertEquals(false, endpoint?.secure)
    }

    @Test
    fun `refuses an empty or malformed value`() {
        assertNull(PoolEndpoint.parse(""))
        assertNull(PoolEndpoint.parse("   "))
        assertNull(PoolEndpoint.parse("http://public-pool.io:4333"))
        assertNull(PoolEndpoint.parse("public-pool.io:0"))
        assertNull(PoolEndpoint.parse("public-pool.io:70000"))
        assertNull(PoolEndpoint.parse("public pool.io:3333"))
        assertNull(PoolEndpoint.parse("stratum+ssl://public-pool.io:4333/path"))
    }

    @Test
    fun `parses an ipv6 literal`() {
        val endpoint = PoolEndpoint.parse("stratum+tcp://[::1]:3333")
        assertNotNull(endpoint)
        assertEquals("::1", endpoint!!.host)
        assertEquals(3333, endpoint.port)
    }
}
