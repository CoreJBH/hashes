package com.corejbh.hashes.crypto

/**
 * Extracts a plain Bitcoin address from what a QR code or a clipboard actually
 * contains.
 *
 * BIP-21 links look like `bitcoin:<address>?amount=0.01&label=...`, wallets
 * print them, and people paste them with the query string attached. Only the
 * address part is meaningful for a payout target, so the rest is discarded here
 * and the result is still put through [BitcoinAddressValidator] - a QR code is
 * not trusted just because a camera saw it.
 */
object BitcoinUri {

    private const val SCHEME = "bitcoin:"

    fun extractAddress(input: String): String? {
        var text = input.trim()
        if (text.isEmpty()) return null
        if (text.startsWith(SCHEME, ignoreCase = true)) {
            text = text.substring(SCHEME.length)
            // Some wallets write bitcoin://<address>.
            if (text.startsWith("//")) text = text.substring(2)
            text = percentDecode(text)
            val cut = text.indexOf('?')
            if (cut >= 0) text = text.substring(0, cut)
            val hash = text.indexOf('#')
            if (hash >= 0) text = text.substring(0, hash)
        } else {
            text = percentDecode(text)
        }
        return text.trim().ifEmpty { null }
    }

    /** Decodes %XX sequences; anything else, including a stray %, is kept as typed. */
    private fun percentDecode(text: String): String {
        if (!text.contains('%')) return text
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character == '%' && index + 2 < text.length) {
                val value = text.substring(index + 1, index + 3).toIntOrNull(16)
                if (value != null) {
                    out.append(value.toChar())
                    index += 3
                    continue
                }
            }
            out.append(character)
            index += 1
        }
        return out.toString()
    }
}
