package com.rountineai.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UsageDao {

    /** 유니크 인덱스 덕분에 중복은 조용히 무시된다. 수집 창이 겹쳐도 안전하다. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvents(rows: List<UsageEventRow>): List<Long>

    @Query("SELECT * FROM usage_event WHERE ts >= :from AND ts < :to ORDER BY ts ASC")
    suspend fun events(from: Long, to: Long): List<UsageEventRow>

    @Query("SELECT COUNT(*) FROM usage_event")
    suspend fun eventCount(): Int

    @Query("SELECT MIN(ts) FROM usage_event")
    suspend fun firstEventTs(): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNotifs(rows: List<NotifEventRow>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNotif(row: NotifEventRow)

    @Query("SELECT * FROM notif_event WHERE ts >= :from AND ts < :to ORDER BY ts ASC")
    suspend fun notifs(from: Long, to: Long): List<NotifEventRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNet(rows: List<NetBucketRow>)

    @Query("SELECT * FROM net_bucket WHERE bucketStart >= :from AND bucketStart < :to")
    suspend fun net(from: Long, to: Long): List<NetBucketRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNetChange(row: NetworkChangeRow)

    @Query("SELECT * FROM net_change WHERE ts >= :from AND ts < :to ORDER BY ts ASC")
    suspend fun netChanges(from: Long, to: Long): List<NetworkChangeRow>

    @Query("SELECT * FROM net_change ORDER BY ts DESC LIMIT 1")
    suspend fun lastNetChange(): NetworkChangeRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: KvRow)

    @Query("SELECT v FROM kv WHERE k = :k")
    suspend fun get(k: String): String?
}
