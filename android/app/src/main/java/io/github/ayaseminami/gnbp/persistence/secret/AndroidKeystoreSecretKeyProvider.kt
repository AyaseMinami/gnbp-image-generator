package io.github.ayaseminami.gnbp.persistence.secret

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AndroidKeystoreSecretKeyProvider(
    private val alias: String = DEFAULT_ALIAS,
) : SecretKeyProvider {
    private val lock = Any()

    override fun getOrCreate(): SecretKey = synchronized(lock) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey) ?: createKey()
    }

    private fun createKey(): SecretKey {
        val specification = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(specification)
            generateKey()
        }
    }

    override fun toString(): String = "AndroidKeystoreSecretKeyProvider(alias=[REDACTED])"

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "gnbp.profile-api-key.v1"
    }
}
