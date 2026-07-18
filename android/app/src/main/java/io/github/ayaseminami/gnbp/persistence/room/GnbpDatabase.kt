package io.github.ayaseminami.gnbp.persistence.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProfileEntity::class, PromptEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class GnbpDatabase : RoomDatabase() {
    internal abstract fun profileDao(): ProfileDao

    internal abstract fun promptDao(): PromptDao

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
    }
}
