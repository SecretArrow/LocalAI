package com.localai.runtime.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * QR code helpers. The connect payload deliberately contains no secret: the client scans
 * the endpoint and then authenticates with its own paired/API token (spec §25).
 */
object QrCodes {

    /** Renders [content] as a QR code bitmap, or null when it cannot be encoded. */
    fun generate(content: String, size: Int = 512): Bitmap? {
        if (content.isEmpty()) return null
        val dimension = size.coerceIn(128, 2048)
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            val matrix: BitMatrix = QRCodeWriter()
                .encode(content, BarcodeFormat.QR_CODE, dimension, dimension, hints)
            val pixels = IntArray(dimension * dimension)
            for (y in 0 until dimension) {
                for (x in 0 until dimension) {
                    pixels[y * dimension + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(pixels, dimension, dimension, Bitmap.Config.ARGB_8888)
        } catch (e: WriterException) {
            null
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * JSON payload for the "connect to this server" QR code: {"host","port","tls","auth"}.
     * NEVER includes a token (spec §25).
     */
    fun apiConnectPayload(host: String, port: Int, tls: Boolean, auth: String = "none"): String =
        buildJsonObject {
            put("host", host)
            put("port", port)
            put("tls", tls)
            put("auth", auth)
        }.toString()
}
