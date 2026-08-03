package com.routineai.interpret

import android.content.Context
import com.routineai.analysis.Analyzer
import com.routineai.analysis.Report
import com.routineai.data.Db
import com.routineai.data.ProposalEventRow
import com.routineai.data.ProposalRow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 분석 한 사이클: 리포트 생성 → LLM 호출 → 파싱·검증 → 제안 DB 병합.
 *
 * 병합 규칙이 핵심이다. 같은 (트리거, 행동) 시그니처의 제안이 다시 나오면
 * 증거·확신도만 갱신하고 **상태는 유지한다** — 사용자가 거절한 것을 분석이
 * 되살리면 안 되고, 수락한 것이 다시 후보로 내려가도 안 된다.
 */
class ProposalEngine(private val ctx: Context) {

    private val dao = Db.get(ctx).dao()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ---- LLM 출력 스키마 (proposal-rules.md 와 일치) ----

    @Serializable
    data class LlmOutput(
        val proposals: List<LlmProposal> = emptyList(),
        val rejected: List<LlmRejected> = emptyList(),
        val retire: List<LlmRetire> = emptyList(),
        val analysisNote: String = "",
    )

    @Serializable
    data class LlmProposal(
        val id: String = "",
        val category: String,
        val oneLine: String,
        val narrative: String = "",
        val trigger: LlmTrigger,
        val action: LlmAction,
        val samsungCondition: String? = null,
        val samsungAction: String? = null,
        val evidence: List<String> = emptyList(),
        val confidence: String = "가설",
        /** 포트폴리오 항목의 변형·개선일 때 — 그 시그니처의 카드가 갱신된다 */
        val updateOf: String? = null,
        /** 패턴이 시간·요일에 묶이면 1차부터 조건을 단다 — 팝업이 그 안에서만 뜬다 */
        val conditionHours: String? = null,
        val conditionWeekdays: String? = null,
    )

    @Serializable data class LlmTrigger(val type: String, val param: String? = null)
    @Serializable data class LlmAction(val type: String, val params: List<String> = emptyList())
    @Serializable data class LlmRejected(val candidate: String, val reason: String)
    @Serializable data class LlmRetire(val signature: String, val reason: String = "")

    /** LLM 입력용 포트폴리오 한 줄 — 재분석이 백지가 아니라 이어쓰기가 되게 한다 */
    @Serializable
    private data class PortfolioItem(
        val signature: String,
        val category: String,
        val oneLine: String,
        val state: String,
        val trigger: String,
        val action: String,
        val conditionHours: String? = null,
        val conditionWeekdays: String? = null,
    )

    data class Outcome(
        val added: Int,
        val updated: Int,
        val dropped: Int,
        val retired: Int,
        val rejected: List<LlmRejected>,
        val analysisNote: String,
    )

