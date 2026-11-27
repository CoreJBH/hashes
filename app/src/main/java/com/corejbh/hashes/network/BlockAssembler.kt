package com.corejbh.hashes.network

import com.corejbh.hashes.crypto.Hex
import com.corejbh.hashes.crypto.Sha256

/**
 * Assembles the bytes a hash engine works on.
 *
 * Stratum V1 line formats are not the Bitcoin serialisation, and every field has
 * its own byte order. This is the one place that order is decided, and the unit
 * tests check it against real mainnet blocks: the coinbase transaction is
 * rebuilt byte for byte, its txid must match the block's coinbase txid, the
 * merkle root must match the header field, and the reassembled header must hash
 * to the block hash.
 *
 * Byte orders, for the reader:
 *  - version, ntime and nbits arrive as display big-endian hex and go into the
 *    header little-endian (reversed).
 *  - the previous block hash arrives word-swapped (each 4-byte word reversed,
 *    word order kept) and is reversed per word again for the header.
 *  - merkle branches and the merkle root are raw internal-order bytes already;
 *    they are concatenated and hashed without any reversal.
 *  - the nonce is a 32-bit number stored little-endian.
 */
object BlockAssembler {

    const val HEADER_SIZE = 80
    const val HEADER_PREFIX_SIZE = 76

    /** coinb1 + extranonce1 + extranonce2 + coinb2, the full coinbase transaction. */
    fun coinbase(coinbase1: String, extranonce1: String, extranonce2: String, coinbase2: String): ByteArray {
        require(Hex.isHex(coinbase1) && Hex.isHex(extranonce1) && Hex.isHex(extranonce2) && Hex.isHex(coinbase2)) {
            "coinbase parts must be hex"
        }
        return Hex.decode(coinbase1) + Hex.decode(extranonce1) + Hex.decode(extranonce2) + Hex.decode(coinbase2)
    }

    /** The coinbase txid a coinbase transaction hashes to, in internal order. */
    fun coinbaseTxId(coinbase: ByteArray): ByteArray = Sha256.doubleHash(coinbase)

    /**
     * Merkle root of a coinbase txid and the job's branch list. The result is in
     * internal order, which is the order the header stores it in.
     */
    fun merkleRoot(coinbaseTxId: ByteArray, branches: List<String>): ByteArray {
        var root = coinbaseTxId.copyOf()
        for (branch in branches) {
            val sibling = Hex.decode(branch)
            require(sibling.size == 32) { "a merkle branch is 32 bytes" }
            root = Sha256.doubleHash(root + sibling)
        }
        return root
    }

    /**
     * First 76 bytes of the header: everything but the nonce.
     */
    fun headerPrefix(job: MiningJob, extranonce1: String, extranonce2: String): ByteArray {
        val coinbase = coinbase(job.coinbase1, extranonce1, extranonce2, job.coinbase2)
        val root = merkleRoot(coinbaseTxId(coinbase), job.merkleBranches)
        val prefix = ByteArray(HEADER_PREFIX_SIZE)
        System.arraycopy(Hex.decode(Hex.littleEndian32(job.version)), 0, prefix, 0, 4)
        System.arraycopy(Hex.decode(Hex.swapWords32(job.previousHash)), 0, prefix, 4, 32)
        System.arraycopy(root, 0, prefix, 36, 32)
        System.arraycopy(Hex.decode(Hex.littleEndian32(job.networkTime)), 0, prefix, 68, 4)
        System.arraycopy(Hex.decode(Hex.littleEndian32(job.networkBits)), 0, prefix, 72, 4)
        return prefix
    }

    /** Appends the nonce little-endian to a 76-byte prefix. */
    fun header(prefix: ByteArray, nonce: Long): ByteArray {
        require(prefix.size == HEADER_PREFIX_SIZE) { "a header prefix is 76 bytes" }
        val header = prefix.copyOf(HEADER_SIZE)
        writeNonce(header, HEADER_PREFIX_SIZE, nonce)
        return header
    }

    /** Writes the four nonce bytes in place at [offset]. */
    fun writeNonce(target: ByteArray, offset: Int, nonce: Long) {
        val value = nonce and 0xffffffffL
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
        target[offset + 3] = (value ushr 24).toByte()
    }

    /** The double SHA-256 of an 80-byte header, in internal order. */
    fun hashHeader(header: ByteArray): ByteArray {
        require(header.size == HEADER_SIZE) { "a block header is 80 bytes" }
        return Sha256.doubleHash(header)
    }

    /** extranonce2 padded to the size the pool announced, as hex. */
    fun extranonce2Hex(counter: Long, sizeBytes: Int): String {
        require(sizeBytes in 1..32) { "extranonce2 size is 1 to 32 bytes" }
        val digits = sizeBytes * 2
        val text = java.lang.Long.toHexString(counter and 0xffffffffL)
        return text.padStart(digits, '0').takeLast(digits)
    }
}
