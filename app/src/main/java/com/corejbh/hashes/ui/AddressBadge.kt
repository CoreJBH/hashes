package com.corejbh.hashes.ui

import com.corejbh.hashes.crypto.BitcoinAddressValidator

/**
 * Turns a validation result into the badge the terminal prints next to the
 * payout field. Keeping this out of the composable makes the mapping testable and
 * keeps the wording identical wherever an address is shown.
 */
object AddressBadge {

    data class Line(val badge: String, val detail: String)

    fun of(result: BitcoinAddressValidator.Result): Line = when (result) {
        is BitcoinAddressValidator.Result.Valid -> Line(
            badge = "[VALID_MAINNET]",
            detail = result.type.name,
        )

        is BitcoinAddressValidator.Result.Invalid -> when (result.error) {
            BitcoinAddressValidator.Error.EMPTY -> Line("[AWAITING_INPUT]", "TYPE OR SCAN A PAYOUT ADDRESS")
            BitcoinAddressValidator.Error.UNKNOWN_FORMAT -> Line("[INVALID_FORMAT]", "NOT A BITCOIN ADDRESS")
            BitcoinAddressValidator.Error.TESTNET -> Line("[TESTNET_REJECTED]", "MAINNET ONLY")
            BitcoinAddressValidator.Error.UNSUPPORTED_NETWORK -> Line("[UNSUPPORTED_NETWORK]", "MAINNET ONLY")
            BitcoinAddressValidator.Error.BAD_BASE58_CHECKSUM,
            BitcoinAddressValidator.Error.BAD_BECH32_CHECKSUM,
            BitcoinAddressValidator.Error.WRONG_CHECKSUM_TYPE ->
                Line("[INVALID_CHECKSUM]", "THE ADDRESS HAS A TYPO")
            BitcoinAddressValidator.Error.BAD_ADDRESS_LENGTH,
            BitcoinAddressValidator.Error.BAD_PROGRAM_LENGTH ->
                Line("[INVALID_LENGTH]", "WRONG LENGTH FOR THIS ADDRESS TYPE")
            BitcoinAddressValidator.Error.BAD_VERSION_BYTE -> Line("[BAD_VERSION_BYTE]", "NOT A PAYABLE MAINNET SCRIPT")
            BitcoinAddressValidator.Error.MIXED_CASE -> Line("[MIXED_CASE]", "UPPER AND LOWER CASE MIXED")
            BitcoinAddressValidator.Error.BAD_WITNESS_VERSION -> Line("[BAD_WITNESS]", "WITNESS VERSION ABOVE 16")
            BitcoinAddressValidator.Error.BAD_PADDING -> Line("[BAD_PADDING]", "MALFORMED WITNESS PROGRAM")
        }
    }

    fun isValid(result: BitcoinAddressValidator.Result): Boolean =
        result is BitcoinAddressValidator.Result.Valid
}
