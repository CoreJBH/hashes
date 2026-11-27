package com.corejbh.hashes.network

/**
 * Solo pools the app offers. Each entry lists what the pool's own page says, so
 * the choice on screen matches the endpoint that is actually dialled.
 *
 * Facts recorded here were read from the pools' published connection settings
 * in November 2026. Ports move; a custom endpoint can always be entered.
 */
enum class PoolPreset(
    val id: String,
    val title: String,
    val endpoint: PoolEndpoint,
    val password: String,
    val summary: String,
) {
    /** 0% fee, solo, Stratum V1 over TLS. Start difficulty is assigned by the pool. */
    PUBLIC_POOL(
        id = "public_pool",
        title = "PUBLIC POOL / SOLO",
        endpoint = PoolEndpoint(secure = true, host = "public-pool.io", port = 4333),
        password = "x",
        summary = "0% FEE / TLS / PAYS YOUR ADDRESS IN FULL",
    ),

    /** Ultra-low difficulty pool for very small miners; TCP only, no TLS listener. */
    NERDMINERS(
        id = "nerdminers",
        title = "NERDMINERS / SOLO",
        endpoint = PoolEndpoint(secure = false, host = "pool.nerdminers.org", port = 3333),
        password = "x",
        summary = "ULTRA-LOW DIFFICULTY / TCP ONLY / NO TLS",
    ),

    /** Long-running solo pool. Minimum share difficulty 10,000; a hardcore option. */
    CKPOOL(
        id = "ckpool",
        title = "CKPOOL / SOLO (HARDCORE)",
        endpoint = PoolEndpoint(secure = false, host = "solo.ckpool.org", port = 3333),
        password = "x",
        summary = "2% FEE / MIN DIFF 10,000 / NOT FOR SMALL HASH RATES",
    ),

    /** A pool the user typed in themselves. */
    CUSTOM(
        id = "custom",
        title = "CUSTOM ENDPOINT",
        endpoint = PoolEndpoint(secure = true, host = "public-pool.io", port = 4333),
        password = "x",
        summary = "STRATUM+SSL:// OR STRATUM+TCP:// HOST:PORT",
    );

    val isCustom: Boolean
        get() = this == CUSTOM

    companion object {
        fun byId(id: String?): PoolPreset? = entries.firstOrNull { it.id == id }
    }
}
