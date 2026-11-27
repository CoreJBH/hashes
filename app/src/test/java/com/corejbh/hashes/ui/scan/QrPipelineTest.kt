package com.corejbh.hashes.ui.scan

import com.corejbh.hashes.TestVectors
import com.corejbh.hashes.crypto.BitcoinAddressValidator
import com.corejbh.hashes.crypto.BitcoinUri
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End to end over the scan path a camera feeds: a real QR code is encoded with
 * ZXing's writer, turned into the luminance buffer a frame would produce, decoded
 * again, and the decoded text goes through the same URI parsing and address
 * validation the settings sheet uses.
 */
class QrPipelineTest {

    private fun encode(text: String, size: Int = 360): BitMatrix =
        QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8"),
        )

    private fun luminance(matrix: BitMatrix): ByteArray {
        val out = ByteArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                out[y * matrix.width + x] = if (matrix.get(x, y)) 0 else 0xFF.toByte()
            }
        }
        return out
    }

    @Test
    fun `a bare address qr decodes and validates`() {
        val text = TestVectors.ADDRESS_P2WPKH
        val matrix = encode(text)
        val decoded = QrDecoder().decodeLuminance(luminance(matrix), matrix.width, matrix.height)
        assertEquals(text, decoded)
        assertEquals(text, BitcoinUri.extractAddress(decoded!!))
        assertTrue(BitcoinAddressValidator.isValid(decoded))
    }

    @Test
    fun `a bip21 qr decodes to its address only`() {
        val uri = "bitcoin:${TestVectors.ADDRESS_P2TR}?amount=0.001&label=Hash%20Reward"
        val matrix = encode(uri)
        val decoded = QrDecoder().decodeLuminance(luminance(matrix), matrix.width, matrix.height)
        assertEquals(uri, decoded)
        val address = BitcoinUri.extractAddress(decoded!!)
        assertEquals(TestVectors.ADDRESS_P2TR, address)
        assertTrue(BitcoinAddressValidator.isValid(address!!))
    }

    @Test
    fun `a testnet qr is decoded and then refused`() {
        val uri = "bitcoin:mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn"
        val matrix = encode(uri)
        val decoded = QrDecoder().decodeLuminance(luminance(matrix), matrix.width, matrix.height)
        val address = BitcoinUri.extractAddress(decoded!!)
        val result = BitcoinAddressValidator.validate(address!!)
        assertTrue(result is BitcoinAddressValidator.Result.Invalid)
        assertEquals(
            BitcoinAddressValidator.Error.TESTNET,
            (result as BitcoinAddressValidator.Result.Invalid).error,
        )
    }

    @Test
    fun `an empty frame decodes to nothing`() {
        val size = 240
        assertNull(QrDecoder().decodeLuminance(ByteArray(size * size) { 0xFF.toByte() }, size, size))
    }

    @Test
    fun `a frame of the wrong size is refused`() {
        assertNull(QrDecoder().decodeLuminance(ByteArray(10), 100, 100))
        assertNull(QrDecoder().decodeNv21(ByteArray(10), 0, 0))
    }
}
