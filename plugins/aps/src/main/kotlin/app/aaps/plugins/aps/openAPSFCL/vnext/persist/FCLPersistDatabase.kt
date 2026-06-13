package app.aaps.plugins.aps.openAPSFCL.vnext.persist

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Losstaande database voor PERSIST-events (V-learner input).
 *
 * Bewust gescheiden van FCLAnalyzerDatabase: die gebruikt
 * fallbackToDestructiveMigration(dropAllTables=true), wat bij elke
 * schema-wijziging ALLE tabellen leegt — inclusief de 7-dagen
 * cyclus-log. Tijdens de iteratiefase van de V-learner kunnen we
 * deze database vrijelijk resetten zonder die data te verliezen.
 */
@Database(
    entities = [FCLPersistEventEntity::class],
    version = 1,
    exportSchema = false
)
abstract class FCLPersistDatabase : RoomDatabase() {

    abstract fun persistEventDao(): FCLPersistEventDao

    companion object {
        private const val DB_NAME = "fcl_persist.db"

        @Volatile
        private var INSTANCE: FCLPersistDatabase? = null

        fun getInstance(context: Context): FCLPersistDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FCLPersistDatabase::class.java,
                    DB_NAME
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }

        fun cutoffMs(): Long =
            System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1000L
    }
}
