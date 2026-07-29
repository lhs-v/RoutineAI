package com.rountineai.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [UsageEventRow::class, NotifEventRow::class, NetBucketRow::class,
        NetworkChangeRow::class, KvRow::class],
    version = 1,
    exportSchema = true,
)
abstract class Db : RoomDatabase() {
    abstract fun dao(): UsageDao

    companion object {
        @Volatile private var instance: Db? = null

        fun get(ctx: Context): Db = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, Db::class.java, "rountine.db")
                .build().also { instance = it }
        }
    }
}
