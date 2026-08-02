package com.routineai.interpret

import android.content.Context
import android.util.Log
import com.routineai.data.Db
import com.routineai.data.ExperimentRow
import com.routineai.watch.DecisionContext

/**
 * 조건 실험의 수명주기.
 *
 * 에이전트가 조건 가설을 기간 한정으로 실제 적용해 보는 유일한 변경 창구다.
 * 모든 실험은 만료를 갖고, 만료 순간 [rollbackExpired] 가 조건을 원래대로
 * 되돌린다 — 만료 없는 변경 권한은 "몰래 바꾸는 앱"으로 가는 문이라 없다.
 *
 * 실험 창의 반응(수락·near_miss·dwell)은 이력에 그대로 쌓이고,
 * experiment_started / experiment_ended 가 경계 표식으로 남아 다음 분석이
 * 창 안팎을 비교 평가한다. 영구 확정은 refinements(기존 경로)로만 한다.
 */
object Experiments {

    private const val TAG = "Experiments"

    suspend fun start(
        ctx: Context,
        signature: String,
        hours: String?,
        weekdays: String?,
        days: Int,
        hypothesis: String,
    ): String {
        val dao = Db.get(ctx).dao()
        val p = dao.proposal(signature) ?: return "없는 제안입니다: $signature"
        if (p.state == "dismissed") return "사용자가 보지 않기로 한 제안입니다 — 실험 불가"
        if (dao.runningExperiments().any { it.proposalSignature == signature }) {
            return "이미 이 제안에 진행 중인 실험이 있습니다 (제안당 하나)"
        }
        val h = hours?.takeIf { it.isNotBlank() }
        val w = weekdays?.takeIf { it.isNotBlank() }
        if (h == null && w == null) return "conditionHours 또는 conditionWeekdays 가 필요합니다"
        if (!okSpec(h, 0, 23, rangeEndMax = 24) || !okSpec(w, 1, 7)) {
            return "조건 형식이 잘못됐습니다 (예: \"9-16\", \"22-2\", \"1-5\")"
        }
        if (hypothesis.isBlank()) return "hypothesis(사용자에게 보일 가설 한 문장)가 필요합니다"

        val d = days.coerceIn(3, 21)
        val now = System.currentTimeMillis()
        val id = dao.insertExperiment(
            ExperimentRow(
                proposalSignature = signature,
                hypothesis = hypothesis.take(120),
                conditionHours = h,
                conditionWeekdays = w,
                prevHours = p.conditionHours,
                prevWeekdays = p.conditionWeekdays,
                startedAt = now,
                endsAt = now + d * 86_400_000L,
                state = "running",
            )
        )
        dao.upsertProposal(p.copy(conditionHours = h, conditionWeekdays = w, updatedAt = now))
        dao.logProposalEvent(DecisionContext.capture(ctx, signature, "experiment_started"))
        Log.i(TAG, "실험 #$id 시작: $signature — $hypothesis (${d}일)")
        return "실험 #$id 시작 — ${d}일 뒤 자동 롤백. 적용 조건: 시간=${h ?: "-"} 요일=${w ?: "-"}"
    }

    /**
     * 평가 기록. 아직 진행 중이면 즉시 롤백하고 마감한다 —
     * 평가가 끝난 실험이 조건을 계속 쥐고 있을 이유가 없다.
     */
    suspend fun conclude(ctx: Context, id: Long, verdict: String): String {
        val dao = Db.get(ctx).dao()
        val e = dao.experiment(id) ?: return "없는 실험: $id"
        if (e.state == "evaluated") return "이미 평가된 실험입니다"
        if (verdict.isBlank()) return "verdict(평가 한 문장)가 필요합니다"
        val now = System.currentTimeMillis()
        if (e.state == "running") {
            dao.proposal(e.proposalSignature)?.let { p ->
                dao.upsertProposal(
                    p.copy(conditionHours = e.prevHours, conditionWeekdays = e.prevWeekdays, updatedAt = now)
                )
            }
            dao.logProposalEvent(DecisionContext.capture(ctx, e.proposalSignature, "experiment_ended"))
        }
        dao.concludeExperiment(id, "evaluated", verdict.take(200))
        Log.i(TAG, "실험 #$id 평가: $verdict")
        return "실험 #$id 평가 기록됨. 조건을 영구 확정하려면 refinements 로 내라"
    }

    /** 감시 루프가 주기적으로 부른다. 만료된 실험의 조건을 되돌린다. */
    suspend fun rollbackExpired(ctx: Context): Int {
        val dao = Db.get(ctx).dao()
        val now = System.currentTimeMillis()
        var n = 0
        for (e in dao.runningExperiments()) {
            if (e.endsAt > now) continue
            dao.proposal(e.proposalSignature)?.let { p ->
                dao.upsertProposal(
                    p.copy(conditionHours = e.prevHours, conditionWeekdays = e.prevWeekdays, updatedAt = now)
                )
            }
            dao.concludeExperiment(e.id, "ended", null)
            dao.logProposalEvent(DecisionContext.capture(ctx, e.proposalSignature, "experiment_ended"))
            Log.i(TAG, "실험 #${e.id} 만료 — 조건 롤백")
            n++
        }
        return n
    }

    /** DeepAnalyzer.okSpec 과 같은 규약 — hours 는 끝 24·자정 넘김 허용, weekdays 는 1..7. */
    private fun okSpec(spec: String?, min: Int, max: Int, rangeEndMax: Int = max): Boolean {
        if (spec.isNullOrBlank()) return true
        return spec.split(',').all { part ->
            val nums = part.trim().split('-').map { it.trim().toIntOrNull() }
            when (nums.size) {
                1 -> nums[0]?.let { it in min..max } == true
                2 -> nums[0]?.let { it in min..max } == true &&
                    nums[1]?.let { it in min..rangeEndMax } == true
                else -> false
            }
        }
    }
}
