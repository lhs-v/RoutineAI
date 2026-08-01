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

    @Query("DELETE FROM proposal WHERE signature = :sig")
    suspend fun deleteProposal(sig: String)

    @Query("DELETE FROM proposal")
    suspend fun clearProposals()

    @Query("DELETE FROM proposal_event")
    suspend fun clearProposalEvents()

    /** 최근 노출 이력 — 방해 예산이 얼마나 남았는지 보여주기 위한 것 */
    @Query("SELECT COUNT(*) FROM proposal_event WHERE kind = 'surfaced' AND ts > :since")
    suspend fun surfacedSince(since: Long): Int

    @Insert
    suspend fun logProposalEvent(row: ProposalEventRow)

    @Query("SELECT * FROM proposal_event WHERE proposalSignature = :sig ORDER BY ts ASC")
    suspend fun proposalEvents(sig: String): List<ProposalEventRow>

    /** 사용자가 실제로 결정한 이력만. 노출·생성 로그는 뺀다. */
    @Query(
        "SELECT * FROM proposal_event WHERE proposalSignature = :sig " +
            "AND kind IN ('accepted','not_now','dismissed') ORDER BY ts ASC"
    )
    suspend fun decisions(sig: String): List<ProposalEventRow>

    /**
     * 심층 분석의 입력 — 결정과 실행의 전체 궤적.
     * surfaced 를 포함하는 이유: "떴는데 아무 반응 없음"도 신호다
     * (수락도 거절도 아닌 무시는 조건이 애매하게 넓다는 뜻일 수 있다).
     */
    @Query(
        "SELECT * FROM proposal_event WHERE kind IN " +
            "('accepted','not_now','dismissed','auto_applied','auto_failed','surfaced') " +
            "ORDER BY ts ASC"
    )
    suspend fun allDecisionEvents(): List<ProposalEventRow>

    /** 루틴 허브의 "몇 번 실행됐나". 원탭 실행(accepted)과 자동 실행을 합친다. */
    @Query(
        "SELECT proposalSignature AS signature, COUNT(*) AS cnt, MAX(ts) AS lastTs " +
            "FROM proposal_event WHERE kind IN ('accepted','auto_applied') " +
            "GROUP BY proposalSignature"
    )
    suspend fun runStats(): List<RunStat>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: KvRow)

    @Query("SELECT v FROM kv WHERE k = :k")
    suspend fun get(k: String): String?
}

/** [UsageDao.runStats] 의 행. Room 이 컬럼 별칭으로 채운다. */
data class RunStat(val signature: String, val cnt: Int, val lastTs: Long)
