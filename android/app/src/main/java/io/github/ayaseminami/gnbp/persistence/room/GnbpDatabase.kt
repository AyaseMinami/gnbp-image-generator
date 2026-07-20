package io.github.ayaseminami.gnbp.persistence.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProfileEntity::class, PromptEntity::class, GenerationTaskEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class GnbpDatabase : RoomDatabase() {
    internal abstract fun profileDao(): ProfileDao

    internal abstract fun promptDao(): PromptDao

    internal abstract fun taskDao(): GenerationTaskDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE profiles ADD COLUMN security_mode TEXT NOT NULL DEFAULT 'VERIFIED_TLS'",
                )
                db.execSQL(
                    "ALTER TABLE profiles ADD COLUMN local_network_mode TEXT NOT NULL " +
                        "DEFAULT 'INTERNET_OR_LOOPBACK_ONLY'",
                )
                db.execSQL(
                    "ALTER TABLE profiles ADD COLUMN transport_policy_revision INTEGER NOT NULL DEFAULT 1",
                )
                db.execSQL(
                    "ALTER TABLE profiles ADD COLUMN custom_ca_certificates_json TEXT NOT NULL DEFAULT '[]'",
                )
                db.execSQL(
                    "ALTER TABLE profiles ADD COLUMN spki_pins_json TEXT NOT NULL DEFAULT '[]'",
                )
                db.execSQL("ALTER TABLE profiles ADD COLUMN pinned_certificate BLOB")
                db.execSQL(
                    "ALTER TABLE profiles ADD COLUMN allow_hostname_mismatch INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL("ALTER TABLE profiles ADD COLUMN ack_profile_id TEXT")
                db.execSQL("ALTER TABLE profiles ADD COLUMN ack_scheme TEXT")
                db.execSQL("ALTER TABLE profiles ADD COLUMN ack_host TEXT")
                db.execSQL("ALTER TABLE profiles ADD COLUMN ack_port INTEGER")
                db.execSQL("ALTER TABLE profiles ADD COLUMN ack_mode TEXT")
                db.execSQL("ALTER TABLE profiles ADD COLUMN ack_policy_revision INTEGER")
                db.execSQL("ALTER TABLE profiles ADD COLUMN ack_accepted_at INTEGER")
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS generation_tasks (" +
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
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE generation_tasks SET source_task_id = NULL " +
                        "WHERE source_task_id IS NOT NULL AND EXISTS (" +
                        "SELECT 1 FROM generation_tasks AS keeper " +
                        "WHERE keeper.source_task_id = generation_tasks.source_task_id AND (" +
                        "keeper.created_at < generation_tasks.created_at OR (" +
                        "keeper.created_at = generation_tasks.created_at AND " +
                        "keeper.id < generation_tasks.id)))",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_generation_tasks_source_task_id` ON " +
                        "`generation_tasks` (`source_task_id`)",
                )
            }
        }

        internal val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
        )
    }
}
