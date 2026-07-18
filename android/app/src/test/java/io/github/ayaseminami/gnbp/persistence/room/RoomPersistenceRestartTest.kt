package io.github.ayaseminami.gnbp.persistence.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.ayaseminami.gnbp.persistence.profile.ProfileLoadResult
import io.github.ayaseminami.gnbp.persistence.profile.ProfileSaveResult
import io.github.ayaseminami.gnbp.persistence.profile.ProviderKind
import io.github.ayaseminami.gnbp.persistence.profile.ProviderProfile
import io.github.ayaseminami.gnbp.persistence.profile.RoomProfileRepository
import io.github.ayaseminami.gnbp.persistence.secret.AesGcmSecretCipher
import io.github.ayaseminami.gnbp.persistence.secret.SecretKeyProvider
import io.github.ayaseminami.gnbp.provider.ApiKey
import io.github.ayaseminami.gnbp.provider.transport.ProfileId
import io.github.ayaseminami.gnbp.provider.transport.ProviderEndpoint
import io.github.ayaseminami.gnbp.provider.transport.TransportBinding
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomPersistenceRestartTest {
    @Test
    fun `profile secret survives database restart and rejects the wrong key`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "gnbp-restart-${System.nanoTime()}.db"
        val profileId = ProfileId("restart-profile")
        val profile = ProviderProfile(
            id = profileId,
            name = "Restart profile",
            providerKind = ProviderKind.Gemini,
            binding = TransportBinding(
                profileId = profileId,
                endpoint = ProviderEndpoint.parse("https://restart.example/base/"),
            ),
            apiKey = ApiKey("restart-api-key"),
            model = "restart-model",
            sortOrder = 0,
        )
        val stableCipher = cipherWithSeed(1)
        openDatabase(context, databaseName).let { database ->
            val repository = RoomProfileRepository(database.profileDao(), stableCipher)
            try {
                assertEquals(ProfileSaveResult.Saved, repository.saveProfile(profile))
            } finally {
                database.close()
            }
        }

        openDatabase(context, databaseName).let { database ->
            try {
                val loaded = RoomProfileRepository(database.profileDao(), cipherWithSeed(1))
                    .loadProfile(profileId)
                assertTrue(loaded is ProfileLoadResult.Found)
                assertEquals(
                    "restart-api-key",
                    (loaded as ProfileLoadResult.Found).profile.apiKey.reveal(),
                )
            } finally {
                database.close()
            }
        }

        openDatabase(context, databaseName).let { database ->
            try {
                val unavailable = RoomProfileRepository(database.profileDao(), cipherWithSeed(2))
                    .loadProfile(profileId)
                assertEquals(ProfileLoadResult.SecretUnavailable, unavailable)
            } finally {
                database.close()
            }
        }
        context.deleteDatabase(databaseName)
    }

    private fun openDatabase(context: Context, databaseName: String): GnbpDatabase =
        Room.databaseBuilder(context, GnbpDatabase::class.java, databaseName)
            .addMigrations(GnbpDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

    private fun cipherWithSeed(seed: Int) = AesGcmSecretCipher(
        SecretKeyProvider {
            SecretKeySpec(ByteArray(32) { index -> (index + seed).toByte() }, "AES")
        },
    )
}
