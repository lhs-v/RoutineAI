package com.routineai.watch

import android.content.Context
import android.util.Log
import com.routineai.data.Db
import com.routineai.data.ProposalEventRow
import com.routineai.data.ProposalRow

/**
 * 트리거 매칭의 두뇌.
 *
 * 제안의 수명주기가 여기서 갈린다:
 *  - candidate  → 패턴이 감지되면 **팝업으로 제안**한다 (사용자 결정 대기)
 *  - accepted   → 이미 의도가 확인됐으므로 **말없이 실행**한다
 *  - snoozed    → 쿨다운(6시간) 동안 조용, 지나면 다시 감시
 *  - dismissed  → 영원히 무시
 *
 * 앱 맥락 모드(app_mode)는 진입/이탈이 쌍이라 따로 다룬다 — 들어갈 때 설정을
 * 바꾸고 나올 때 되돌린다. 되돌리지 않으면 사용자의 기기 설정을 몰래 바꿔놓은
 * 셈이 되므로, 이탈 복구는 선택이 아니라 필수다.
 */
object PatternWatcher {

    private const val TAG = "PatternWatcher"

    /** 같은 제안을 이 간격 안에 다시 띄우지 않는다 (연타 방지용 최소 간격) */
    const val RESURFACE_MS = 20L * 60 * 1000

    /** 하루 방해 예산. 좋은 제안이라도 이만큼 넘으면 제안 탭에 조용히 쌓인다. */
    const val DAILY_BUDGET = 3
    private const val PER_CATEGORY_BUDGET = 1

    /** 같은 맥락 버킷에서 이만큼 거절하면 그 버킷은 조용해진다 */
    const val BUCKET_REJECT_LIMIT = 2

    /** app_mode 로 바꿔둔 설정. 이탈 시 되돌리기 위해 기억한다. */
    private val activeModes = HashMap<String, ProposalRow>()

    private var lastForegroundPkg: String? = null

    suspend fun onAppForeground(ctx: Context, pkg: String) {
        // 1) 떠난 앱의 모드 복구가 먼저다.
        val leaving = activeModes.keys.filter { it != pkg }
        for (key in leaving) {
            activeModes.remove(key)?.let { revertMode(ctx, it) }
        }
        lastForegroundPkg = pkg
        // 앱페어는 둘 중 아무 쪽을 열어도 성립한다. triggerParam 하나로만
        // 매칭하면 한쪽에서만 뜨는데, 실제로 그래서 토스에서는 안 떴다.
        dispatch(ctx, "app_launch", pkg) {
            it.triggerParam == pkg ||
                (it.actionType == "app_pair" && pkg in Applier.params(it))
        }
    }

    suspend fun onBluetooth(ctx: Context, connected: Boolean, name: String) {
        BtState.update(name, connected)
        val type = if (connected) "bt_connect" else "bt_disconnect"
        dispatch(ctx, type, name) { it.triggerParam.isNullOrBlank() || it.triggerParam == name }
    }

    suspend fun onNetwork(ctx: Context, wifi: Boolean, alias: String?) {
        val type = if (wifi) "wifi_connect" else "wifi_disconnect"
        dispatch(ctx, type, alias) { it.triggerParam.isNullOrBlank() || it.triggerParam == alias }
    }

    // ------------------------------------------------------------------

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

        // 1) 이미 수락된 루틴 — 사용자가 자동 실행으로 승격한 것만 말없이 실행한다.
        for (p in matched.filter { it.state == "accepted" }) {
            if (!inCondition(p, here)) continue
            if (p.autoRun) {
                val r = Applier.apply(ctx, p, anchor = lastForegroundPkg)
                if (p.category == "app_mode" && r.ok) rememberMode(p)
                dao.logProposalEvent(
                    DecisionContext.capture(
                        ctx, p.signature,
                        if (r.ok) "auto_applied" else "auto_failed", lastForegroundPkg, now
                    )
                )
                Log.i(TAG, "자동 실행: ${p.oneLine} → ${r.message}")
            } else if (canSurface(ctx, p, now)) {
                surface(ctx, p, now, shortcut = true)
                return
            }
        }

        // 2) 후보 — 본 적 없는 맥락에서만 물어본다.
        for (p in matched.filter { it.state !in setOf("accepted", "dismissed") }) {
            if (!canSurface(ctx, p, now)) continue
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
     * 이 맥락 버킷에서 이미 거절한 적이 있는가.
     *
     * 시간으로 덮지 않는 이유: 22시에 거절했다고 09시에도 안 띄우면, 정작
     * 사용자가 원했을 상황을 영영 만나지 못한다. 거절은 "이 제안이 싫다"가
     * 아니라 "이 조건이 너무 넓다"는 신호일 수 있고, 그 구분은 다른 맥락에서
     * 한 번 더 물어봐야 얻어진다.
     */
    private suspend fun rejectedHereBefore(
        dao: com.routineai.data.UsageDao,
        p: ProposalRow,
        hereBucket: String,
    ): Boolean {
        val rejects = dao.decisions(p.signature)
            .filter { it.kind == "not_now" || it.kind == "dismissed" }
        if (rejects.any { it.kind == "dismissed" }) return true
        return rejects.count { DecisionContext.bucket(it) == hereBucket } >= BUCKET_REJECT_LIMIT
    }

    /** 방해 예산 + 연타 방지 */
    private suspend fun canSurface(ctx: Context, p: ProposalRow, now: Long): Boolean {
        if (now - (p.lastSurfacedAt ?: 0L) < RESURFACE_MS) return false
        val dao = Db.get(ctx).dao()
        val since = now - 24L * 60 * 60 * 1000
        val today = dao.proposals().filter { (it.lastSurfacedAt ?: 0L) > since }
        if (today.size >= DAILY_BUDGET) {
            Log.i(TAG, "방해 예산 소진 — ${p.oneLine} 은 제안 탭에만 쌓는다")
            return false
        }
        if (today.count { it.category == p.category } >= PER_CATEGORY_BUDGET) return false
        return true
    }

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

    private fun rememberMode(p: ProposalRow) {
        Applier.params(p).firstOrNull()?.let { activeModes[it] = p }
    }

    private fun revertMode(ctx: Context, p: ProposalRow) {
        when (p.actionType) {
            "mode_rotation" -> Applier.rotation(ctx, on = false)
            "mode_dnd" -> Applier.dnd(ctx, on = false)
        }
        Log.i(TAG, "모드 원복: ${p.oneLine}")
    }
}
