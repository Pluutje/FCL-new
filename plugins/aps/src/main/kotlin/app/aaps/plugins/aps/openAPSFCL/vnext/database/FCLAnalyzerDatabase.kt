package app.aaps.plugins.aps.openAPSFCL.vnext.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.BasalProfileHistoryEntity
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.EpisodeEntity
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.NightWindowEntity

// ── MIGRATION_16_17 (16/07/2026, Ecko) ─────────────────────────────────────
// Zuiver additief: 6 nieuwe kolommen op de bestaande fcl_cycle_log-tabel.
// Kolomnamen zijn de Kotlin-veldnamen zelf (geen @ColumnInfo-overrides
// elders in FCLCycleLogEntity.kt, dus Room mapt 1-op-1). Type-mapping volgt
// Room's standaardconventie: String→TEXT, Boolean→INTEGER (0/1), Double→REAL.
// NOT NULL vereist een DEFAULT bij ADD COLUMN op een tabel met bestaande
// rijen — vandaar de expliciete defaults, die exact overeenkomen met de
// Kotlin-defaultwaarden in FCLCycleLogEntity.kt (val ... = "" / false / 0.0).
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE fcl_cycle_log ADD COLUMN codeVersion TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE fcl_cycle_log ADD COLUMN appRestartThisCycle INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE fcl_cycle_log ADD COLUMN aigfPct REAL NOT NULL DEFAULT 100.0")
        db.execSQL("ALTER TABLE fcl_cycle_log ADD COLUMN aigfActive INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE fcl_cycle_log ADD COLUMN aigfReason TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE fcl_cycle_log ADD COLUMN episodePeakCommitU REAL NOT NULL DEFAULT 0.0")
    }
}

@Database(
    entities = [
        FCLCycleLogEntity::class,
        EpisodeEntity::class,
        NightWindowEntity::class,
        BasalProfileHistoryEntity::class
    ],
    // v13→v15 (05/07/2026, Ecko): +curveFitR2/+curveAcceleration/+toppingOutBoost
    // (in TrendsFields), en FCLCycleLogEntity herstructureerd in @Embedded-
    // groepen om de eerdere VerifyError-crash (registerlimiet op de platte
    // ~150-parameter constructor) structureel uit te sluiten.
    //
    // BEWUST v15, NIET v14: een eerdere, teruggedraaide poging declareerde al
    // versie 14 (met de kapotte platte constructor). Room's schema-creatie
    // (CREATE TABLE) gebeurt via annotatie-metadata, niet via de Kotlin-
    // constructor — dus die stap kan toen best geslaagd zijn vóórdat de
    // VerifyError bij het eerste schrijfmoment optrad. Het toestel kan dus al
    // ergens op user_version=14 staan. Room vergelijkt alleen het versienummer
    // met wat er nu gedeclareerd is; bij een match slaat het de migratie
    // (en dus fallbackToDestructiveMigration) gewoon OVER. v15 garandeert een
    // echte version-mismatch en dus een gegarandeerde schone migratie,
    // ongeacht de staat waarin het toestel nu verkeert.
    //
    // v15→v16 (11/07/2026, Ecko): +bgStijgtNogFors/+commitNrUsed (in
    // ForensicFields) — twee nieuwe, puur diagnostische kolommen. Ook een
    // toevoeging met default-waarden vereist een versiebump: fallback-
    // ToDestructiveMigration werkt op VERSIENUMMER-mismatch, niet op een
    // inhoudelijke schema-diff — bij een ongewijzigd versienummer had Room de
    // migratie simpelweg overgeslagen en het oude, incompatibele schema laten
    // staan (zie de uitleg bij v15 hierboven — exact hetzelfde risico).
    // GEVOLG: bij de eerste start na deze update wordt de cycle-log/episode/
    // night-window/basal-profile-geschiedenis in de Room-database gewist
    // (dropAllTables=true) en leeg opnieuw opgebouwd.
    //
    // CORRECTIE (11/07/2026, Ecko): eerdere aanname dat de CSV-bestanden
    // hierbuiten zouden staan was ONJUIST — exportCsvLast7Days() in
    // FCLCycleLogRepository.kt bouwt FCLvNext_Log_v8.csv bij elke export
    // volledig opnieuw op vanuit dao.getSince() (Room als bron, niet als
    // spiegel). Na deze migratie is Room dus leeg, en de eerstvolgende
    // exportronde na de wipe overschrijft de bestaande CSV met (aanvankelijk
    // vrijwel) niets — de meerdaagse geschiedenis in die CSV gaat feitelijk
    // ook verloren, niet alleen de Room-tabellen zelf.
    //
    // v16→v17 (16/07/2026, Ecko): +codeVersion/+appRestartThisCycle/+aigfPct/
    // +aigfActive/+aigfReason/+episodePeakCommitU in FCLCycleLogEntity — zie
    // de kdoc daar. ANDERS DAN alle eerdere bumps hierboven: dit is zuiver
    // ADDITIEF (alleen nieuwe kolommen, niets hernoemd/verwijderd/van type
    // veranderd), dus deze keer een ECHTE Migration (MIGRATION_16_17
    // hieronder, ALTER TABLE ... ADD COLUMN) i.p.v. destructieve wipe — de
    // 90 dagen cycle-log/episode/night-window/basalprofiel-geschiedenis
    // blijft nu dus intact. Historische rijen van vóór v17 krijgen de
    // DEFAULT-waarden uit de ALTER TABLE-statements (lege string/0/false)
    // voor de 6 nieuwe kolommen — geen dataverlies, wel logisch dat oude
    // rijen op "geen AIGF-data"/"onbekende code-versie" uitkomen.
    // RISICO (Ecko, expliciet): deze Migration kon niet op een echt toestel
    // getest worden. fallbackToDestructiveMigration hieronder blijft daarom
    // als vangnet staan — als de Migration onverhoopt niet toepasbaar blijkt
    // (bijv. door een OEM-SQLite-eigenaardigheid), valt Room automatisch
    // terug op de bekende, werkende destructieve wipe i.p.v. te crashen.
    version = 17,
    exportSchema = false
)
abstract class FCLAnalyzerDatabase : RoomDatabase() {

    abstract fun cycleLogDao(): FCLCycleLogDao
    abstract fun episodeDao(): app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.EpisodeDao
    abstract fun nightWindowDao(): app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.NightWindowDao
    abstract fun basalProfileHistoryDao(): app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.BasalProfileHistoryDao

    companion object {
        private const val DB_NAME = "fcl_analyzer.db"

        @Volatile
        private var INSTANCE: FCLAnalyzerDatabase? = null

        fun getInstance(context: Context): FCLAnalyzerDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FCLAnalyzerDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_16_17)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }

        fun cutoffMs(): Long =
            System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000L
    }
}