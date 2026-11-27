package com.corejbh.hashes.network

/**
 * Reads the block height a solo pool put into the coinbase it handed out.
 *
 * Since BIP34 every coinbase scriptSig begins with the height as a minimal push,
 * and the stratum coinbase template carries that scriptSig, so the terminal can
 * show the block being mined without asking a third-party API. An older or
 * unusual template that does not begin with a plausible height returns null, and
 * the UI shows no height rather than a wrong number.
 */
object CoinbaseHeight {

    /** Above any height Bitcoin will plausibly have while this app exists. */
    const val MAX_PLAUSIBLE_HEIGHT = 10_000_000L

    fun parse(coinbase: ByteArray): Long? {
        var offset = 4 // version
        if (coinbase.size < offset + 2) return null
        // Segwit marker and flag (0x00 0x01) precede the input count in a
        // segwit coinbase; a non-segwit one starts with the input count.
        if (coinbase[offset].toInt() == 0 && coinbase[offset + 1].toInt() == 1) {
            offset += 2
        }
        val inputCount = readVarInt(coinbase, offset) ?: return null
        offset += inputCount.second
        if (inputCount.first != 1L) return null

        if (coinbase.size < offset + 36 + 1) return null
        offset += 32 + 4 // previous output: null txid and index 0xffffffff
        val scriptLength = readVarInt(coinbase, offset) ?: return null
        offset += scriptLength.second
        val length = scriptLength.first.toInt()
        if (length <= 0 || coinbase.size < offset + length) return null

        val push = coinbase[offset].toInt() and 0xff
        if (push !in 1..4) return null
        if (length < push + 1) return null
        var height = 0L
        for (index in 0 until push) {
            height = height or ((coinbase[offset + 1 + index].toLong() and 0xff) shl (8 * index))
        }
        if (height <= 0 || height > MAX_PLAUSIBLE_HEIGHT) return null
        return height
    }

    private fun readVarInt(data: ByteArray, offset: Int): Pair<Long, Int>? {
        if (offset >= data.size) return null
        val first = data[offset].toInt() and 0xff
        return when {
            first < 0xfd -> first.toLong() to 1
            first == 0xfd -> {
                if (data.size < offset + 3) return null
                ((data[offset + 1].toLong() and 0xff) or ((data[offset + 2].toLong() and 0xff) shl 8)) to 3
            }
            first == 0xfe -> {
                if (data.size < offset + 5) return null
                var value = 0L
                for (index in 0 until 4) {
                    value = value or ((data[offset + 1 + index].toLong() and 0xff) shl (8 * index))
                }
                value to 5
            }
            else -> null
        }
    }
}