    /**
     * @param demo 데모 DB 로 리포트를 만들지. 제안은 어느 쪽이든 실 DB 에 쌓인다 —
     *   제안의 수명주기는 수집 데이터가 아니라 앱의 상태다.
     */
    suspend fun analyze(cfg: AzureConfig, demo: Boolean, onStep: (String) -> Unit = {}): Result<Outcome> {
        onStep("리포트 만드는 중")
        val report: Report = Analyzer(ctx, demo).build(onStep = onStep)
        val reportJson = json.encodeToString(Report.serializer(), report)

        // 기존 제안을 입력에 넣는다. 없으면(첫 분석) null — 프롬프트도 증분
        // 규칙 없이 백지 모드로 간다.
        val before = dao.proposals()
        val portfolioJson = if (before.isEmpty()) null else json.encodeToString(
            before.map { r ->
                PortfolioItem(
                    signature = r.signature,
                    category = r.category,
                    oneLine = r.oneLine,
                    state = r.state,
                    trigger = "${r.triggerType}:${r.triggerParam.orEmpty()}",
                    action = "${r.actionType}:${r.actionParams}",
                    conditionHours = r.conditionHours,
                    conditionWeekdays = r.conditionWeekdays,
                )
            }
        )

        onStep("제안 요청 중 (최대 2분)")
        val raw = Interpreter(ctx).propose(reportJson, cfg, portfolioJson)
            .getOrElse { return Result.failure(it) }

        val parsed = runCatching { json.decodeFromString(LlmOutput.serializer(), raw) }
            .getOrElse { return Result.failure(IllegalStateException("제안 JSON 파싱 실패: ${it.message}")) }

        onStep("제안 정리 중")
        var added = 0; var updated = 0; var dropped = 0; var retired = 0
        val now = System.currentTimeMillis()

        for (p in parsed.proposals) {
            if (!valid(p)) { dropped++; continue }
            // 조건은 형식이 어긋나면 조건만 버린다 — 제안 자체는 살린다.
            val condH = p.conditionHours?.takeIf { it.isNotBlank() && okHours(it) }
            val condW = p.conditionWeekdays?.takeIf { it.isNotBlank() && okWeekdays(it) }
            val sig = signature(p)
            val existing = dao.proposal(sig)
            // updateOf 는 새 시그니처가 비어 있을 때만 이관 대상이 된다 —
            // 이미 그 시그니처의 카드가 있으면 그쪽 갱신으로 흡수한다.
            val base = if (existing == null) p.updateOf?.trim()?.let { dao.proposal(it) } else null

            when {
                existing != null -> {
                    // 상태·노출 이력은 그대로, 내용만 최신 분석으로 갱신한다.
                    // 조건은 이미 있으면 안 건드린다 — 정제·실험의 결과는
                    // 심층 분석이 소유하고, 1차는 백지에만 처음 잣대를 놓는다.
                    dao.upsertProposal(
                        existing.copy(
                            oneLine = p.oneLine,
                            narrative = p.narrative,
                            evidenceJson = json.encodeToString(p.evidence),
                            confidence = p.confidence,
                            samsungCondition = p.samsungCondition,
                            samsungAction = p.samsungAction,
                            conditionHours = existing.conditionHours ?: condH,
                            conditionWeekdays = existing.conditionWeekdays ?: condW,
                            updatedAt = now,
                        )
                    )
                    dao.logProposalEvent(ProposalEventRow(ts = now, proposalSignature = sig, kind = "updated"))
                    updated++
                }
                base != null -> {
                    // 기존 제안의 진화(updateOf) — 상태와 결정 이력을 유지한 채
                    // 내용·파라미터만 바꾼다. 시그니처가 달라지면 이력·실험의
                    // 참조를 먼저 이관한다.
                    if (base.signature != sig) {
                        dao.migrateProposalEvents(base.signature, sig)
                        dao.migrateExperiments(base.signature, sig)
                        dao.deleteProposal(base.signature)
                    }
                    dao.upsertProposal(
                        base.copy(
                            signature = sig,
                            category = p.category,
                            oneLine = p.oneLine,
                            narrative = p.narrative,
                            triggerType = p.trigger.type,
                            triggerParam = p.trigger.param,
                            actionType = p.action.type,
                            actionParams = json.encodeToString(p.action.params),
                            samsungCondition = p.samsungCondition,
                            samsungAction = p.samsungAction,
                            evidenceJson = json.encodeToString(p.evidence),
                            confidence = p.confidence,
                            conditionHours = base.conditionHours ?: condH,
                            conditionWeekdays = base.conditionWeekdays ?: condW,
                            updatedAt = now,
                        )
                    )
                    dao.logProposalEvent(ProposalEventRow(ts = now, proposalSignature = sig, kind = "updated"))
                    updated++
                }
                else -> {
                    dao.upsertProposal(
                        ProposalRow(
                            signature = sig,
                            category = p.category,
                            oneLine = p.oneLine,
                            narrative = p.narrative,
                            triggerType = p.trigger.type,
                            triggerParam = p.trigger.param,
                            actionType = p.action.type,
                            actionParams = json.encodeToString(p.action.params),
                            samsungCondition = p.samsungCondition,
                            samsungAction = p.samsungAction,
                            evidenceJson = json.encodeToString(p.evidence),
                            confidence = p.confidence,
                            state = "candidate",
                            createdAt = now,
                            updatedAt = now,
                            conditionHours = condH,
                            conditionWeekdays = condW,
                        )
                    )
                    dao.logProposalEvent(ProposalEventRow(ts = now, proposalSignature = sig, kind = "generated"))
                    added++
                }
            }
        }

        // 은퇴는 LLM 의 명시 판정(retire)만 따른다. 예전의 "이번 출력에 다시
        // 안 나오면 걷어내기"는 증분 규칙("있는 건 다시 내지 마라")과 정면
        // 충돌이라 제거했다 — 그 규칙 아래서는 안 나오는 게 정상이다.
        for (r in parsed.retire) {
            val row = dao.proposal(r.signature.trim()) ?: continue
            // 사용자가 결정한 것(수락·거절·스누즈)은 LLM 이 걷지 못한다.
            if (row.state != "candidate") continue
            dao.logProposalEvent(
                ProposalEventRow(ts = now, proposalSignature = row.signature, kind = "retired")
            )
            dao.deleteProposal(row.signature)
            retired++
        }

        return Result.success(
            Outcome(added, updated, dropped, retired, parsed.rejected, parsed.analysisNote)
        )
    }

