package com.corejbh.hashes.network

import com.corejbh.hashes.crypto.Hex
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stratum V1 wire format: the requests this client sends and the messages it
 * understands. Parsing is separated from the socket so every message the pools
 * actually send can be tested against a captured line.
 */
object StratumProtocol {

    const val ID_SUBSCRIBE = 1L
    const val ID_AUTHORIZE = 2L
    const val ID_SUGGEST_DIFFICULTY = 3L
    const val ID_EXTRANONCE_SUBSCRIBE = 4L

    data class StratumError(val code: Int, val message: String) {
        override fun toString(): String = "$code $message"
    }

    sealed interface Message {
        data class Response(val id: Long, val result: Any?, val error: StratumError?) : Message
        data class Notify(val job: MiningJob) : Message
        data class SetDifficulty(val difficulty: Double) : Message
        data class SetExtranonce(val extranonce1: String, val extranonce2Size: Int) : Message
        /** A method this version does not act on, such as client.show_message. */
        data class Notice(val method: String, val text: String?) : Message
    }

    /**
     * Parses one line. Throws [StratumFormatException] for a line that is not
     * JSON or whose notify parameters cannot be assembled into a job.
     */
    fun parse(line: String): Message {
        val json = try {
            JSONObject(line)
        } catch (error: Exception) {
            throw StratumFormatException("not a JSON object")
        }

        val method = json.optString("method", "")
        if (method.isNotEmpty()) {
            return when (method) {
                "mining.notify" -> Message.Notify(MiningJob.fromNotify(parameters(json)))
                "mining.set_difficulty" -> {
                    val values = parameters(json)
                    val difficulty = when (val value = values.firstOrNull()) {
                        is Number -> value.toDouble()
                        is String -> value.toDoubleOrNull()
                        else -> null
                    } ?: throw StratumFormatException("set_difficulty without a number")
                    Message.SetDifficulty(difficulty)
                }
                "mining.set_extranonce" -> {
                    val values = parameters(json)
                    val extranonce1 = values.getOrNull(0) as? String
                        ?: throw StratumFormatException("set_extranonce without extranonce1")
                    val size = (values.getOrNull(1) as? Number)?.toInt() ?: 4
                    if (!Hex.isHex(extranonce1) || size !in 2..32) {
                        throw StratumFormatException("set_extranonce with an unusable value")
                    }
                    Message.SetExtranonce(extranonce1, size)
                }
                else -> Message.Notice(method, json.optJSONArray("params")?.optString(0))
            }
        }

        if (!json.has("id")) throw StratumFormatException("message without method or id")
        val id = when (val raw = json.opt("id")) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull() ?: throw StratumFormatException("non-numeric id")
            else -> throw StratumFormatException("response without an id")
        }
        val error = json.optJSONArray("error")?.let { array ->
            if (array.length() == 0) {
                null
            } else {
                StratumError(
                    code = array.optInt(0),
                    message = array.optString(1, "unknown error"),
                )
            }
        }
        val result = when (val raw = json.opt("result")) {
            null, JSONObject.NULL -> null
            else -> raw
        }
        return Message.Response(id, result, error)
    }

    /** Reads the `subscriptions`, `extranonce1` and `extranonce2_size` of a subscribe result. */
    fun subscribeResult(result: Any?): SubscribeSession {
        val array = result as? JSONArray
            ?: throw StratumFormatException("subscribe result is not an array")
        if (array.length() < 3) throw StratumFormatException("subscribe result is short")
        val extranonce1 = array.optString(1, "")
        val size = array.optInt(2, -1)
        if (!Hex.isHex(extranonce1) || size !in 2..32) {
            throw StratumFormatException("subscribe result has no usable extranonce")
        }
        val sessionId = array.optJSONArray(0)
            ?.let { subscriptions ->
                (0 until subscriptions.length())
                    .mapNotNull { subscriptions.optJSONArray(it) }
                    .firstOrNull { it.length() >= 2 && it.optString(0) == "mining.notify" }
                    ?.optString(1)
            }
        return SubscribeSession(extranonce1 = extranonce1, extranonce2Size = size, sessionId = sessionId)
    }

    data class SubscribeSession(
        val extranonce1: String,
        val extranonce2Size: Int,
        val sessionId: String?,
    )

    fun subscribe(id: Long, userAgent: String): String = request(
        id,
        "mining.subscribe",
        JSONArray().put(userAgent),
    )

    fun authorize(id: Long, user: String, password: String): String = request(
        id,
        "mining.authorize",
        JSONArray().put(user).put(password),
    )

    fun suggestDifficulty(id: Long, difficulty: Double): String = request(
        id,
        "mining.suggest_difficulty",
        JSONArray().put(difficulty),
    )

    fun extranonceSubscribe(id: Long): String = request(
        id,
        "mining.extranonce.subscribe",
        JSONArray(),
    )

    /**
     * `mining.submit` parameters: worker name, job id, extranonce2, ntime and
     * the nonce as the four bytes of the header field in little-endian order.
     */
    fun submit(
        id: Long,
        user: String,
        jobId: String,
        extranonce2Hex: String,
        networkTime: String,
        nonce: Long,
        versionBits: String? = null,
    ): String {
        val params = JSONArray()
            .put(user)
            .put(jobId)
            .put(extranonce2Hex)
            .put(networkTime)
            .put(nonceLittleEndianHex(nonce))
        if (versionBits != null) params.put(versionBits)
        return request(id, "mining.submit", params)
    }

    fun nonceLittleEndianHex(nonce: Long): String {
        val value = nonce and 0xffffffffL
        val bigEndian = String.format("%08x", value)
        return Hex.reverseHex(bigEndian)
    }

    private fun request(id: Long, method: String, params: JSONArray): String =
        JSONObject()
            .put("id", id)
            .put("method", method)
            .put("params", params)
            .toString()

    private fun parameters(json: JSONObject): List<Any?> {
        val array = json.optJSONArray("params") ?: return emptyList()
        return (0 until array.length()).map { index -> value(array.opt(index)) }
    }

    /** org.json's JSONArray is not a java.util.List, so nested arrays are converted here. */
    private fun value(raw: Any?): Any? = when (raw) {
        null, JSONObject.NULL -> null
        is JSONArray -> (0 until raw.length()).map { index -> value(raw.opt(index)) }
        else -> raw
    }
}
