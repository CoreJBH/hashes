package com.corejbh.hashes.data

import com.corejbh.hashes.network.PoolEndpoint
import com.corejbh.hashes.network.PoolPreset

/** What the settings sheet changes; everything else is per-session state. */
data class MinerSettings(
    val payoutAddress: String = "",
    val presetId: String = PoolPreset.PUBLIC_POOL.id,
    val customEndpoint: String = "",
    val turbo: Boolean = false,
) {
    val preset: PoolPreset
        get() = PoolPreset.byId(presetId) ?: PoolPreset.PUBLIC_POOL

    /** The endpoint the preset or the typed text resolves to, if it resolves. */
    fun endpointOrNull(): PoolEndpoint? =
        if (preset.isCustom) {
            PoolEndpoint.parse(customEndpoint)
        } else {
            preset.endpoint
        }

    fun endpoint(): PoolEndpoint = endpointOrNull() ?: PoolPreset.PUBLIC_POOL.endpoint

    /** Pool login name: the payout address plus a fixed worker tag. */
    fun workerName(): String = "$payoutAddress.hashes"

    fun password(): String = preset.password

    fun withAddress(address: String): MinerSettings = copy(payoutAddress = address.trim())

    fun withPreset(preset: PoolPreset): MinerSettings = copy(presetId = preset.id)

    fun withCustomEndpoint(text: String): MinerSettings =
        copy(presetId = PoolPreset.CUSTOM.id, customEndpoint = text.trim())

    fun withTurbo(enabled: Boolean): MinerSettings = copy(turbo = enabled)
}

interface SettingsStore {
    fun load(): MinerSettings
    fun save(settings: MinerSettings)
}

class SharedPreferencesSettingsStore(private val preferences: android.content.SharedPreferences) :
    SettingsStore {

    constructor(context: android.content.Context) : this(
        context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)
    )

    override fun load(): MinerSettings = MinerSettings(
        payoutAddress = preferences.getString(KEY_ADDRESS, "").orEmpty(),
        presetId = preferences.getString(KEY_PRESET, PoolPreset.PUBLIC_POOL.id)
            ?: PoolPreset.PUBLIC_POOL.id,
        customEndpoint = preferences.getString(KEY_CUSTOM_ENDPOINT, "").orEmpty(),
        turbo = preferences.getBoolean(KEY_TURBO, false),
    )

    override fun save(settings: MinerSettings) {
        preferences.edit()
            .putString(KEY_ADDRESS, settings.payoutAddress)
            .putString(KEY_PRESET, settings.presetId)
            .putString(KEY_CUSTOM_ENDPOINT, settings.customEndpoint)
            .putBoolean(KEY_TURBO, settings.turbo)
            .apply()
    }

    companion object {
        private const val FILE = "hashes_settings"
        private const val KEY_ADDRESS = "payout_address"
        private const val KEY_PRESET = "pool_preset"
        private const val KEY_CUSTOM_ENDPOINT = "custom_endpoint"
        private const val KEY_TURBO = "turbo_mode"
    }
}
