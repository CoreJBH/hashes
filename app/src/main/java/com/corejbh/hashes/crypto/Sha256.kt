package com.corejbh.hashes.crypto

/**
 * SHA-256 in plain Kotlin.
 *
 * The shipping hash loop is the Phase 1 native core; this implementation exists
 * for three jobs that never touch native code: the prompt/UI fallback engine
 * used on the JVM and in previews, the unit tests that verify header and merkle
 * assembly against real mainnet blocks, and address validation (Base58Check).
 *
 * [midstate] and [hash80WithMidstate] mirror the native design: the first 64
 * bytes of a block header never change while a miner walks the nonce, so the
 * compression state after that block is computed once per job and every nonce
 * costs one compression for the header tail plus one for the second hash.
 */
object Sha256 {

    private val K = intArrayOf(
        0x428a2f98.toInt(), 0x71374491.toInt(), 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(), 0x3956c25b.toInt(), 0x59f111f1.toInt(),
        0x923f82a4.toInt(), 0xab1c5ed5.toInt(), 0xd807aa98.toInt(), 0x12835b01.toInt(), 0x243185be.toInt(), 0x550c7dc3.toInt(),
        0x72be5d74.toInt(), 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(), 0xe49b69c1.toInt(), 0xefbe4786.toInt(),
        0x0fc19dc6.toInt(), 0x240ca1cc.toInt(), 0x2de92c6f.toInt(), 0x4a7484aa.toInt(), 0x5cb0a9dc.toInt(), 0x76f988da.toInt(),
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(), 0xc6e00bf3.toInt(), 0xd5a79147.toInt(),
        0x06ca6351.toInt(), 0x14292967.toInt(), 0x27b70a85.toInt(), 0x2e1b2138.toInt(), 0x4d2c6dfc.toInt(), 0x53380d13.toInt(),
        0x650a7354.toInt(), 0x766a0abb.toInt(), 0x81c2c92e.toInt(), 0x92722c85.toInt(), 0xa2bfe8a1.toInt(), 0xa81a664b.toInt(),
        0xc24b8b70.toInt(), 0xc76c51a3.toInt(), 0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070.toInt(),
        0x19a4c116.toInt(), 0x1e376c08.toInt(), 0x2748774c.toInt(), 0x34b0bcb5.toInt(), 0x391c0cb3.toInt(), 0x4ed8aa4a.toInt(),
        0x5b9cca4f.toInt(), 0x682e6ff3.toInt(), 0x748f82ee.toInt(), 0x78a5636f.toInt(), 0x84c87814.toInt(), 0x8cc70208.toInt(),
        0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt()
    )

    private val INITIAL = intArrayOf(
        0x6a09e667.toInt(), 0xbb67ae85.toInt(), 0x3c6ef372.toInt(), 0xa54ff53a.toInt(),
        0x510e527f.toInt(), 0x9b05688c.toInt(), 0x1f83d9ab.toInt(), 0x5be0cd19.toInt()
    )

    fun hash(data: ByteArray): ByteArray {
        val state = INITIAL.copyOf()
        val fullBlocks = data.size / 64
        for (block in 0 until fullBlocks) {
            compress(state, data, block * 64)
        }
        val rest = data.size - fullBlocks * 64
        val tail = ByteArray(if (rest < 56) 64 else 128)
        System.arraycopy(data, fullBlocks * 64, tail, 0, rest)
        tail[rest] = 0x80.toByte()
        val bitLength = data.size.toLong() * 8
        for (index in 0 until 8) {
            tail[tail.size - 1 - index] = (bitLength ushr (8 * index)).toByte()
        }
        for (block in 0 until tail.size / 64) {
            compress(state, tail, block * 64)
        }
        return toBytes(state)
    }

    fun doubleHash(data: ByteArray): ByteArray = hash(hash(data))

    /**
     * State after hashing exactly the first 64 bytes of an 80-byte block header.
     */
    fun midstate(first64: ByteArray): IntArray {
        require(first64.size == 64) { "a header midstate covers 64 bytes" }
        val state = INITIAL.copyOf()
        compress(state, first64, 0)
        return state
    }

