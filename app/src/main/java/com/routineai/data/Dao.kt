package com.routineai.data

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

    @Query("SELECT MAX(ts) FROM usage_event")
    suspend fun lastEventTs(): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNotifs(rows: List<NotifEventRow>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNotif(row: NotifEventRow)

    @Query("SELECT * FROM notif_event WHERE ts >= :from AND ts < :to ORDER BY ts ASC")
    suspend fun notifs(from: Long, to: Long): List<NotifEventRow>

    /**
     * 통신량 버킷은 IGNORE 로 넣으면 안 된다.
     *
     * 시스템은 같은 (버킷, uid, 전송수단) 을 foreground/background·tag 로 쪼개 돌려주고,
     * 진행 중인 버킷은 나중에 다시 읽으면 값이 커져 있다. IGNORE 면 첫 조각만 남아
     * 통신량이 실제보다 작게 기록된다. 합산·비교는 [com.routineai.collect.NetworkCollector]
     * 가 하고 여기서는 그 결과로 덮어쓴다.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNet(rows: List<NetBucketRow>)

    @Query("SELECT * FROM net_bucket WHERE bucketStart >= :from AND bucketStart < :to")
    suspend fun net(from: Long, to: Long): List<NetBucketRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNetChange(row: NetworkChangeRow)

    @Query("SELECT * FROM net_change WHERE ts >= :from AND ts < :to ORDER BY ts ASC")
    suspend fun netChanges(from: Long, to: Long): List<NetworkChangeRow>

    @Query("SELECT * FROM net_change ORDER BY ts DESC LIMIT 1")
    suspend fun lastNetChange(): NetworkChangeRow?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBt(row: BtEventRow)

    @Query("SELECT * FROM bt_event WHERE ts >= :from AND ts < :to ORDER BY ts ASC")
    suspend fun btEvents(from: Long, to: Long): List<BtEventRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHealth(rows: List<HealthSessionRow>)

    @Query("SELECT * FROM health_session WHERE tsStart >= :from AND tsStart < :to ORDER BY tsStart ASC")
    suspend fun healthSessions(from: Long, to: Long): List<HealthSessionRow>

    @Query("SELECT * FROM proposal ORDER BY updatedAt DESC")
    suspend fun proposals(): List<ProposalRow>

    @Query("SELECT * FROM proposal WHERE signature = :sig")
    suspend fun proposal(sig: String): ProposalRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProposal(row: ProposalRow)

    @Query("UPDATE proposal SET state = :state, updatedAt = :at WHERE signature = :sig")
    suspend fun setProposalState(sig: String, state: String, at: Long)

    @Insert
    suspend fun logProposalEvent(row: ProposalEventRow)

    @Query("SELECT * FROM proposal_event WHERE proposalSignature = :sig ORDER BY ts ASC")
    suspend fun proposalEvents(sig: String): List<ProposalEventRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: KvRow)

    @Query("SELECT v FROM kv WHERE k = :k")
    suspend fun get(k: String): String?
}
