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
    )

    @Serializable data class LlmTrigger(val type: String, val param: String? = null)
    @Serializable data class LlmAction(val type: String, val params: List<String> = emptyList())
    @Serializable data class LlmRejected(val candidate: String, val reason: String)

    data class Outcome(
        val added: Int,
        val updated: Int,
        val dropped: Int,
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

        onStep("제안 요청 중 (최대 2분)")
        val raw = Interpreter(ctx).propose(reportJson, cfg).getOrElse { return Result.failure(it) }

        val parsed = runCatching { json.decodeFromString(LlmOutput.serializer(), raw) }
            .getOrElse { return Result.failure(IllegalStateException("제안 JSON 파싱 실패: ${it.message}")) }

        onStep("제안 정리 중")
        var added = 0; var updated = 0; var dropped = 0
        val now = System.currentTimeMillis()

        for (p in parsed.proposals) {
            if (!valid(p)) { dropped++; continue }
            val sig = signature(p)
            val existing = dao.proposal(sig)
            if (existing == null) {
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
                    )
                )
                dao.logProposalEvent(ProposalEventRow(ts = now, proposalSignature = sig, kind = "generated"))
                added++
            } else {
                // 상태·노출 이력은 그대로, 내용만 최신 분석으로 갱신한다.
                dao.upsertProposal(
                    existing.copy(
                        oneLine = p.oneLine,
                        narrative = p.narrative,
                        evidenceJson = json.encodeToString(p.evidence),
                        confidence = p.confidence,
                        samsungCondition = p.samsungCondition,
                        samsungAction = p.samsungAction,
                        updatedAt = now,
                    )
                )
                dao.logProposalEvent(ProposalEventRow(ts = now, proposalSignature = sig, kind = "updated"))
                updated++
            }
        }

        // 이번 분석이 다시 내지 않은 후보 중, 한 번도 노출·결정되지 않은 것은
        // 걷어낸다. 남겨두면 분석을 돌릴 때마다 낡은 후보가 쌓인다.
        // 수락·거절·스누즈된 것과 노출 이력이 있는 것은 사용자의 상태이므로 유지.
        val currentSigs = parsed.proposals.filter { valid(it) }.map { signature(it) }.toSet()
        var pruned = 0
        for (row in dao.proposals()) {
            if (row.state == "candidate" && row.surfacedCount == 0 &&
                row.signature !in currentSigs
            ) {
                dao.logProposalEvent(
                    ProposalEventRow(ts = now, proposalSignature = row.signature, kind = "superseded")
                )
                dao.deleteProposal(row.signature)
                pruned++
            }
        }

        return Result.success(
            Outcome(added, updated, dropped, parsed.rejected, parsed.analysisNote)
        )
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
