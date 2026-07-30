package com.routineai.data

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
        @Volatile private var demoInstance: Db? = null

        fun get(ctx: Context): Db = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, Db::class.java, "routine.db")
                .build().also { instance = it }
        }

        /**
         * assets 에 넣어둔 데모 로그.
         *
         * 리포트를 통째로 넣지 않고 **원본 로그**를 넣는다. 그래야 데모에서도
         * [com.routineai.analysis.Sessionizer] → [com.routineai.analysis.Analyzer] 가
         * 실제로 돌고, 나중에 집계 로직을 고치면 데모에도 그대로 반영된다.
         *
         * 쓰기는 하지 않는다. 수집은 언제나 [get] 쪽 실제 DB 에만 쌓인다.
         */
        fun demo(ctx: Context): Db = demoInstance ?: synchronized(this) {
            demoInstance ?: Room.databaseBuilder(
                ctx.applicationContext, Db::class.java, DEMO_DB
            ).createFromAsset("$DEMO_ASSET_DIR/$DEMO_ASSET_FILE")
                .build().also { demoInstance = it }
        }

        /**
         * 데모 로그가 이 빌드에 들어 있는가.
         *
         * 데모 DB 는 실제 사용 기록이라 저장소에 올리지 않는다(`.gitignore`).
         * 그래서 저장소를 클론해서 빌드하면 이 에셋이 없다. 없는 채로 [demo] 를 열면
         * 첫 질의에서 터지므로, 화면이 미리 물어보고 토글 자체를 막는다.
         */
        fun hasDemoAsset(ctx: Context): Boolean = runCatching {
            ctx.assets.list(DEMO_ASSET_DIR)?.contains(DEMO_ASSET_FILE) == true
        }.getOrDefault(false)

        private const val DEMO_DB = "demo-routine.db"
        private const val DEMO_ASSET_DIR = "demo"
        private const val DEMO_ASSET_FILE = "routine.db"
    }
}
