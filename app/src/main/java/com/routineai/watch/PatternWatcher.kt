package com.routineai.watch

import android.content.Context
import android.util.Log
import com.routineai.data.Db
import com.routineai.data.KvRow
import com.routineai.data.ProposalEventRow
import com.routineai.data.ProposalRow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 트리거 매칭의 두뇌 — 모든 감지 경로의 **단일 입구**.
 *
 * 감지 경로는 여럿이다(폴링·브로드캐스트·접근성). 빠른 경로는 즉시 보고하고
 * 끊기지 않는 경로는 늦게 한 번 더 보고하므로, 같은 사건이 두 번 들어올 수
 * 있다. 경로끼리 서로를 알게 하는 대신 여기 입구의 관문([signal])에서 접는다 —
 * 새 감지 경로를 붙일 때 중복 걱정 없이 on* 메서드만 부르면 되는 이유다.
 *
 * 제안의 수명주기가 여기서 갈린다:
 *  - candidate  → 패턴이 감지되면 **팝업으로 제안**한다 (사용자 결정 대기)
 *  - accepted   → 같은 팝업으로 매번 여쭤본다. **자동 실행은 없다**(사용자
 *                 결정으로 보류): 모든 실행은 사용자의 탭이다.
 *  - snoozed    → '이번엔 아님' 기록. 지금은 candidate 와 같게 다시 제안된다
 *  - dismissed  → 영원히 무시
 *
 * 억제 장치 중 둘은 보류 상태다(사용자 결정) — 기록은 전부 남으므로 심층
 * 분석의 입력은 유지되고, 코드 몇 줄을 되살리면 다시 켜진다:
 *  - 맥락 버킷 거절 학습(같은 버킷 2회 거절 시 침묵) → [rejectedHereBefore]
 *  - 방해 예산(24시간 서로 다른 후보 3개·카테고리 1개) → [canSurface]
 * 지금 남은 억제는 신호 관문(5초)·재노출 간격(20초)·팝업 자동 소멸(12초)·
 * '보지 않기'뿐이다.
 *
 * 앱 맥락 모드(app_mode)는 진입/이탈이 쌍이라 따로 다룬다 — 들어갈 때 설정을
 * 바꾸고 나올 때 되돌린다. 되돌리지 않으면 사용자의 기기 설정을 몰래 바꿔놓은
 * 셈이 되므로, 이탈 복구는 선택이 아니라 필수다.
 */
object PatternWatcher {

    private const val TAG = "PatternWatcher"

    /**
     * 같은 제안을 다시 띄우기까지의 최소 간격 — 후보·수락 공통.
     * 길게 두면 데모에서 "왜 안 뜨지"가 되고, 진짜 억제는 방해 예산과
     * 12초 자동 소멸이 맡는다(사용자 결정으로 짧게 통일).
     */
    const val REPEAT_GAP_MS = 20L * 1000

    /** (보류 중) 하루 방해 예산 — 24시간 안에 서로 다른 후보 이만큼만 */
    const val DAILY_BUDGET = 3
    @Suppress("unused")
    private const val PER_CATEGORY_BUDGET = 1

    /** (보류 중) 같은 맥락 버킷에서 이만큼 거절하면 그 버킷은 조용해진다 */
    const val BUCKET_REJECT_LIMIT = 2

    /**
     * 같은 (신호, 파라미터)가 이 안에 다시 오면 같은 사건의 중복 보고로 본다.
     * 가장 느린 경로(1.5초 폴링)의 지연을 넉넉히 덮되, "나갔다 바로 다시
     * 들어옴" 같은 진짜 재발생은 삼키지 않을 만큼만 짧게.
     */
    private const val SIGNAL_DEDUP_MS = 5_000L
    private val recentSignals = HashMap<String, Long>()

    /** app_mode 로 바꿔둔 설정. 이탈 시 되돌리기 위해 기억한다. */
    private val activeModes = HashMap<String, ProposalRow>()

    private var lastForegroundPkg: String? = null

    suspend fun onAppForeground(ctx: Context, pkg: String) {
        // 모드 복구와 앵커 갱신은 멱등이라 관문 앞에서 한다 — 중복 보고가
        // 와도 같은 앱이면 되돌릴 것도, 바뀔 것도 없다.
        val leaving = activeModes.keys.filter { it != pkg }
        for (key in leaving) {
            activeModes.remove(key)?.let { revertMode(ctx, it) }
        }
        if (leaving.isNotEmpty()) persistModes(ctx)
        lastForegroundPkg = pkg
        // 앱페어는 둘 중 아무 쪽을 열어도 성립한다. triggerParam 하나로만
        // 매칭하면 한쪽에서만 뜨는데, 실제로 그래서 토스에서는 안 떴다.
        signal(ctx, "app_launch", pkg) {
            it.triggerParam == pkg ||
                (it.actionType == "app_pair" && pkg in Applier.params(it))
        }
    }

