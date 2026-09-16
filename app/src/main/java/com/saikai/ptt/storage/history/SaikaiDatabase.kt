package com.saikai.ptt.storage.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The communication history.
 *
 * Settings do not live here -- those are DataStore (`.claude/CLAUDE.md`
 * section 22). This database holds one table, and is expected to hold a few
 * more as history grows features; it is not a general store.
 *
 * ### There is no destructive fallback, on purpose
 *
 * `fallbackToDestructiveMigration` is absent and must stay absent. Room's
 * default when a version has no migration is to throw, and that is the
 * behaviour this product wants: the alternative silently deletes every
 * conversation the user has recorded, on an upgrade, with no warning and no way
 * back. A missing migration should be a bug caught in development, not a
 * feature that eats data in the field.
 *
 * The schema is exported to `app/schemas/` and committed, which is what makes
 * writing that migration possible later: it is the record of what version 1
 * actually was.
 */
@Database(
    entities = [CommunicationRecordEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(HistoryConverters::class)
abstract class SaikaiDatabase : RoomDatabase() {

    abstract fun records(): CommunicationRecordDao

    companion object {
        const val NAME: String = "saikai_ptt.db"

        /**
         * Opens the database, without touching the disk yet.
         *
         * Room defers the real open until the first query, so this is safe to
         * call while assembling the application container. The first query is
         * always from a coroutine off the main thread.
         */
        fun open(context: Context): SaikaiDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                SaikaiDatabase::class.java,
                NAME,
            ).build()
    }
}
