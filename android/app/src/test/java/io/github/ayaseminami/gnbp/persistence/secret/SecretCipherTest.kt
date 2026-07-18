package io.github.ayaseminami.gnbp.persistence.secret

import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SecretCipherTest {
    @Test
    fun `encrypted API key survives cipher recreation without exposing plaintext`() {
        val provider = SecretKeyProvider {
            SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "AES")
        }
        val firstCipher = AesGcmSecretCipher(provider)

        val first = firstCipher.encrypt("sentinel-api-key")
        val second = firstCipher.encrypt("sentinel-api-key")
        val reopenedCipher = AesGcmSecretCipher(provider)

        assertFalse(first.iv.contentEquals(second.iv))
        assertFalse(first.ciphertext.containsSubsequence("sentinel-api-key".encodeToByteArray()))
        val decrypted = reopenedCipher.decrypt(first)
        assertEquals(SecretDecryptionResult.Success("sentinel-api-key"), decrypted)
        assertFalse(decrypted.toString().contains("sentinel-api-key"))
        assertFalse(first.toString().contains("sentinel-api-key"))
    }

    @Test
    fun `tampered or unsupported encrypted secrets fail without exposing crypto exceptions`() {
        val provider = SecretKeyProvider {
            SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "AES")
        }
        val cipher = AesGcmSecretCipher(provider)
        val encrypted = cipher.encrypt("sentinel-api-key")
        val tamperedBytes = encrypted.ciphertext.apply {
            this[lastIndex] = this[lastIndex].toInt().xor(1).toByte()
        }

        val tamperedResult = cipher.decrypt(
            EncryptedSecret(encrypted.version, encrypted.iv, tamperedBytes),
        )
        val unsupportedResult = cipher.decrypt(
            EncryptedSecret(encrypted.version + 1, encrypted.iv, encrypted.ciphertext),
        )

        assertEquals(SecretDecryptionResult.Unavailable, tamperedResult)
        assertEquals(SecretDecryptionResult.UnsupportedVersion, unsupportedResult)
        assertNotEquals("sentinel-api-key", tamperedResult.toString())
    }
}

private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean =
    candidate.isNotEmpty() && indices.any { start ->
        start + candidate.size <= size &&
            candidate.indices.all { offset -> this[start + offset] == candidate[offset] }
    }
