package com.corejbh.hashes.crypto

/**
 * Checks a mainnet Bitcoin payout address before it is ever sent to a pool.
 *
 * The app needs an address only to receive a block reward, so the validation is
 * deliberately strict: the checksum must verify, the address must belong to the
 * mainnet and it must be one of the forms a solo pool can pay to. A wrong
 * checksum would either be rejected by the pool or, worse, be a typo that pays
 * someone else, and a testnet address mined on mainnet pays nobody.
 */
object BitcoinAddressValidator {

    private const val MAINNET_HRP = "bc"
    private const val VERSION_P2PKH = 0x00
    private const val VERSION_P2SH = 0x05
    private const val VERSION_TESTNET_P2PKH = 0x6f
    private const val VERSION_TESTNET_P2SH = 0xc4

    enum class AddressType { P2PKH, P2SH, P2WPKH, P2WSH, P2TR, WITNESS_V1_PLUS }

    enum class Error {
        EMPTY,
        UNKNOWN_FORMAT,
        TESTNET,
        UNSUPPORTED_NETWORK,
        BAD_BASE58_CHECKSUM,
        BAD_ADDRESS_LENGTH,
        BAD_VERSION_BYTE,
        MIXED_CASE,
        BAD_BECH32_CHECKSUM,
        BAD_WITNESS_VERSION,
        BAD_PROGRAM_LENGTH,
        WRONG_CHECKSUM_TYPE,
        BAD_PADDING,
    }

    sealed interface Result {
        data class Valid(val address: String, val type: AddressType) : Result
        data class Invalid(val error: Error) : Result
    }

    fun validate(input: String): Result {
        val address = input.trim()
        if (address.isEmpty()) return Result.Invalid(Error.EMPTY)

        val lower = address.lowercase()
        return when {
            lower.startsWith("bc1") || lower.startsWith("tb1") || lower.startsWith("bcrt1") ->
                validateBech32(address, lower)
            address[0] == '1' || address[0] == '3' ->
                validateBase58(address)
            address[0] == 'm' || address[0] == 'n' || address[0] == '2' ->
                Result.Invalid(Error.TESTNET)
            else -> Result.Invalid(Error.UNKNOWN_FORMAT)
        }
    }

    fun isValid(input: String): Boolean = validate(input) is Result.Valid

    private fun validateBase58(address: String): Result {
        val payload = Base58.decodeChecked(address)
            ?: return Result.Invalid(Error.BAD_BASE58_CHECKSUM)
        if (payload.size != 21) return Result.Invalid(Error.BAD_ADDRESS_LENGTH)
        return when (payload[0].toInt() and 0xff) {
            VERSION_P2PKH -> Result.Valid(address, AddressType.P2PKH)
            VERSION_P2SH -> Result.Valid(address, AddressType.P2SH)
            VERSION_TESTNET_P2PKH, VERSION_TESTNET_P2SH -> Result.Invalid(Error.TESTNET)
            else -> Result.Invalid(Error.BAD_VERSION_BYTE)
        }
    }

    private fun validateBech32(address: String, lower: String): Result {
        if (lower.startsWith("tb1") || lower.startsWith("bcrt1")) {
            return Result.Invalid(Error.TESTNET)
        }
        val decoded = when (val result = Bech32.decode(address)) {
            is Bech32.Result.Ok -> result.decoded
            is Bech32.Result.Failed ->
                return Result.Invalid(
                    if (result.reason == "mixed case") Error.MIXED_CASE else Error.BAD_BECH32_CHECKSUM
                )
        }
        if (decoded.hrp != MAINNET_HRP) return Result.Invalid(Error.UNSUPPORTED_NETWORK)
        if (decoded.data.isEmpty()) return Result.Invalid(Error.BAD_PROGRAM_LENGTH)

        val version = decoded.data[0]
        if (version > 16) return Result.Invalid(Error.BAD_WITNESS_VERSION)
        val program = Bech32.toBytes(decoded.data.copyOfRange(1, decoded.data.size))
            ?: return Result.Invalid(Error.BAD_PADDING)

        if (version == 0) {
            if (decoded.checksum != Bech32.CHECKSUM_BECH32) {
                return Result.Invalid(Error.WRONG_CHECKSUM_TYPE)
            }
            return when (program.size) {
                20 -> Result.Valid(address, AddressType.P2WPKH)
                32 -> Result.Valid(address, AddressType.P2WSH)
                else -> Result.Invalid(Error.BAD_PROGRAM_LENGTH)
            }
        }

        if (decoded.checksum != Bech32.CHECKSUM_BECH32M) {
            return Result.Invalid(Error.WRONG_CHECKSUM_TYPE)
        }
        if (program.size < 2 || program.size > 40) {
            return Result.Invalid(Error.BAD_PROGRAM_LENGTH)
        }
        return if (version == 1 && program.size == 32) {
            Result.Valid(address, AddressType.P2TR)
        } else {
            Result.Valid(address, AddressType.WITNESS_V1_PLUS)
        }
    }
}
