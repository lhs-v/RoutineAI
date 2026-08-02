package com.routineai.interpret

import android.content.Context
import com.routineai.data.Db
import com.routineai.data.ProposalEventRow
import com.routineai.data.ProposalRow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId

/**
 * 심층 분석 한 사이클: 결정 이력 수집 → LLM 정제 요청 → 검증 → 제안 갱신.
 *
 * 1차 분석([ProposalEngine])이 통계에서 제안을 만들었다면, 여기는 그 제안에
 * 대한 **사용자의 반응**을 다시 LLM 에 보여 조건을 좁힌다. 수락·거절의 맥락
 * (시각·요일·네트워크·BT·당시 화면 앱)은 [com.routineai.watch.DecisionContext]
 * 가 해석 없이 기록해 둔 것이고, 의미 판단은 전부 이쪽 LLM 의 몫이다.
 *
 * 자동으로 돌지 않는다 — 결정 이력은 천천히 쌓이고 호출은 비용이 든다.
 * 루틴 탭의 버튼으로 사용자가 명시적으로 돌린다.
 */
class DeepAnalyzer(private val ctx: Context) {

    private val dao = Db.get(ctx).dao()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ---- LLM 출력 스키마 (deep-analysis.md 와 일치) ----

    @Serializable
    data class LlmOutput(
        val refinements: List<LlmRefinement> = emptyList(),
        val brief: String = "",
        val analysisNote: String = "",
    )

    @Serializable
    data class LlmRefinement(
        val signature: String,
        val conditionHours: String? = null,
        val conditionWeekdays: String? = null,
        val suggestAutoRun: Boolean = false,
        val insight: String = "",
    )

    data class Outcome(
        val refined: Int,
        val skipped: Int,
        val brief: String,
        val analysisNote: String,
    )

    /** 정제할 거리가 있는가 — 버튼 활성화 판단용. */
    suspend fun hasHistory(): Boolean = dao.allDecisionEvents()
        .any { it.kind in DECISION_KINDS }

    suspend fun refine(cfg: AzureConfig, onStep: (String) -> Unit = {}): Result<Outcome> {
        onStep("결정 이력 모으는 중")
        val proposals = dao.proposals().filter { it.state != "dismissed" }
        val events = dao.allDecisionEvents().groupBy { it.proposalSignature }

        // 결정이 하나도 없는 제안은 보내지 않는다 — 보낼 것도 물을 것도 없다.
        val withHistory = proposals.filter { p ->
            events[p.signature].orEmpty().any { it.kind in DECISION_KINDS }
        }
        if (withHistory.isEmpty()) {
            return Result.failure(IllegalStateException("아직 수락·거절 이력이 없습니다"))
        }

        onStep("조건 정제 요청 중 (최대 2분)")
        val user = userPrompt(withHistory, events)
        val raw = Interpreter(ctx).refine(user, cfg).getOrElse { return Result.failure(it) }

        val parsed = runCatching { json.decodeFromString(LlmOutput.serializer(), raw) }
            .getOrElse { return Result.failure(IllegalStateException("정제 JSON 파싱 실패: ${it.message}")) }

        onStep("결과 반영 중")
        val now = System.currentTimeMillis()
        var refined = 0
        var skipped = 0
        for (r in parsed.refinements) {
            val row = dao.proposal(r.signature)
            if (row == null || row.state == "dismissed" || !valid(r)) { skipped++; continue }
            dao.upsertProposal(
                row.copy(
                    conditionHours = r.conditionHours?.takeIf { it.isNotBlank() },
                    conditionWeekdays = r.conditionWeekdays?.takeIf { it.isNotBlank() },
                    suggestAutoRun = r.suggestAutoRun,
                    insight = r.insight.ifBlank { null },
                    refinedAt = now,
                    updatedAt = now,
                )
            )
            dao.logProposalEvent(
                ProposalEventRow(ts = now, proposalSignature = r.signature, kind = "refined")
            )
            refined++
        }

        return Result.success(Outcome(refined, skipped, parsed.brief, parsed.analysisNote))
    }

    // ------------------------------------------------------------------

