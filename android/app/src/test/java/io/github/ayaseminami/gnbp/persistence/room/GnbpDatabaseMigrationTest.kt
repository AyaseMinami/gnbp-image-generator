package io.github.ayaseminami.gnbp.persistence.room

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.github.ayaseminami.gnbp.persistence.profile.ProfileLoadResult
import io.github.ayaseminami.gnbp.persistence.profile.RoomProfileRepository
import io.github.ayaseminami.gnbp.persistence.prompt.PromptId
import io.github.ayaseminami.gnbp.persistence.prompt.RoomPromptRepository
import io.github.ayaseminami.gnbp.persistence.secret.AesGcmSecretCipher
import io.github.ayaseminami.gnbp.persistence.secret.SecretKeyProvider
import io.github.ayaseminami.gnbp.provider.transport.LocalNetworkMode
import io.github.ayaseminami.gnbp.provider.transport.ProfileId
import io.github.ayaseminami.gnbp.provider.transport.TransportSecurityMode
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
class GnbpDatabaseMigrationTest {
    @Test
    fun `migration fills strict transport defaults without replacing legacy data`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "gnbp-migration-${System.nanoTime()}.db"
        val cipher = testCipher()
        val encrypted = cipher.encrypt("legacy-api-key")
        createVersionOneDatabase(context, databaseName, encrypted.version, encrypted.iv, encrypted.ciphertext)

        val database = Room.databaseBuilder(context, GnbpDatabase::class.java, databaseName)
            .addMigrations(GnbpDatabase.MIGRATION_1_2, GnbpDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        val profile = RoomProfileRepository(database.profileDao(), cipher)
            .loadProfile(ProfileId("legacy-profile"))
        val prompt = RoomPromptRepository(database.promptDao()).loadPrompt(PromptId("legacy-prompt"))

        assertTrue(profile is ProfileLoadResult.Found)
        profile as ProfileLoadResult.Found
        assertEquals("Legacy profile", profile.profile.name)
        assertEquals("legacy-api-key", profile.profile.apiKey.reveal())
        assertEquals(TransportSecurityMode.VerifiedTls, profile.profile.binding.securityMode)
        assertEquals(
            LocalNetworkMode.InternetOrLoopbackOnly,
            profile.profile.binding.localNetworkMode,
        )
        assertEquals("Legacy prompt", prompt?.name)
        assertEquals("preserved content", prompt?.content)
        assertTrue(database.taskDao().findAll().isEmpty())

        database.close()
        context.deleteDatabase(databaseName)
    }

    private fun createVersionOneDatabase(
        context: Context,
        databaseName: String,
        secretVersion: Int,
        iv: ByteArray,
        ciphertext: ByteArray,
    ) {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE profiles (" +
                                "id TEXT NOT NULL PRIMARY KEY, " +
                                "name TEXT NOT NULL, " +
                                "provider_kind TEXT NOT NULL, " +
                                "endpoint_url TEXT NOT NULL, " +
                                "model TEXT NOT NULL, " +
                                "secret_version INTEGER NOT NULL, " +
                                "api_key_iv BLOB NOT NULL, " +
                                "api_key_ciphertext BLOB NOT NULL, " +
                                "sort_order INTEGER NOT NULL)",
                        )
                        db.execSQL(
                            "CREATE TABLE prompts (" +
                                "id TEXT NOT NULL PRIMARY KEY, " +
                                "name TEXT NOT NULL, " +
                                "content TEXT NOT NULL, " +
                                "sort_order INTEGER NOT NULL)",
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                },
            )
            .build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper ->
            val database = helper.writableDatabase
            database.execSQL(
                "INSERT INTO profiles (" +
                    "id, name, provider_kind, endpoint_url, model, secret_version, " +
                    "api_key_iv, api_key_ciphertext, sort_order" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    "legacy-profile",
                    "Legacy profile",
                    "Gemini",
                    "https://legacy.example/base/",
                    "legacy-model",
                    secretVersion,
                    iv,
                    ciphertext,
                    7,
                ),
            )
            database.execSQL(
                "INSERT INTO prompts (id, name, content, sort_order) VALUES (?, ?, ?, ?)",
                arrayOf<Any?>("legacy-prompt", "Legacy prompt", "preserved content", 3),
            )
        }
    }
}

private fun testCipher() = AesGcmSecretCipher(
    SecretKeyProvider {
        SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "AES")
    },
)
