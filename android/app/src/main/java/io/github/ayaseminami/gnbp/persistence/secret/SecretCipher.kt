package io.github.ayaseminami.gnbp.persistence.secret

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

fun interface SecretKeyProvider {
    fun getOrCreate(): SecretKey
}

class EncryptedSecret(
    val version: Int,
    iv: ByteArray,
    ciphertext: ByteArray,
) {
    private val ivBytes = iv.copyOf()
    private val ciphertextBytes = ciphertext.copyOf()

    val iv: ByteArray get() = ivBytes.copyOf()
    val ciphertext: ByteArray get() = ciphertextBytes.copyOf()

    override fun toString(): String =
        "EncryptedSecret(version=$version, ivBytes=${ivBytes.size}, ciphertext=[REDACTED])"
}

sealed interface SecretDecryptionResult {
    data class Success(val value: String) : SecretDecryptionResult {
        override fun toString(): String = "Success(value=[REDACTED])"
    }

    data object Unavailable : SecretDecryptionResult

    data object UnsupportedVersion : SecretDecryptionResult
}

interface SecretCipher {
    fun encrypt(plaintext: String): EncryptedSecret

    fun decrypt(secret: EncryptedSecret): SecretDecryptionResult
}

class AesGcmSecretCipher(
    private val keyProvider: SecretKeyProvider,
    private val secureRandom: SecureRandom = SecureRandom(),
) : SecretCipher {
    override fun encrypt(plaintext: String): EncryptedSecret {
        val iv = ByteArray(IV_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.ENCRYPT_MODE,
                keyProvider.getOrCreate(),
                GCMParameterSpec(AUTHENTICATION_TAG_BITS, iv),
            )
        }
        return EncryptedSecret(
            version = CURRENT_VERSION,
            iv = iv,
            ciphertext = cipher.doFinal(plaintext.encodeToByteArray()),
        )
    }

    override fun decrypt(secret: EncryptedSecret): SecretDecryptionResult {
        if (secret.version != CURRENT_VERSION) return SecretDecryptionResult.UnsupportedVersion
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    keyProvider.getOrCreate(),
                    GCMParameterSpec(AUTHENTICATION_TAG_BITS, secret.iv),
                )
            }
            SecretDecryptionResult.Success(cipher.doFinal(secret.ciphertext).decodeToString())
        } catch (_: GeneralSecurityException) {
            SecretDecryptionResult.Unavailable
        } catch (_: IllegalArgumentException) {
            SecretDecryptionResult.Unavailable
        }
    }

    companion object {
        const val CURRENT_VERSION = 1
        private const val IV_BYTES = 12
        private const val AUTHENTICATION_TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
