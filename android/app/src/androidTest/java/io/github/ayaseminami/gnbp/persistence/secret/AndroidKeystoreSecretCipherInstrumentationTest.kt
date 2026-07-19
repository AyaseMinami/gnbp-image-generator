package io.github.ayaseminami.gnbp.persistence.secret

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreSecretCipherInstrumentationTest {
    @Test
    fun aesGcmRoundTripUsesAndroidKeystoreGeneratedIv() {
        val alias = "gnbp.instrumentation-keystore-test"
        try {
            val cipher = AesGcmSecretCipher(AndroidKeystoreSecretKeyProvider(alias))
            val encrypted = cipher.encrypt("instrumentation-sentinel")

            assertEquals(12, encrypted.iv.size)
            assertEquals(
                SecretDecryptionResult.Success("instrumentation-sentinel"),
                cipher.decrypt(encrypted),
            )
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
    }
}
