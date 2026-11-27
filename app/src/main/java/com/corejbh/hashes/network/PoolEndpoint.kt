package com.corejbh.hashes.network

/**
 * A Stratum endpoint. TLS is the default because the login line carries the
 * payout address, and a plaintext pool line can be rewritten in transit to pay
 * someone else; a pool that only speaks TCP has to be asked for explicitly.
 */
data class PoolEndpoint(
    val secure: Boolean,
    val host: String,
    val port: Int,
) {
    /** Label shown in the status bar. */
    val label: String
        get() = if (secure) "STRATUM+TLS" else "STRATUM+TCP"

    val display: String
        get() = "$host:$port"

    val url: String
        get() = (if (secure) "stratum+ssl://" else "stratum+tcp://") + display

    override fun toString(): String = url

    companion object {
        const val DEFAULT_TCP_PORT = 3333
        const val DEFAULT_TLS_PORT = 4333

        /** Parses `stratum+ssl://host:port`, `stratum+tcp://host:port` or a bare `host:port`. */
        fun parse(text: String): PoolEndpoint? {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed.length > 260) return null
            if (trimmed.any { it.isWhitespace() }) return null

            val (secure, remainder) = when {
                trimmed.startsWith("stratum+ssl://", ignoreCase = true) ->
                    true to trimmed.substring("stratum+ssl://".length)
                trimmed.startsWith("stratum+tls://", ignoreCase = true) ->
                    true to trimmed.substring("stratum+tls://".length)
                trimmed.startsWith("stratum+tcp://", ignoreCase = true) ->
                    false to trimmed.substring("stratum+tcp://".length)
                trimmed.contains("://") -> return null
                else -> true to trimmed
            }
            if (remainder.isEmpty() || remainder.contains('/') || remainder.contains('@')) return null

            val host: String
            val portText: String?
            if (remainder.startsWith("[")) {
                val end = remainder.indexOf(']')
                if (end <= 1) return null
                host = remainder.substring(1, end)
                val after = remainder.substring(end + 1)
                portText = when {
                    after.isEmpty() -> null
                    after.startsWith(":") -> after.substring(1)
                    else -> return null
                }
            } else {
                val separator = remainder.lastIndexOf(':')
                if (separator < 0) {
                    host = remainder
                    portText = null
                } else {
                    host = remainder.substring(0, separator)
                    portText = remainder.substring(separator + 1)
                }
            }
            if (host.isEmpty()) return null
            if (!host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == ':' }) return null
            if (!host.contains('.') && host != "localhost" && !host.contains(':')) return null

            val port = when {
                portText == null -> if (secure) DEFAULT_TLS_PORT else DEFAULT_TCP_PORT
                portText.isEmpty() -> return null
                else -> portText.toIntOrNull() ?: return null
            }
            if (port !in 1..65535) return null
            return PoolEndpoint(secure, host, port)
        }
    }
}
