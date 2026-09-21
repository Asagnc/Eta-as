package io.github.mangi.eta.data.db

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        AgentTextChunkEntity::class,
        ConversationEntity::class,
        ConversationContextCheckpointEntity::class,
        ConversationMessageEntity::class,
        ConversationStateEntity::class,
        ProviderEntity::class,
        ProviderModelEntity::class,
        RuntimeResultEntity::class,
        RuntimeArchiveRunEntity::class,
        RuntimeArchiveEventEntity::class,
        RuntimeInFlightRunEntity::class,
        RuntimeInFlightEventEntity::class,
        SkillRegistryEntity::class,
        McpServerEntity::class,
        CharacterEntity::class,
        UserPersonaEntity::class,
        SubAgentRunEntity::class,
        SubAgentMailboxEntity::class,
    ],
    version = EtaMigrations.CURRENT_VERSION,
    exportSchema = false,
)
internal abstract class EtaDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun providerDao(): ProviderDao
    abstract fun runtimeRunDao(): RuntimeRunDao
    abstract fun skillDao(): SkillDao
    abstract fun mcpServerDao(): McpServerDao
    abstract fun characterDao(): CharacterDao
    abstract fun subAgentRunDao(): SubAgentRunDao
    abstract fun subAgentMailboxDao(): SubAgentMailboxDao

    companion object {
        @Volatile
        private var instance: EtaDatabase? = null

        fun get(context: Context): EtaDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EtaDatabase::class.java,
                    "eta.db",
                )
                    .addMigrations(*EtaMigrations.ALL.toTypedArray())
                    .addCallback(object : Callback() {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) { EtaMigrations.createTextChunkCleanup(db) }
                        override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) { EtaMigrations.createTextChunkCleanup(db) }
                    })
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }

        @VisibleForTesting
        internal fun closeForTests() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }

    }
}
