package com.corejbh.hashes.notification

import com.corejbh.hashes.engine.EngineSnapshot
import com.corejbh.hashes.network.ConnectionState
import com.corejbh.hashes.network.PoolEndpoint
import com.corejbh.hashes.session.MiningSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinerNotificationContentTest {

    private val endpoint = PoolEndpoint(secure = true, host = "public-pool.io", port = 4333)

    private fun state(
        turbo: Boolean = false,
        connection: ConnectionState = ConnectionState.CONNECTED,
        hashRate: Double = 367_600.0,
        threads: Int = 1,
        height: Long? = 977_840,
        temperature: Double? = 39.0,
        accepted: Int = 0,
        rejected: Int = 0,
    ) = MiningSessionState(
        endpoint = endpoint,
        connection = connection,
        blockHeight = height,
        accepted = accepted,
        rejected = rejected,
        temperatureCelsius = temperature,
        mining = true,
        snapshot = EngineSnapshot.IDLE.copy(
            running = true,
            hashRate = hashRate,
            threads = threads,
            turbo = turbo,
        ),
    ).let { base ->
        if (turbo) base.copy(settings = base.settings.withTurbo(true)) else base
    }

    @Test
    fun `the collapsed line carries the link and the live rate`() {
        val content = MinerNotificationContent.of(state())
        assertEquals("STRATUM+TLS OK \u00b7 367.6 KH/s", content.text)
    }

    @Test
    fun `the mode is in the title and the expanded lines`() {
        val content = MinerNotificationContent.of(state())
        assertEquals("HASHES \u00b7 COOL", content.title)
        assertTrue(content.bigText.contains("COOL"))
        assertTrue(content.bigText.contains("39C"))
        assertTrue(content.bigText.contains("BLOCK 977,840"))
        assertTrue(content.bigText.contains("SHARES A:0 R:0"))
        assertFalse(content.bigText.contains("TURBO"))
    }

    @Test
    fun `turbo says turbo`() {
        val content = MinerNotificationContent.of(state(turbo = true, threads = 7))
        assertEquals("HASHES \u00b7 TURBO", content.title)
        assertTrue(content.bigText.contains("TURBO"))
        assertTrue(content.bigText.contains("7T"))
    }

    @Test
    fun `a tcp pool is never reported as tls`() {
        val tcp = state(connection = ConnectionState.CONNECTED).copy(
            endpoint = PoolEndpoint(secure = false, host = "pool.nerdminers.org", port = 3333),
        )
        val content = MinerNotificationContent.of(tcp)
        assertTrue(content.text.startsWith("STRATUM+TCP OK"))
        assertFalse(content.text.contains("TLS"))
    }

    @Test
    fun `a lost link says so instead of a rate that is not arriving`() {
        val content = MinerNotificationContent.of(
            state(connection = ConnectionState.DISCONNECTED, hashRate = 0.0),
        )
        assertEquals("STRATUM+TLS OFFLINE \u00b7 0 H/s", content.text)
    }

    @Test
    fun `an unknown height and temperature do not leave holes`() {
        val content = MinerNotificationContent.of(
            state(height = null, temperature = null, hashRate = 0.0, threads = 0),
        )
        assertTrue(content.bigText.contains("BLOCK --"))
        assertTrue(content.bigText.contains("--T") || content.bigText.contains(" --"))
        assertFalse(content.bigText.contains("null"))
        assertEquals("BLOCK --", content.subText)
    }

    @Test
    fun `the shade text is plain ascii apart from the separator`() {
        val content = MinerNotificationContent.of(state())
        val allowedPunctuation = setOf('\u00b7', ' ', ':', '/', '.', '+', ',', '-', '\n')
        val text = content.title + content.text + content.bigText + content.subText
        for (character in text) {
            assertTrue(
                "unexpected character in the notification: $character",
                character.isLetterOrDigit() || character in allowedPunctuation,
            )
        }
    }
}