    suspend fun onBluetooth(ctx: Context, connected: Boolean, name: String) {
        BtState.update(name, connected)
        val type = if (connected) "bt_connect" else "bt_disconnect"
        signal(ctx, type, name) { it.triggerParam.isNullOrBlank() || it.triggerParam == name }
    }

    suspend fun onNetwork(ctx: Context, wifi: Boolean, alias: String?) {
        val type = if (wifi) "wifi_connect" else "wifi_disconnect"
        signal(ctx, type, alias) { it.triggerParam.isNullOrBlank() || it.triggerParam == alias }
    }

    /** @param hhmm "HH:mm" — proposal-rules.md 의 time 트리거 형식과 같다 */
    suspend fun onTime(ctx: Context, hhmm: String) {
        signal(ctx, "time", hhmm) { it.triggerParam == hhmm }
    }

    suspend fun onExercise(ctx: Context, name: String) {
        signal(ctx, "exercise_start", name) {
            it.triggerParam.isNullOrBlank() || it.triggerParam == name
        }
    }

    // ------------------------------------------------------------------

    /**
     * 단일 관문. 모든 on* 이 여기를 지나며, 같은 사건의 중복 보고는
     * 여기서 죽는다. 관문을 통과한 신호만 [dispatch] 로 간다.
     */
    private suspend fun signal(
        ctx: Context,
        triggerType: String,
        param: String?,
        paramMatches: (ProposalRow) -> Boolean,
    ) {
        val now = System.currentTimeMillis()
        val key = "$triggerType:${param.orEmpty()}"
        synchronized(recentSignals) {
            val last = recentSignals[key]
            recentSignals[key] = now
            // 오래된 키가 무한히 쌓이지 않게 이따금 청소한다.
            if (recentSignals.size > 64) {
                recentSignals.entries.removeAll { now - it.value > 60_000 }
            }
            if (last != null && now - last < SIGNAL_DEDUP_MS) {
                Log.i(TAG, "중복 신호 접음: $key")
                return
            }
        }
        dispatch(ctx, triggerType, param, paramMatches)
    }

    private suspend fun dispatch(
        ctx: Context,
        triggerType: String,
        param: String?,
        paramMatches: (ProposalRow) -> Boolean,
    ) {
        val dao = Db.get(ctx).dao()
        val now = System.currentTimeMillis()
        val here = DecisionContext.capture(ctx, "", "probe", lastForegroundPkg, now)
        val hereBucket = DecisionContext.bucket(here)

        val matched = dao.proposals().filter { it.triggerType == triggerType && paramMatches(it) }
        if (matched.isEmpty()) return

        // 1) 수락된 루틴 — 실행은 언제나 사용자의 탭이다. 후보와 같은 팝업으로
        //    매번 여쭤본다. P3 가 좁힌 조건 밖에서는 침묵.
        for (p in matched.filter { it.state == "accepted" }) {
            if (!inCondition(p, here)) continue
            if (canSurface(p, now)) {
                surface(ctx, p, now, shortcut = false)
                return
            }
        }

        // 2) 후보 — 본 적 없는 맥락에서만 물어본다.
        for (p in matched.filter { it.state !in setOf("accepted", "dismissed") }) {
            if (!canSurface(p, now)) continue
            if (rejectedHereBefore(dao, p, hereBucket)) continue
            surface(ctx, p, now, shortcut = false)
            return   // 한 번에 하나만 띄운다
        }
    }

    private suspend fun surface(ctx: Context, p: ProposalRow, now: Long, shortcut: Boolean) {
        Db.get(ctx).dao().let { dao ->
            dao.upsertProposal(p.copy(surfacedCount = p.surfacedCount + 1, lastSurfacedAt = now))
            dao.logProposalEvent(
                DecisionContext.capture(ctx, p.signature, "surfaced", lastForegroundPkg, now)
            )
        }
        SuggestionOverlay.show(ctx, p, shortcut, lastForegroundPkg)
    }

