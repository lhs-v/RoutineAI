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
    private const val SNOOZE_MS = 6L * 60 * 60 * 1000
    /** 같은 제안을 이 간격 안에 다시 띄우지 않는다 */
    private const val RESURFACE_MS = 30L * 60 * 1000

    /** app_mode 로 바꿔둔 설정. 이탈 시 되돌리기 위해 기억한다. */
    private val activeModes = HashMap<String, ProposalRow>()

    suspend fun onAppForeground(ctx: Context, pkg: String) {
        // 1) 떠난 앱의 모드 복구가 먼저다.
        val leaving = activeModes.keys.filter { it != pkg }
        for (key in leaving) {
            activeModes.remove(key)?.let { revertMode(ctx, it) }
        }
        dispatch(ctx, "app_launch", pkg) { it.triggerParam == pkg }
    }

    suspend fun onBluetooth(ctx: Context, connected: Boolean, name: String) {
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
        val hits = dao.proposals().filter {
            it.triggerType == triggerType && paramMatches(it) && isLive(it, now)
        }
        if (hits.isEmpty()) return
        Log.i(TAG, "트리거 $triggerType($param) → 제안 ${hits.size}건")

        for (p in hits) {
            if (p.state == "accepted") {
                // 의도가 확인된 루틴 — 묻지 않고 실행한다.
                val r = Applier.apply(ctx, p)
                if (p.category == "app_mode" && r.ok) rememberMode(p)
                dao.logProposalEvent(
                    ProposalEventRow(
                        ts = now, proposalSignature = p.signature,
                        kind = if (r.ok) "auto_applied" else "auto_failed"
                    )
                )
                Log.i(TAG, "자동 실행: ${p.oneLine} → ${r.message}")
            } else {
                // 아직 후보 — 팝업으로 물어본다.
                dao.upsertProposal(
                    p.copy(surfacedCount = p.surfacedCount + 1, lastSurfacedAt = now)
                )
                dao.logProposalEvent(
                    ProposalEventRow(ts = now, proposalSignature = p.signature, kind = "surfaced")
                )
                SuggestionOverlay.show(ctx, p)
                return   // 한 번에 하나만 띄운다
            }
        }
    }

    /** 지금 이 제안을 살펴볼 상태인가 */
    private fun isLive(p: ProposalRow, now: Long): Boolean = when (p.state) {
        "accepted" -> true
        "dismissed" -> false
        "snoozed" -> now - p.updatedAt > SNOOZE_MS
        else -> (p.lastSurfacedAt ?: 0L).let { now - it > RESURFACE_MS }
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
