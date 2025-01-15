package com.olup.notable.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Date

class Converters {
    @TypeConverter
    fun fromListString(value: List<String>) = Json.encodeToString(value)
    @TypeConverter
    fun toListString(value: String) = Json.decodeFromString<List<String>>(value)
    @TypeConverter
    fun fromListPoint(value: List<StrokePoint>) = Json.encodeToString(value)
    @TypeConverter
    fun toListPoint(value: String) = Json.decodeFromString<List<StrokePoint>>(value)

    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time?.toLong()
    }
}



@Database(
    entities = [Folder::class, Notebook::class, Page::class, Stroke::class, Kv::class, ChatMessage::class],
    version = 30,
    autoMigrations = [
        AutoMigration(19,20),
        AutoMigration(20,21),
        AutoMigration(21,22),
        AutoMigration(23,24),
        AutoMigration(24,25),
        AutoMigration(25,26),
        AutoMigration(26,27),
        AutoMigration(27,28),
        AutoMigration(28,29),
        AutoMigration(29,30),
    ]
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun folderDao(): FolderDao
    abstract fun kvDao(): KvDao
    abstract fun notebookDao(): NotebookDao
    abstract fun pageDao(): PageDao
    abstract fun strokeDao(): StrokeDao
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase {
            if (INSTANCE == null) {
                synchronized(this) {
                    INSTANCE =
                        Room.databaseBuilder(context, AppDatabase::class.java, "app_database")
                            .allowMainThreadQueries()
                            //.fallbackToDestructiveMigration()
                            .addMigrations(
                                MIGRATION_16_17,
                                MIGRATION_17_18,
                                MIGRATION_22_23,
                                MIGRATION_28_29,
                                MIGRATION_29_30)
                            .build()
                }
            }
            return INSTANCE!!
        }
    }
}