    /**
     * 이 제안을 다시 보이면 안 되는가.
     *
     * '보지 않기'(dismissed)는 명시적 지시라 항상 지킨다. 맥락 버킷 거절
     * 학습은 보류 중이다(사용자 결정) — 기록은 계속 남으므로 아래 주석을
     * 되살리면 다시 켜진다.
     */
    private suspend fun rejectedHereBefore(
        dao: com.routineai.data.UsageDao,
        p: ProposalRow,
        hereBucket: String,
    ): Boolean {
        val rejects = dao.decisions(p.signature)
            .filter { it.kind == "not_now" || it.kind == "dismissed" }
        if (rejects.any { it.kind == "dismissed" }) return true
        // return rejects.count { DecisionContext.bucket(it) == hereBucket } >= BUCKET_REJECT_LIMIT
        return false
    }

    /**
     * 연타 방지만 남았다. 방해 예산(24시간 서로 다른 후보 3개·카테고리 1개)은
     * 보류 중이다(사용자 결정) — 노출 기록(surfaced)은 전부 남으므로 얼마나
     * 자주 띄웠고 무엇이 무시됐는지는 심층 분석이 이력에서 읽는다.
     * 되살리려면: lastSurfacedAt 이 24시간 내인 비수락 제안 수를 세어
     * [DAILY_BUDGET]·[PER_CATEGORY_BUDGET] 과 비교하면 된다.
     */
    private fun canSurface(p: ProposalRow, now: Long): Boolean =
        now - (p.lastSurfacedAt ?: 0L) >= REPEAT_GAP_MS

    /** P3 가 조건을 좁혀두었으면 그 조건 밖에서는 뜨지 않는다 */
    private fun inCondition(p: ProposalRow, here: ProposalEventRow): Boolean {
        p.conditionHours?.let { spec ->
            val ok = spec.split(',').any { part ->
                val r = part.trim().split('-')
                if (r.size == 2) here.hour >= r[0].toInt() && here.hour < r[1].toInt()
                else here.hour == part.trim().toIntOrNull()
            }
            if (!ok) return false
        }
        p.conditionWeekdays?.let { spec ->
            val ok = spec.split(',').any { part ->
                val r = part.trim().split('-')
                if (r.size == 2) here.weekday >= r[0].toInt() && here.weekday <= r[1].toInt()
                else here.weekday == part.trim().toIntOrNull()
            }
            if (!ok) return false
        }
        return true
    }

    /**
     * 팝업·제안 탭의 '적용'이 app_mode 를 실제로 켰을 때 부른다 — 이탈 원복을
     * 걸어두는 유일한 통로다. 자동 실행이 없어졌으므로 적용 지점이 직접
     * 알려줘야 한다(이걸 빼먹으면 회전 잠금이 영영 안 풀린다).
     */
    suspend fun onModeApplied(ctx: Context, p: ProposalRow) = rememberMode(ctx, p)

    private suspend fun rememberMode(ctx: Context, p: ProposalRow) {
        Applier.params(p).firstOrNull()?.let { activeModes[it] = p }
        persistModes(ctx)
    }

    private fun revertMode(ctx: Context, p: ProposalRow) {
        when (p.actionType) {
            "mode_rotation" -> Applier.rotation(ctx, on = false)
            "mode_dnd" -> Applier.dnd(ctx, on = false)
        }
        Log.i(TAG, "모드 원복: ${p.oneLine}")
    }

    // ------------------------------------------------------------------

    /**
     * 걸어둔 모드를 KV 에도 남긴다. 메모리에만 두면 프로세스가 죽는 순간
     * "누가 회전 잠금을 걸었는지"를 아무도 모르게 되고, 사용자 설정이 바뀐 채
     * 남는다 — 이탈 복구는 선택이 아니라 필수라는 원칙이 재시작을 건너서도
     * 지켜져야 한다.
     */
    private suspend fun persistModes(ctx: Context) {
        val map: Map<String, String> = activeModes.mapValues { it.value.signature }
        Db.get(ctx).dao().put(KvRow(KEY_ACTIVE_MODES, Json.encodeToString(map)))
    }

    /** 감시 서비스가 뜰 때 한 번 — 지난 프로세스가 못 되돌린 모드를 되돌린다. */
    suspend fun restoreModes(ctx: Context) {
        val dao = Db.get(ctx).dao()
        val raw = dao.get(KEY_ACTIVE_MODES) ?: return
        val map = runCatching { Json.decodeFromString<Map<String, String>>(raw) }
            .getOrDefault(emptyMap())
        if (map.isEmpty()) return
        for ((_, sig) in map) {
            dao.proposal(sig)?.let {
                Log.i(TAG, "지난 세션의 모드 복구: ${it.oneLine}")
                revertMode(ctx, it)
            }
        }
        dao.put(KvRow(KEY_ACTIVE_MODES, "{}"))
    }

    private const val KEY_ACTIVE_MODES = "active_modes"
}
