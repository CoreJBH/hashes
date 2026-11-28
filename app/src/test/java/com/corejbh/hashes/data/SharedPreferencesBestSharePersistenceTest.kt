package com.corejbh.hashes.data

import android.content.SharedPreferences
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-disk all-time record, against an in-memory SharedPreferences.
 *
 * The regression it locks down: a build before the zero guard saved the
 * all-zero digest with difficulty 1.8e308, and since no hash can beat that, the
 * terminal kept printing `DIFF 1.8e308 / 0000...` for ever. The record is now
 * dropped on read, so a phone that already holds the bad value heals on the
 * first start after the update.
 */
class SharedPreferencesBestSharePersistenceTest {

    private class FakePreferences(
        val values: MutableMap<String, Any?> = mutableMapOf(),
    ) : SharedPreferences {

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(key: String, defValue: String?): String? =
            values[key] as? String ?: defValue

        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST")
            (values[key] as? MutableSet<String>) ?: defValues

        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor(values)

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = Unit
    }

    private class FakeEditor(private val values: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private val staged = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            apply { staged[key] = value }

        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
            apply { staged[key] = values }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor =
            apply { staged[key] = value }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor =
            apply { staged[key] = value }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
            apply { staged[key] = value }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
            apply { staged[key] = value }

        override fun remove(key: String): SharedPreferences.Editor = apply { removed += key }

        override fun clear(): SharedPreferences.Editor = apply { values.clear() }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            staged.forEach { (key, value) -> values[key] = value }
            removed.forEach { values.remove(it) }
            staged.clear()
            removed.clear()
        }
    }

    private val goodRecord = BestShareRecord(
        hashHex = "0".repeat(60) + "01ab",
        difficulty = BigDecimal("1.2e40"),
        leadingZeros = 14,
        timestampMillis = 1_700_000_000_000L,
    )

    @Test
    fun `a stored record reads back`() {
        val preferences = FakePreferences()
        val persistence = SharedPreferencesBestSharePersistence(preferences)
        persistence.write(goodRecord)

        val read = persistence.read()
        assertEquals(goodRecord.hashHex, read?.hashHex)
        assertTrue(goodRecord.difficulty.compareTo(read!!.difficulty) == 0)
        assertEquals(goodRecord.leadingZeros, read.leadingZeros)
        assertEquals(goodRecord.timestampMillis, read.timestampMillis)
    }

    @Test
    fun `an all zero stored hash is dropped`() {
        val preferences = FakePreferences(
            mutableMapOf<String, Any?>(
                "all_time_hash" to "0".repeat(64),
                "all_time_difficulty" to "1.7976931348623157E308",
                "all_time_zeros" to 64,
                "all_time_time" to 1_700_000_000_000L,
            ),
        )
        val persistence = SharedPreferencesBestSharePersistence(preferences)

        assertNull(persistence.read())
    }

    @Test
    fun `the bad record is removed from disk, not just skipped`() {
        val preferences = FakePreferences(
            mutableMapOf<String, Any?>(
                "all_time_hash" to "0".repeat(64),
                "all_time_difficulty" to "1.7976931348623157E308",
                "all_time_zeros" to 64,
                "all_time_time" to 1_700_000_000_000L,
            ),
        )
        val persistence = SharedPreferencesBestSharePersistence(preferences)
        persistence.read()

        assertTrue(preferences.values.isEmpty())
        assertFalse(preferences.contains("all_time_hash"))
        assertFalse(preferences.contains("all_time_difficulty"))
    }

    @Test
    fun `a real record with leading zeros is kept`() {
        val preferences = FakePreferences()
        val persistence = SharedPreferencesBestSharePersistence(preferences)
        persistence.write(goodRecord)

        assertEquals(goodRecord.hashHex, persistence.read()?.hashHex)
    }

    @Test
    fun `no stored record reads as none`() {
        assertNull(SharedPreferencesBestSharePersistence(FakePreferences()).read())
    }

    @Test
    fun `the tracker does not adopt the bad record`() {
        val preferences = FakePreferences(
            mutableMapOf<String, Any?>(
                "all_time_hash" to "0".repeat(64),
                "all_time_difficulty" to "1.7976931348623157E308",
                "all_time_zeros" to 64,
                "all_time_time" to 1_700_000_000_000L,
            ),
        )
        val tracker = BestShareTracker(SharedPreferencesBestSharePersistence(preferences))

        assertNull(tracker.allTimeBest())
        assertTrue(preferences.values.isEmpty())
    }
}
