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
    fun `migration backfills successful tasks into generated results exactly once`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "gnbp-result-migration-${System.nanoTime()}.db"
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE generation_tasks (" +
                                "id TEXT NOT NULL PRIMARY KEY, " +
                                "request_json TEXT NOT NULL, " +
                                "status TEXT NOT NULL, " +
                                "created_at INTEGER NOT NULL, " +
                                "started_at INTEGER, " +
                                "finished_at INTEGER, " +
                                "source_task_id TEXT, " +
                                "terminal_reason TEXT, " +
                                "result_asset_id TEXT, " +
                                "result_uri TEXT, " +
                                "result_display_name TEXT, " +
                                "result_mime_type TEXT, " +
                                "result_byte_size INTEGER)",
                        )
                        db.execSQL(
                            "INSERT INTO generation_tasks (" +
                                "id, request_json, status, created_at, finished_at, result_asset_id, " +
                                "result_uri, result_display_name, result_mime_type, result_byte_size" +
                                ") VALUES (?, ?, 'SUCCEEDED', ?, ?, ?, ?, ?, ?, ?)",
                            arrayOf<Any?>(
                                "successful-task",
                                "{\"profileId\":\"profile-one\",\"profileName\":\"Gemini\",\"providerKind\":\"Gemini\",\"model\":\"model\",\"prompt\":\"backfilled prompt\",\"parameters\":{\"kind\":\"GEMINI\",\"aspectRatio\":\"3:4\",\"imageSize\":\"2K\",\"temperature\":0.7},\"references\":[]}",
                                100L,
                                200L,
                                "asset-one",
                                "content://gnbp/asset-one",
                                "asset-one.png",
                                "image/png",
                                3L,
                            ),
                        )
                        db.execSQL(
                            "INSERT INTO generation_tasks (id, request_json, status, created_at) " +
                                "VALUES ('failed-task', '{}', 'FAILED', 300)",
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
            GnbpDatabase.MIGRATION_4_5.migrate(database)
            GnbpDatabase.MIGRATION_4_5.migrate(database)

            database.query(
                "SELECT id, source_task_id, created_at, is_favorite FROM generated_results",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("result-successful-task", cursor.getString(0))
                assertEquals("successful-task", cursor.getString(1))
                assertEquals(200L, cursor.getLong(2))
                assertEquals(0, cursor.getInt(3))
                assertTrue(!cursor.moveToNext())
            }
        }
        assertTrue(context.deleteDatabase(databaseName))
    }

    @Test
    fun `migration fills strict transport defaults without replacing legacy data`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "gnbp-migration-${System.nanoTime()}.db"
        val cipher = testCipher()
        val encrypted = cipher.encrypt("legacy-api-key")
        createVersionOneDatabase(context, databaseName, encrypted.version, encrypted.iv, encrypted.ciphertext)

        val database = Room.databaseBuilder(context, GnbpDatabase::class.java, databaseName)
            .addMigrations(*GnbpDatabase.ALL_MIGRATIONS)
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

    @Test
    fun `migration keeps one direct replacement without deleting duplicate history`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "gnbp-retry-migration-${System.nanoTime()}.db"
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE generation_tasks (" +
                                "id TEXT NOT NULL PRIMARY KEY, " +
                                "request_json TEXT NOT NULL, " +
                                "status TEXT NOT NULL, " +
                                "created_at INTEGER NOT NULL, " +
                                "started_at INTEGER, " +
                                "finished_at INTEGER, " +
                                "source_task_id TEXT, " +
                                "terminal_reason TEXT, " +
                                "result_asset_id TEXT, " +
                                "result_uri TEXT, " +
                                "result_display_name TEXT, " +
                                "result_mime_type TEXT, " +
                                "result_byte_size INTEGER)",
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
            fun insertReplacement(id: String, createdAt: Long) {
                database.execSQL(
                    "INSERT INTO generation_tasks (" +
                        "id, request_json, status, created_at, source_task_id" +
                        ") VALUES (?, ?, ?, ?, ?)",
                    arrayOf<Any?>(id, "{}", "FAILED", createdAt, "failed-source"),
                )
            }
            insertReplacement("later-replacement", 300L)
            insertReplacement("earlier-replacement", 200L)

            GnbpDatabase.MIGRATION_3_4.migrate(database)

            val lineageById = buildMap<String, String?> {
                database.query(
                    "SELECT id, source_task_id FROM generation_tasks ORDER BY id",
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        put(
                            cursor.getString(0),
                            if (cursor.isNull(1)) null else cursor.getString(1),
                        )
                    }
                }
            }
            assertEquals(
                mapOf(
                    "earlier-replacement" to "failed-source",
                    "later-replacement" to null,
                ),
                lineageById,
            )
            assertTrue(
                runCatching { insertReplacement("new-duplicate", 400L) }.isFailure,
            )
        }
        assertTrue(context.deleteDatabase(databaseName))
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
