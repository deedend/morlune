// SPDX-License-Identifier: GPL-3.0-or-later
package org.morlune.engine

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Builds the signed token Apple WeatherKit wants (a JWT signed with ES256).
 * The user supplies their own Apple developer credentials: team id, service id, key id and the .p8 private key.
 */
object AppleJwt {
    fun create(teamId: String, serviceId: String, keyId: String, privateKeyPem: String, nowSeconds: Long, ttlSeconds: Long = 3600): String {
        if (teamId.isBlank() || serviceId.isBlank() || keyId.isBlank()) {
            throw ProviderException("Apple WeatherKit needs a Team ID, Service ID and Key ID in the settings")
        }
        val header = "{\"alg\":\"ES256\",\"kid\":${q(keyId.trim())},\"id\":${q(teamId.trim() + "." + serviceId.trim())}}"
        val payload = "{\"iss\":${q(teamId.trim())},\"iat\":$nowSeconds,\"exp\":${nowSeconds + ttlSeconds},\"sub\":${q(serviceId.trim())}}"
        val signingInput = b64(header.toByteArray(Charsets.UTF_8)) + "." + b64(payload.toByteArray(Charsets.UTF_8))
        val der = try {
            Signature.getInstance("SHA256withECDSA").run {
                initSign(parsePrivateKey(privateKeyPem))
                update(signingInput.toByteArray(Charsets.US_ASCII))
                sign()
            }
        } catch (e: ProviderException) {
            throw e
        } catch (e: java.security.GeneralSecurityException) {
            throw ProviderException("could not sign the Apple token: ${e.message}", e)
        }
        return signingInput + "." + b64(derToRaw(der, 32))
    }

    /** Reads the text of an AuthKey_XXXX.p8 file (PKCS#8, EC P-256). */
    fun parsePrivateKey(pem: String): PrivateKey {
        val body = pem.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("-----") }.joinToString("")
        if (body.isEmpty()) throw ProviderException("the Apple private key (.p8) is empty or missing")
        return try {
            KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(body)))
        } catch (e: Exception) {
            throw ProviderException("the Apple private key (.p8) could not be read: ${e.message}", e)
        }
    }

    /** Java gives ECDSA signatures as DER (SEQUENCE of two INTEGERs); JWT wants the two numbers side by side, [size] bytes each. */
    fun derToRaw(der: ByteArray, size: Int): ByteArray {
        var p = 0
        fun length(): Int {
            val b = der[p++].toInt() and 0xFF
            if (b < 0x80) return b
            var l = 0
            repeat(b and 0x7F) { l = (l shl 8) or (der[p++].toInt() and 0xFF) }
            return l
        }
        fun integer(): ByteArray {
            require(der[p++].toInt() == 0x02) { "not a DER integer" }
            val l = length()
            return der.copyOfRange(p, p + l).also { p += l }
        }
        require(der[p++].toInt() == 0x30) { "not a DER sequence" }
        length()
        val out = ByteArray(2 * size)
        for ((index, value) in listOf(integer(), integer()).withIndex()) {
            var start = 0
            while (start < value.size - 1 && value[start] == 0.toByte()) start++
            val n = value.size - start
            require(n <= size) { "signature number too large" }
            System.arraycopy(value, start, out, index * size + size - n, n)
        }
        return out
    }

    private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
