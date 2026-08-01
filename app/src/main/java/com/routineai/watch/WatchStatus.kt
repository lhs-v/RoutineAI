package com.routineai.watch

import android.content.Context
import com.routineai.data.Db
import com.routineai.data.ProposalRow

/**
 * 지금 왜 제안이 안 뜨는지를 사람이 읽을 수 있게 만든다.
 *
 * 억제 규칙이 여럿(방해 예산·연타 방지·맥락 버킷·조건 정제)이라, 화면에
 * 이유가 안 보이면 "고장인가 설계인가"를 구분할 수 없다. 데모 리허설에서
 * 특히 필요하다 — 리허설 중 한도가 차서 안 뜨는 걸 버그로 오해하기 쉽다.
 */
object WatchStatus {

    data class Line(val label: String, val value: String, val warn: Boolean = false)

    data class Snapshot(
        val lines: List<Line>,
        /** 지금 이 순간의 맥락 버킷 — 어떤 상황으로 기록되는지 */
        val bucket: String,
    )

    suspend fun read(ctx: Context): Snapshot {
        val dao = Db.get(ctx).dao()
        val now = System.currentTimeMillis()
        val dayAgo = now - 24L * 60 * 60 * 1000

        val proposals = dao.proposals()
        val surfacedToday = dao.surfacedSince(dayAgo)
        val here = DecisionContext.capture(ctx, "", "probe", null, now)
        val bucket = DecisionContext.bucket(here)

        val budgetLeft = (PatternWatcher.DAILY_BUDGET - surfacedToday).coerceAtLeast(0)
        val byCategory = proposals
            .filter { (it.lastSurfacedAt ?: 0L) > dayAgo }
            .groupingBy { it.category }.eachCount()

        val lines = ArrayList<Line>()
        // 감지 경로가 살아 있는지가 첫 줄이어야 한다 — 이게 꺼져 있으면
        // 나머지가 다 정상이어도 아무것도 안 뜬다(실제로 그래서 헤맸다).
        lines += Line(
            "감지 경로",
            buildString {
                append(if (WatchService.isRunning) "사용정보 감시 켜짐" else "감시 꺼짐")
                if (PatternAccessibilityService.isConnected()) append(" + 접근성")
            },
            warn = !WatchService.isRunning,
        )
        lines += Line(
            "오늘 남은 제안 횟수",
            "$budgetLeft / ${PatternWatcher.DAILY_BUDGET}",
            warn = budgetLeft == 0,
        )
        if (byCategory.isNotEmpty()) {
            lines += Line(
                "오늘 이미 띄운 종류",
                byCategory.entries.joinToString(", ") { "${it.key} ${it.value}" },
            )
        }
        lines += Line("지금 맥락", bucket)
        lines += Line("연결된 기기", BtState.connectedDevice() ?: "없음")

        // 제안별로 왜 대기 중인지
        for (p in proposals.filter { it.state !in setOf("dismissed") }.take(6)) {
            val gap = if (p.state == "accepted") PatternWatcher.ACCEPTED_MIN_GAP_MS
            else PatternWatcher.RESURFACE_MS
            val cool = p.lastSurfacedAt?.let { last ->
                val remain = gap - (now - last)
                when {
                    remain <= 0 -> null
                    remain < 60_000 -> "${remain / 1000}초 뒤"
                    else -> "${remain / 60_000}분 뒤"
                }
            }
            val rejects = dao.decisions(p.signature)
                .filter { it.kind == "not_now" || it.kind == "dismissed" }
            val here2 = rejects.count { DecisionContext.bucket(it) == bucket }
            val why = when {
                p.state == "accepted" && p.autoRun -> "자동 실행"
                p.state == "accepted" -> "수락됨 · 원탭 제안"
                here2 >= PatternWatcher.BUCKET_REJECT_LIMIT -> "이 맥락에선 안 뜸 (거절 ${here2}회)"
                cool != null -> "대기 중 · $cool"
                budgetLeft == 0 -> "예산 소진"
                else -> "감시 중"
            }
            lines += Line(shorten(p), why, warn = why.startsWith("이 맥락") || why == "예산 소진")
        }
        return Snapshot(lines, bucket)
    }

    private fun shorten(p: ProposalRow): String =
        p.oneLine.take(18).let { if (p.oneLine.length > 18) "$it…" else it }

    /** 데모를 처음부터 다시 보여주기 위해 제안·이력을 모두 지운다. */
    suspend fun resetProposals(ctx: Context) {
        val dao = Db.get(ctx).dao()
        dao.clearProposalEvents()
        dao.clearProposals()
    }
}
