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

    /**
     * 제안의 파라미터가 진화해 시그니처가 바뀔 때(updateOf) 결정 이력을
     * 새 시그니처로 이관한다 — 이력이 끊기면 심층 분석이 그 제안의
     * 과거를 잃는다.
     */
    // 파라미터명이 생성 Java 코드에 그대로 들어가므로 new/old 같은 예약어는 못 쓴다.
    @Query("UPDATE proposal_event SET proposalSignature = :newSig WHERE proposalSignature = :oldSig")
    suspend fun migrateProposalEvents(oldSig: String, newSig: String)

    /** 같은 이유로 실험의 참조도 함께 이관 — 끊기면 만료 롤백이 허공을 친다. */
    @Query("UPDATE experiment SET proposalSignature = :newSig WHERE proposalSignature = :oldSig")
    suspend fun migrateExperiments(oldSig: String, newSig: String)

    @Query("DELETE FROM proposal_event WHERE proposalSignature = :sig")
    suspend fun deleteProposalEvents(sig: String)

    @Query("DELETE FROM proposal")
    suspend fun clearProposals()

    @Query("DELETE FROM proposal_event")
    suspend fun clearProposalEvents()

    /**
     * 조건 밖에서 침묵한 횟수와 마지막 시각 — 카드에 "N번 지나갔어요"로
     * 보여주기 위한 것. 조건이 너무 좁은지를 사용자가 스스로 알아채는 유일한
     * 표면이다(에이전트는 같은 기록을 이력으로 읽는다).
     */
    @Query(
        "SELECT COUNT(*) AS cnt, MAX(ts) AS lastTs FROM proposal_event " +
            "WHERE kind = 'near_miss' AND proposalSignature = :sig"
    )
    suspend fun nearMissStat(sig: String): NearMissStat

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
     * 심층 분석의 입력 — 결정·실행·루틴 자체의 삶 전체 궤적.
     * surfaced(노출)·outcome_*(실행 후 유지/이탈)·near_miss(조건 밖 근접
     * 발화)·ignored_then(무시 직후 자발 행동)이 결정과 함께 있어야
     * "조건이 맞는가"를 양방향으로 판단할 수 있다.
     */
    @Query(
        "SELECT * FROM proposal_event WHERE kind IN " +
            "('accepted','not_now','dismissed','auto_applied','auto_failed','surfaced'," +
            "'outcome','near_miss','ignored_then','eclipsed','experiment_started'," +
            "'experiment_ended','brief_ack','brief_approve','brief_decline','brief_revert') " +
            "ORDER BY ts ASC"
    )
    suspend fun allDecisionEvents(): List<ProposalEventRow>

    // ---- 조건 실험 ----

    @Insert
    suspend fun insertExperiment(row: ExperimentRow): Long

    @Query("SELECT * FROM experiment WHERE id = :id")
    suspend fun experiment(id: Long): ExperimentRow?

    @Query("SELECT * FROM experiment WHERE state = 'running'")
    suspend fun runningExperiments(): List<ExperimentRow>

    @Query("SELECT * FROM experiment ORDER BY startedAt DESC")
    suspend fun experiments(): List<ExperimentRow>

    @Query("UPDATE experiment SET state = :state, verdict = :verdict WHERE id = :id")
    suspend fun concludeExperiment(id: Long, state: String, verdict: String?)

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

/** [UsageDao.nearMissStat] 의 행. 없으면 cnt=0, lastTs=0. */
data class NearMissStat(val cnt: Int, val lastTs: Long)
