package org.deadnet.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DeadNetMessage::class, SeenPacket::class],
    version = 1,
    exportSchema = false
)
abstract class DeadNetDatabase : RoomDatabase() {

    abstract fun deadNetMessageDao(): DeadNetMessageDao
    abstract fun seenPacketDao(): SeenPacketDao

    companion object {
        @Volatile
        private var INSTANCE: DeadNetDatabase? = null

        fun getDatabase(context: Context): DeadNetDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DeadNetDatabase::class.java,
                    "deadnet_database"
                )
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