    /**
     * Double SHA-256 of a header whose first 64 bytes produced [state] and whose
     * last 16 bytes are [tail] (12 bytes of header time/bits and the 4-byte nonce).
     * Returns the 32-byte digest in internal (little-endian) order.
     */
    fun hash80WithMidstate(state: IntArray, tail: ByteArray): ByteArray {
        require(tail.size == 16) { "the nonce block is 16 bytes" }
        val block = ByteArray(64)
        System.arraycopy(tail, 0, block, 0, 16)
        block[16] = 0x80.toByte()
        // Message length is 80 bytes = 640 bits.
        block[62] = 0x02
        block[63] = 0x80.toByte()
        val first = state.copyOf()
        compress(first, block, 0)
        val firstHash = toBytes(first)
        return hash(firstHash)
    }

    /**
     * Allocation-free variant of [hash80WithMidstate] for the prompt fallback
     * engine: the caller owns every buffer, so the nonce loop produces no
     * garbage. `stateScratch` is 8 ints, `blockScratch` 128 bytes, `out` 32.
     */
    fun doubleHash80(
        midstate: IntArray,
        tail: ByteArray,
        stateScratch: IntArray,
        blockScratch: ByteArray,
        out: ByteArray,
    ) {
        require(midstate.size == 8 && stateScratch.size >= 8) { "SHA-256 state is 8 ints" }
        require(tail.size == 16) { "the nonce block is 16 bytes" }
        require(blockScratch.size >= 128) { "the block scratch covers two compressions" }
        require(out.size >= 32) { "the digest is 32 bytes" }

        for (index in 0 until 64) blockScratch[index] = 0
        System.arraycopy(tail, 0, blockScratch, 0, 16)
        blockScratch[16] = 0x80.toByte()
        blockScratch[62] = 0x02
        blockScratch[63] = 0x80.toByte()
        System.arraycopy(midstate, 0, stateScratch, 0, 8)
        compress(stateScratch, blockScratch, 0)
        writeState(stateScratch, out)

        for (index in 0 until 64) blockScratch[index] = 0
        System.arraycopy(out, 0, blockScratch, 0, 32)
        blockScratch[32] = 0x80.toByte()
        // Second hash length: 32 bytes = 256 bits.
        blockScratch[62] = 0x01
        blockScratch[63] = 0x00
        System.arraycopy(INITIAL, 0, stateScratch, 0, 8)
        compress(stateScratch, blockScratch, 0)
        writeState(stateScratch, out)
    }

    private fun compress(state: IntArray, block: ByteArray, offset: Int) {
        val schedule = IntArray(64)
        for (index in 0 until 16) {
            val position = offset + index * 4
            schedule[index] = ((block[position].toInt() and 0xff) shl 24) or
                ((block[position + 1].toInt() and 0xff) shl 16) or
                ((block[position + 2].toInt() and 0xff) shl 8) or
                (block[position + 3].toInt() and 0xff)
        }
        for (index in 16 until 64) {
            val s0 = schedule[index - 15].rotateRight(7) xor
                schedule[index - 15].rotateRight(18) xor
                (schedule[index - 15] ushr 3)
            val s1 = schedule[index - 2].rotateRight(17) xor
                schedule[index - 2].rotateRight(19) xor
                (schedule[index - 2] ushr 10)
            schedule[index] = schedule[index - 16] + s0 + schedule[index - 7] + s1
        }

        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]

        for (index in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val choice = (e and f) xor (e.inv() and g)
            val temp1 = h + s1 + choice + K[index] + schedule[index]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val majority = (a and b) xor (a and c) xor (b and c)
            val temp2 = s0 + majority
            h = g
            g = f
            f = e
            e = d + temp1
            d = c
            c = b
            b = a
            a = temp1 + temp2
        }

        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
        state[4] += e
        state[5] += f
        state[6] += g
        state[7] += h
    }

    private fun Int.rotateRight(bits: Int): Int = (this ushr bits) or (this shl (32 - bits))

    private fun toBytes(state: IntArray): ByteArray {
        val out = ByteArray(32)
        writeState(state, out)
        return out
    }

    private fun writeState(state: IntArray, out: ByteArray) {
        for (word in 0 until 8) {
            out[word * 4] = (state[word] ushr 24).toByte()
            out[word * 4 + 1] = (state[word] ushr 16).toByte()
            out[word * 4 + 2] = (state[word] ushr 8).toByte()
            out[word * 4 + 3] = state[word].toByte()
        }
    }
}