    // 조건 형식 검증 — deep-analysis 와 같은 규약(시간 끝 미포함·자정 넘김
    // 허용·범위 끝 24 허용, 요일 1-7 끝 포함). PatternWatcher.matchesSpec 이
    // 읽을 수 있는 것만 저장한다.
    private fun okHours(spec: String?): Boolean = okSpec(spec, 0, 23, rangeEndMax = 24)
    private fun okWeekdays(spec: String?): Boolean = okSpec(spec, 1, 7)

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

    /**
     * 병합 키. LLM 의 id 는 실행마다 달라질 수 있으므로 쓰지 않는다.
     * params 는 정렬한다 — 앱페어의 [A,B] 와 [B,A] 는 같은 제안인데 실행마다
     * 순서가 뒤집혀 카드가 중복 생성됐다.
     */
    private fun signature(p: LlmProposal): String =
        "${p.trigger.type}:${p.trigger.param.orEmpty().trim()}>" +
            "${p.action.type}:${p.action.params.map { it.trim() }.sorted().joinToString(",")}"

    /** 허용 목록 검증. 어긴 제안은 조용히 버리지 않고 dropped 로 센다. */
    private fun valid(p: LlmProposal): Boolean =
        p.category in CATEGORIES &&
            p.trigger.type in TRIGGER_TYPES &&
            p.action.type in ACTION_TYPES &&
            p.oneLine.isNotBlank() &&
            p.narrative.isNotBlank() &&
            p.evidence.isNotEmpty()

    /** 제안 탭 하단에 보일 분석 노트의 보관 형식 */
    @Serializable
    data class StoredNote(
        val analysisNote: String = "",
        val rejected: List<LlmRejected> = emptyList(),
        val at: Long = 0,
    )

    companion object {
        private val noteJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encodeNote(n: StoredNote): String = noteJson.encodeToString(StoredNote.serializer(), n)

        fun decodeNote(s: String): StoredNote =
            runCatching { noteJson.decodeFromString(StoredNote.serializer(), s) }
                .getOrDefault(StoredNote())

        val CATEGORIES = setOf(
            "trigger_routine", "app_pair", "app_mode", "notif_cleanup", "time_shortcut"
        )
        val TRIGGER_TYPES = setOf(
            "bt_connect", "bt_disconnect", "wifi_connect", "wifi_disconnect",
            "app_launch", "time", "exercise_start"
        )
        val ACTION_TYPES = setOf(
            "launch_app", "app_pair", "mode_rotation", "mode_eye_comfort",
            "mode_dnd", "notif_channel_off"
        )
    }
}
