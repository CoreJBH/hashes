package com.corejbh.hashes.network

import com.corejbh.hashes.TestVectors
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StratumProtocolTest {

    private fun notifyJson(clean: Boolean = true): String =
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
          $clean
        ]}
        """.trimIndent()

    @Test
    fun `parses a mining notify into a job`() {
        val message = StratumProtocol.parse(notifyJson()) as StratumProtocol.Message.Notify
        val job = message.job
        assertEquals("bf", job.jobId)
        assertEquals(TestVectors.BLOCK_PREV_HASH_NOTIFY, job.previousHash)
        assertEquals(TestVectors.BLOCK_COINB1, job.coinbase1)
        assertEquals(TestVectors.BLOCK_COINB2, job.coinbase2)
        assertEquals(2, job.merkleBranches.size)
        assertEquals(TestVectors.BLOCK_BRANCH_0, job.merkleBranches[0])
        assertEquals("00000001", job.version)
        assertEquals("1b04864c", job.networkBits)
        assertEquals("4d1b2237", job.networkTime)
        assertTrue(job.cleanJobs)
    }

    @Test
    fun `parses clean_jobs false`() {
        val message = StratumProtocol.parse(notifyJson(clean = false)) as StratumProtocol.Message.Notify
        assertEquals(false, message.job.cleanJobs)
    }

    @Test
    fun `refuses a notify with too few parameters`() {
        val line = """{"id":null,"method":"mining.notify","params":["bf","00"]}"""
        assertThrows(StratumFormatException::class.java) { StratumProtocol.parse(line) }
    }

    @Test
    fun `refuses a notify whose previous hash is not 32 bytes`() {
        val line = notifyJson().replace(TestVectors.BLOCK_PREV_HASH_NOTIFY, "abcd")
        assertThrows(StratumFormatException::class.java) { StratumProtocol.parse(line) }
    }

    @Test
    fun `parses set difficulty`() {
        val message = StratumProtocol.parse(
            """{"id":null,"method":"mining.set_difficulty","params":[0.001]}"""
        ) as StratumProtocol.Message.SetDifficulty
        assertEquals(0.001, message.difficulty, 1e-12)
    }

    @Test
    fun `parses set extranonce`() {
        val message = StratumProtocol.parse(
            """{"id":null,"method":"mining.set_extranonce","params":["abcdef01",4]}"""
        ) as StratumProtocol.Message.SetExtranonce
        assertEquals("abcdef01", message.extranonce1)
        assertEquals(4, message.extranonce2Size)
    }

    @Test
    fun `parses a subscribe result with its session id`() {
        val line = """
            {"id":1,"result":[[["mining.set_difficulty","s1"],["mining.notify","n1"]],"abcdef01",4],"error":null}
        """.trimIndent()
        val response = StratumProtocol.parse(line) as StratumProtocol.Message.Response
        val session = StratumProtocol.subscribeResult(response.result)
        assertEquals("abcdef01", session.extranonce1)
        assertEquals(4, session.extranonce2Size)
        assertEquals("n1", session.sessionId)
    }

    @Test
    fun `parses an authorize result`() {
        val response = StratumProtocol.parse("""{"id":2,"result":true,"error":null}""")
            as StratumProtocol.Message.Response
        assertEquals(true, response.result)
        assertEquals(null, response.error)
    }

    @Test
    fun `parses an error response`() {
        val response = StratumProtocol.parse(
            """{"id":7,"result":null,"error":[21,"Job not found",null]}"""
        ) as StratumProtocol.Message.Response
        assertEquals(21, response.error?.code)
        assertEquals("Job not found", response.error?.message)
        assertEquals(null, response.result)
    }

    @Test
    fun `treats an unknown method as a notice`() {
        val message = StratumProtocol.parse(
            """{"id":null,"method":"client.show_message","params":["maintenance soon"]}"""
        ) as StratumProtocol.Message.Notice
        assertEquals("client.show_message", message.method)
        assertEquals("maintenance soon", message.text)
    }

    @Test
    fun `refuses a line that is not json`() {
        assertThrows(StratumFormatException::class.java) { StratumProtocol.parse("hello") }
    }

    @Test
    fun `builds a subscribe request`() {
        val json = JSONObject(StratumProtocol.subscribe(1, "Hashes/0.1.0"))
        assertEquals(1, json.getInt("id"))
        assertEquals("mining.subscribe", json.getString("method"))
        assertEquals("Hashes/0.1.0", json.getJSONArray("params").getString(0))
    }

    @Test
    fun `builds an authorize request`() {
        val json = JSONObject(StratumProtocol.authorize(2, "bc1qexample.hashes", "x"))
        assertEquals("mining.authorize", json.getString("method"))
        val params = json.getJSONArray("params")
        assertEquals("bc1qexample.hashes", params.getString(0))
        assertEquals("x", params.getString(1))
    }

    @Test
    fun `builds a submit request with a little endian nonce`() {
        val json = JSONObject(
            StratumProtocol.submit(
                id = 100,
                user = "bc1qexample.hashes",
                jobId = "bf",
                extranonce2Hex = "00000001",
                networkTime = "4d1b2237",
                nonce = TestVectors.BLOCK_NONCE,
            )
        )
        assertEquals("mining.submit", json.getString("method"))
        val params = json.getJSONArray("params")
        assertEquals("bc1qexample.hashes", params.getString(0))
        assertEquals("bf", params.getString(1))
        assertEquals("00000001", params.getString(2))
        assertEquals("4d1b2237", params.getString(3))
        assertEquals("0f2b5710", params.getString(4))
    }

    @Test
    fun `nonce hex is the four header bytes`() {
        assertEquals("0f2b5710", StratumProtocol.nonceLittleEndianHex(0x10572b0fL))
        assertEquals("00000000", StratumProtocol.nonceLittleEndianHex(0))
        assertEquals("ffffffff", StratumProtocol.nonceLittleEndianHex(0xffffffffL))
    }

    @Test
    fun `subscribe result without an extranonce is refused`() {
        val line = """{"id":1,"result":[[],"",0],"error":null}"""
        val response = StratumProtocol.parse(line) as StratumProtocol.Message.Response
        assertThrows(StratumFormatException::class.java) {
            StratumProtocol.subscribeResult(response.result)
        }
    }

    @Test
    fun `a response without an id is refused`() {
        assertThrows(StratumFormatException::class.java) {
            StratumProtocol.parse("""{"result":true,"error":null}""")
        }
    }
}
