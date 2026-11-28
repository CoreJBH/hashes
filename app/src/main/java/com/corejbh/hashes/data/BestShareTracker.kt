package com.corejbh.hashes.data

import com.corejbh.hashes.crypto.Hex
import com.corejbh.hashes.network.Target
import java.math.BigDecimal

/** The best hash this device has found, as the terminal reports it. */
data class BestShareRecord(
    val hashHex: String,
    val difficulty: BigDecimal,
    val leadingZeros: Int,
    val timestampMillis: Long,
)

/**
 * Keeps `SESSION_BEST_SHARE` in memory and `ALL_TIME_BEST` on disk.
 *
 * A best share is the lowest hash the device has produced; its value is the
 * difficulty that hash represents. The all-time record has to survive a restart,
 * so it is written through [BestSharePersistence] whenever it improves.
 */
class BestShareTracker(
    private val persistence: BestSharePersistence,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Update { NONE, SESSION, ALL_TIME }

    private var session: BestShareRecord? = null
    private var allTime: BestShareRecord? = persistence.read()

    fun sessionBest(): BestShareRecord? = session

    fun allTimeBest(): BestShareRecord? = allTime

    fun resetSession() {
        session = null
    }

    fun observe(hash: ByteArray): Update {
        if (hash.size != 32) return Update.NONE
        /* An all-zero digest is not a finding. The core publishes a zeroed
         * buffer until its first hash completes, and zero is also what a
         * failed read leaves behind; as a share it would count as difficulty
         * 1.8e308 and, being unbeatable, freeze the all-time record. */
        if (hash.all { it == 0.toByte() }) return Update.NONE
        val record = record(hash)
        var update = Update.NONE
        if (isBetter(record, session)) {
            session = record
            update = Update.SESSION
        }
        if (isBetter(record, allTime)) {
            allTime = record
            persistence.write(record)
            update = Update.ALL_TIME
        }
        return update
    }

    /** Observes a hex hash in display order. */
    fun observeHex(displayHex: String): Update {
        val bytes = Hex.decodeOrNull(displayHex) ?: return Update.NONE
        return observe(bytes.reversedArray())
    }

    private fun record(hash: ByteArray): BestShareRecord = BestShareRecord(
        hashHex = Hex.encode(hash.reversedArray()),
        difficulty = Target.hashDifficulty(hash),
        leadingZeros = Target.leadingZeroDigits(hash),
        timestampMillis = clock(),
    )

    private fun isBetter(candidate: BestShareRecord, current: BestShareRecord?): Boolean {
        if (current == null) return true
        val comparison = candidate.difficulty.compareTo(current.difficulty)
        return if (comparison != 0) comparison > 0 else candidate.leadingZeros > current.leadingZeros
    }

    companion object {
        /** Two records with the same hash and difficulty are the same finding. */
        fun sameFinding(first: BestShareRecord?, second: BestShareRecord?): Boolean {
            if (first == null || second == null) return first == second
            return first.hashHex == second.hashHex && first.difficulty.compareTo(second.difficulty) == 0
        }
    }
}

interface BestSharePersistence {
    fun read(): BestShareRecord?
    fun write(record: BestShareRecord)
}

/** SharedPreferences implementation; the value is a plain string and a decimal. */
class SharedPreferencesBestSharePersistence(
    private val preferences: android.content.SharedPreferences,
) : BestSharePersistence {

    constructor(context: android.content.Context) : this(
        context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)
    )

    override fun read(): BestShareRecord? {
        val hash = preferences.getString(KEY_HASH, null) ?: return null
        /* A build before the zero guard saved the all-zero digest; the record is
         * worthless and would outrank every real share, so it is dropped here
         * and the device heals on the first start after the update. */
        if (hash.isNotEmpty() && hash.all { it == '0' }) {
            clear()
            return null
        }
        val difficulty = preferences.getString(KEY_DIFFICULTY, null)?.toBigDecimalOrNull() ?: return null
        return BestShareRecord(
            hashHex = hash,
            difficulty = difficulty,
            leadingZeros = preferences.getInt(KEY_ZEROS, 0),
            timestampMillis = preferences.getLong(KEY_TIME, 0L),
        )
    }

    override fun write(record: BestShareRecord) {
        preferences.edit()
            .putString(KEY_HASH, record.hashHex)
            .putString(KEY_DIFFICULTY, record.difficulty.toPlainString())
            .putInt(KEY_ZEROS, record.leadingZeros)
            .putLong(KEY_TIME, record.timestampMillis)
            .apply()
    }

    /** Removes a stored record that can no longer be trusted. */
    private fun clear() {
        preferences.edit()
            .remove(KEY_HASH)
            .remove(KEY_DIFFICULTY)
            .remove(KEY_ZEROS)
            .remove(KEY_TIME)
            .apply()
    }

    companion object {
        private const val FILE = "hashes_best_share"
        private const val KEY_HASH = "all_time_hash"
        private const val KEY_DIFFICULTY = "all_time_difficulty"
        private const val KEY_ZEROS = "all_time_zeros"
        private const val KEY_TIME = "all_time_time"
    }
}