    /**
     * 형식 검증. 조건 문자열이 깨져 있으면 [com.routineai.watch.PatternWatcher.inCondition]
     * 이 파싱 예외로 죽을 수 있으므로 여기서 거른다.
     */
    private fun valid(r: LlmRefinement): Boolean =
        okSpec(r.conditionHours, max = 23) && okSpec(r.conditionWeekdays, max = 7)

    private fun okSpec(spec: String?, max: Int): Boolean {
        if (spec.isNullOrBlank()) return true
        return spec.split(',').all { part ->
            val nums = part.trim().split('-')
            nums.size in 1..2 && nums.all { n -> n.toIntOrNull()?.let { it in 0..max } == true }
        }
    }

    private fun userPrompt(
        proposals: List<ProposalRow>,
        events: Map<String, List<ProposalEventRow>>,
    ): String {
        val t = Instant.now().atZone(ZoneId.systemDefault())
        val input = buildJsonObject {
            put("now", buildJsonObject {
                put("hour", t.hour)
                put("weekday", t.dayOfWeek.value)
            })
            put("proposals", buildJsonArray {
                for (p in proposals) add(buildJsonObject {
                    put("signature", p.signature)
                    put("oneLine", p.oneLine)
                    put("category", p.category)
                    put("state", p.state)
                    put("autoRun", p.autoRun)
                    put("trigger", buildJsonObject {
                        put("type", p.triggerType)
                        p.triggerParam?.let { put("param", it) } ?: put("param", JsonNull)
                    })
                    put("action", buildJsonObject {
                        put("type", p.actionType)
                        put("params", runCatching {
                            json.decodeFromString<List<String>>(p.actionParams)
                        }.getOrDefault(emptyList()).let { list ->
                            buildJsonArray { list.forEach { add(it) } }
                        })
                    })
                    p.conditionHours?.let { put("conditionHours", it) }
                        ?: put("conditionHours", JsonNull)
                    p.conditionWeekdays?.let { put("conditionWeekdays", it) }
                        ?: put("conditionWeekdays", JsonNull)
                    put("history", buildJsonArray {
                        // 상한을 결정/노출로 나눈다. 방해 예산이 보류되면서
                        // 노출(surfaced)이 많아졌는데, 하나의 상한을 같이 쓰면
                        // 노출이 정작 결정 기록을 밀어낸다 — 조건 정제의 근거는
                        // 결정이고, 노출은 무시 밀도를 보는 보조 자료다.
                        val all = events[p.signature].orEmpty()
                        val decisions = all.filter { it.kind in DECISION_KINDS }
                            .takeLast(MAX_DECISIONS)
                        val exposures = all.filter { it.kind !in DECISION_KINDS }
                            .takeLast(MAX_EXPOSURES)
                        for (e in (decisions + exposures).sortedBy { it.ts }) {
                            add(buildJsonObject {
                                put("kind", e.kind)
                                put("hour", e.hour)
                                put("weekday", e.weekday)
                                e.foregroundPkg?.let { put("foreground", it) }
                                e.network?.let { put("network", it) }
                                e.btDevice?.let { put("bt", it) }
                                e.choice?.let { put("choice", it) }
                                e.ringer?.let { put("ringer", it) }
                                if (e.charging) put("charging", true)
                                if (e.batteryPct in 0..100) put("battery", e.batteryPct)
                            })
                        }
                    })
                })
            })
        }

        return buildString {
            appendLine("아래는 루틴 제안들에 대한 사용자의 결정 이력입니다.")
            appendLine("시스템 프롬프트의 절차대로 조건을 정제하고, 출력 스키마의")
            appendLine("순수 JSON 객체 하나만 출력하세요.")
            appendLine()
            appendLine("```json")
            appendLine(input.toString())
            appendLine("```")
        }
    }

    companion object {
        private val DECISION_KINDS = setOf("accepted", "not_now", "dismissed")

        /** 제안당 이력 상한 — 결정과 노출을 따로 센다. 프롬프트 크기와의 타협점. */
        private const val MAX_DECISIONS = 30
        private const val MAX_EXPOSURES = 15
    }
}
