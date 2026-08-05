package com.routineai.watch

import android.content.Context
import android.util.Log
import com.routineai.data.Db
import com.routineai.data.KvRow
import com.routineai.data.ProposalEventRow
import com.routineai.data.ProposalRow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * 지금 앞 앱이 앞으로 나온 시각 — "이번 방문"의 시작. 접근성은 앱 안의
     * 화면 전환도 재보고하므로, 방문 개념이 없으면 같은 앱에 머무는 동안
     * 20초 간격마다 알약이 반복된다(실측 신고).
     */
    private var foregroundSince = 0L

    /** 앱별 마지막 목격 시각 — 앱페어의 "이미 함께 쓰는 중" 판정 재료 */
    private val pkgLastSeen = HashMap<String, Long>()

    /** 이 안에 상대 앱을 썼다면 페어를 이미 함께 쓰는 중이다 — 제안이 무의미 */
    private const val PAIR_FLOW_MS = 3L * 60 * 1000

    // ---- 루틴 중심 로그: 루틴 자체의 삶을 기록한다 ----
    //
    // 사용자 중심 로그(앱 사용·결정)만으로는 "이 루틴이 잘 살고 있는가"를
    // 모른다. 실행이 유지로 이어졌는지, 조건이 기회를 죽이고 있는지,
    // 무시 뒤에 사용자가 스스로 무엇을 열었는지 — 실험 루프의 측정 기반이다.

    /**
     * 실행 결과 측정 대기. 유지/이탈을 **판정하지 않는다** — 앱마다 정상
     * 체류가 다르므로(잔고 확인 20초는 완료, 영상 20초는 어긋남) 경계를
     * 어디 긋든 자의적이다. 머문 시간만 재고 판단은 심층 분석이 그 앱의
     * 평소 체류(secondsPerLaunch)와 비교해서 한다.
     */
    private data class PendingOutcome(val signature: String, val pkg: String, val at: Long)
    private val pendingOutcomes = ArrayList<PendingOutcome>()

    /** 이 이상 머물면 "충분히 오래"다 — 더 세지 않고 상한값으로 기록한다. */
    private const val OUTCOME_CAP_S = 300

    /** 조건 밖 근접 발화의 시그니처별 마지막 기록 시각 — 기록 자체의 스팸 방지 */
    private val lastNearMiss = HashMap<String, Long>()
    private const val NEAR_MISS_GAP_MS = 30L * 60 * 1000

    /** 같은 신호에서 순서에 밀린(가려진) 제안의 기록 스팸 방지 — near_miss 와 같은 간격 */
    private val lastEclipsed = HashMap<String, Long>()

    /** 이 시간 안의 재등장은 같은 방문의 연장으로 본다 — 종료 애니메이션·PIP 재보고 흡수 */
    private const val VISIT_REJOIN_MS = 15_000L

    /** 현재 방문이 "떠난 직후 되돌아온" 것인가 — 방문이 끝날 때까지 유지된다 */
    private var reenteredRecently = false

    /**
     * 지금 처리 중인 보고가 방문을 연 보고인가. 앱 트리거(비페어)는 이때만
     * 발화할 수 있다 — 방문 중의 재보고(새로고침·게시글 클릭 등 화면 상태
     * 변화)가 진입 때 조용했던 제안을 뒤늦게 띄우면 안 된다(실측 신고).
     */
    private var visitOpening = false

    /** 팝업 무반응 소멸 직후, 사용자가 스스로 여는 첫 앱을 기다린다 */
    private data class PendingIgnore(val signature: String, val at: Long)
    @Volatile private var pendingIgnore: PendingIgnore? = null
    private const val IGNORE_FOLLOW_MS = 60_000L

    private var cachedLauncher: String? = null
    private fun launcherPkg(ctx: Context): String = cachedLauncher ?: run {
        val i = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        val p = ctx.packageManager.resolveActivity(i, 0)?.activityInfo?.packageName.orEmpty()
        cachedLauncher = p
        p
    }

    /** 팝업의 '적용'이 launch_app 을 실제로 실행했을 때 — 결과 판정을 건다. */
    fun noteApplied(signature: String, launchedPkg: String?) {
        launchedPkg ?: return
        synchronized(pendingOutcomes) {
            pendingOutcomes += PendingOutcome(signature, launchedPkg, System.currentTimeMillis())
        }
    }

    /** 팝업이 무반응으로 소멸했을 때 — 직후의 자발적 행동이 제3의 후보 단서다. */
    fun noteIgnored(signature: String) {
        pendingIgnore = PendingIgnore(signature, System.currentTimeMillis())
    }

    /**
     * 다른 앱으로 옮겨간 순간이 체류의 끝이다. [onAppForeground] 가 부른다.
     * foreground 에는 떠나서 간 곳이 남는다 — 어디로 갔는지도 신호다.
     */
    private suspend fun finalizeOutcomes(ctx: Context, nowPkg: String) {
        val now = System.currentTimeMillis()
        val done = synchronized(pendingOutcomes) {
            val d = pendingOutcomes.filter { it.pkg != nowPkg }
            pendingOutcomes.removeAll(d.toSet())
            d
        }
        for (o in done) {
            DecisionContext.log(
                ctx, o.signature, "outcome", nowPkg,
                dwellSeconds = ((now - o.at) / 1000).toInt().coerceIn(0, OUTCOME_CAP_S),
            )
        }
    }

    /**
     * 감시 루프가 주기적으로 부른다. 전환 없이 상한까지 머문 경우를 확정한다 —
     * 그 이상은 "충분히 오래"라 더 셀 필요가 없다.
     */
    suspend fun evaluateOutcomes(ctx: Context) = gate.withLock {
        val now = System.currentTimeMillis()
        val capped = synchronized(pendingOutcomes) {
            val d = pendingOutcomes.filter { now - it.at >= OUTCOME_CAP_S * 1000L }
            pendingOutcomes.removeAll(d.toSet())
            d
        }
        for (o in capped) {
            DecisionContext.log(
                ctx, o.signature, "outcome", lastForegroundPkg,
                dwellSeconds = OUTCOME_CAP_S,
            )
        }
    }

    /**
     * 진입 직렬화. 접근성(이벤트별 코루틴)·폴링 루프·오버레이가 서로 다른
     * 스레드에서 들어오는데, activeModes·pending* ·lastForegroundPkg 는
     * 일반 컬렉션이다 — 동시 진입이 ConcurrentModificationException 이나
     * 모드 원복 유실로 이어질 수 있어(검토에서 확인) 문 하나로 세운다.
     */
    private val gate = Mutex()

    suspend fun onAppForeground(ctx: Context, pkg: String) = gate.withLock {
        // 모드 복구와 앵커 갱신은 멱등이라 관문 앞에서 한다 — 중복 보고가
        // 와도 같은 앱이면 되돌릴 것도, 바뀔 것도 없다.
        val leaving = activeModes.keys.filter { it != pkg }
        for (key in leaving) {
            activeModes.remove(key)?.let { revertMode(ctx, it) }
        }
        if (leaving.isNotEmpty()) persistModes(ctx)
        val nowTs = System.currentTimeMillis()
        // 도장을 찍기 전에 읽는다 — "이 앱을 마지막으로 본 게 언제였나"가
        // 아래 재진입 판정의 재료다.
        val prevSeen = pkgLastSeen[pkg]
        val newVisit = lastForegroundPkg != pkg
        if (newVisit) {
            foregroundSince = nowTs
            // 떠나는 앱에도 도장을 찍는다 — "상대 앱을 3분 내 썼는가"(수락
            // 페어의 흐름 억제, 후보 페어의 전환 게이트)를 입장 시각만으로
            // 판정하면 긴 체류 끝의 전환을 놓친다.
            lastForegroundPkg?.let { pkgLastSeen[it] = nowTs }
        }
        // 떠난 직후의 재등장은 새 방문이 아니다. 앱을 끄는 과정(종료 애니메이션,
        // PIP 전환)에서 시스템이 그 앱을 잠깐 다시 전면으로 보고하는데, 이게
        // 새 방문으로 잡히면 방문 스코프가 리셋되어 "앱을 끌 때 같은 제안이
        // 또 뜨는" 증상이 된다(실측 신고). 페어는 예외 — 빠른 왕복 자체가
        // 관찰 대상이라 dispatch 의 페어 게이트가 따로 판단한다.
        // 방문이 이어지는 동안(같은 앱 재보고)에는 갱신하지 않는다 — 매 보고마다
        // 다시 계산하면 방문 중 첫 재보고에서 풀려 버린다(실측: 새로고침에 재발화).
        if (newVisit) {
            reenteredRecently = prevSeen != null && nowTs - prevSeen < VISIT_REJOIN_MS
        }
        visitOpening = newVisit
        pkgLastSeen[pkg] = nowTs
        lastForegroundPkg = pkg

        // 실행 결과: 다른 앱으로 옮겨간 순간이 체류의 끝이다.
        finalizeOutcomes(ctx, pkg)

        // 무시 직후의 자발적 첫 앱. 런처는 경유지일 뿐이라 건너뛰고 기다린다.
        pendingIgnore?.let { pi ->
            if (System.currentTimeMillis() - pi.at > IGNORE_FOLLOW_MS) {
                pendingIgnore = null
            } else if (pkg != launcherPkg(ctx) && pkg != ctx.packageName) {
                pendingIgnore = null
                DecisionContext.log(ctx, pi.signature, "ignored_then", pkg, choice = pkg)
            }
        }
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
        gate.withLock {
            signal(ctx, type, name) { it.triggerParam.isNullOrBlank() || it.triggerParam == name }
        }
    }

    suspend fun onNetwork(ctx: Context, wifi: Boolean, alias: String?) {
        val type = if (wifi) "wifi_connect" else "wifi_disconnect"
        gate.withLock {
            signal(ctx, type, alias) { it.triggerParam.isNullOrBlank() || it.triggerParam == alias }
        }
    }

    /** @param hhmm "HH:mm" — proposal-rules.md 의 time 트리거 형식과 같다 */
    suspend fun onTime(ctx: Context, hhmm: String) {
        gate.withLock { signal(ctx, "time", hhmm) { it.triggerParam == hhmm } }
    }

    suspend fun onExercise(ctx: Context, name: String) {
        gate.withLock {
            signal(ctx, "exercise_start", name) {
                it.triggerParam.isNullOrBlank() || it.triggerParam == name
            }
        }
    }

    /** 알림을 스와이프·모두 지우기로 지운 순간 — notif_cleanup 의 증명 순간 */
    suspend fun onNotifDismissed(ctx: Context, pkg: String) {
        gate.withLock {
            signal(ctx, "notif_dismissed", pkg) { it.triggerParam == pkg }
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
            // 중복으로 접을 때는 시각을 갱신하지 않는다 — 갱신하면 창이
            // 계속 미끄러져(접근성이 앱 내부 화면 전환마다 재보고) 진짜
            // 재진입까지 삼킨다(검토에서 확인). 창은 마지막 '통과' 기준이다.
            if (last != null && now - last < SIGNAL_DEDUP_MS) {
                Log.i(TAG, "중복 신호 접음: $key")
                return
            }
            recentSignals[key] = now
            // 오래된 키가 무한히 쌓이지 않게 이따금 청소한다.
            if (recentSignals.size > 64) {
                recentSignals.entries.removeAll { now - it.value > 60_000 }
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

        // 적격자를 먼저 모은다 — 우선순위는 수락 > 후보, 같은 급에서는
        // proposals() 정렬(updatedAt 최신순)이다. 띄우는 것은 첫째 하나지만,
        // 밀린 나머지도 기록해야 경쟁의 존재를 심층 분석이 볼 수 있다.
        val eligible = ArrayList<ProposalRow>()

        // 1) 수락된 루틴 — 실행은 언제나 사용자의 탭이다. 후보와 같은 팝업으로
        //    매번 여쭤본다. P3 가 좁힌 조건 밖에서는 침묵하되, 침묵을 기록한다.
        for (p in matched.filter { it.state == "accepted" }) {
            if (!inCondition(p, here)) {
                // 조건 밖 근접 발화 — "조건이 너무 좁은가"를 판단할 유일한
                // 재료다. 좁힌 조건이 기회를 죽여도 이 기록 없이는 아무도 모른다.
                if (now - (lastNearMiss[p.signature] ?: 0L) >= NEAR_MISS_GAP_MS) {
                    lastNearMiss[p.signature] = now
                    dao.logProposalEvent(
                        DecisionContext.capture(ctx, p.signature, "near_miss", lastForegroundPkg, now)
                    )
                }
                continue
            }
            // 같은 방문에서 두 번 묻지 않는다 — 앱에 머무는 동안 접근성이
            // 화면 전환을 재보고해도 알약이 반복되면 안 된다(실측 신고).
            if (triggerType == "app_launch" && (p.lastSurfacedAt ?: 0L) >= foregroundSince) continue
            // 비페어 앱 트리거는 방문을 연 보고에서만, 재진입 방문이 아닐 때만.
            if (triggerType == "app_launch" && p.actionType != "app_pair" &&
                (!visitOpening || reenteredRecently)
            ) continue
            // 페어는 수락 뒤에도 후보와 같은 조건이다(사용자 결정으로 통일):
            // 한쪽을 쓰다 다른 쪽으로 건너간 전환 순간에만 묻는다. 단독
            // 실행은 대부분 다른 용무라 소음이다. 대신 같은 흐름 안에서
            // 전환마다 반복하지 않는다 — "번갈아 쓸 때마다 알약"이 원 신고다.
            if (p.actionType == "app_pair" && triggerType == "app_launch") {
                val partner = Applier.params(p).firstOrNull { it != param }
                if (partner == null || now - (pkgLastSeen[partner] ?: 0L) >= PAIR_FLOW_MS) continue
                if (now - (p.lastSurfacedAt ?: 0L) < PAIR_FLOW_MS) continue
            }
            if (canSurface(p, now)) eligible += p
        }

        // 2) 후보 — 본 적 없는 맥락에서만 물어본다. 1차 분석이 조건을 붙였으면
        //    후보도 그 조건 안에서만 뜬다("밤에"라고 제안해놓고 낮에 뜨면 안 된다).
        for (p in matched.filter { it.state !in setOf("accepted", "dismissed") }) {
            if (!inCondition(p, here)) {
                // 후보의 조건 밖 침묵도 기록한다. 예전에는 수락된 루틴만
                // 남겨서, 1차 분석이 조건을 붙인 후보가 조건 밖에서 아무리
                // 자주 지나가도 흔적이 없었다 — 사용자도 에이전트도 "조건이
                // 너무 좁다"를 알 길이 없다(실측: 버즈 제안 7-14 조건).
                if (now - (lastNearMiss[p.signature] ?: 0L) >= NEAR_MISS_GAP_MS) {
                    lastNearMiss[p.signature] = now
                    dao.logProposalEvent(
                        DecisionContext.capture(ctx, p.signature, "near_miss", lastForegroundPkg, now)
                    )
                }
                continue
            }
            if (triggerType == "app_launch" && (p.lastSurfacedAt ?: 0L) >= foregroundSince) continue
            // 비페어 앱 트리거는 방문을 연 보고에서만, 재진입 방문이 아닐 때만.
            if (triggerType == "app_launch" && p.actionType != "app_pair" &&
                (!visitOpening || reenteredRecently)
            ) continue
            // 후보 페어는 전환 순간에만 묻는다 — 상대 앱을 방금 쓰고 온
            // 그 순간이 "이 왕복, 나란히 볼까요?"의 증명이다. 단독 실행은
            // 대부분 다른 용무라 소음이다. (수락 뒤에는 반대로 첫 실행에
            // 묻는다 — 왕복이 시작되기 전에 없애는 것이 세트의 목적이라.)
            if (p.actionType == "app_pair" && triggerType == "app_launch") {
                val partner = Applier.params(p).firstOrNull { it != param }
                if (partner == null || now - (pkgLastSeen[partner] ?: 0L) >= PAIR_FLOW_MS) continue
            }
            if (!canSurface(p, now)) continue
            if (rejectedHereBefore(dao, p, hereBucket)) continue
            eligible += p
        }

        val winner = eligible.firstOrNull() ?: return
        surface(ctx, winner, now, shortcut = false)   // 한 번에 하나만 띄운다

        // 가려짐 — 같은 신호에서 조건을 통과하고도 순서에 밀려 침묵한 제안.
        // 이 기록이 없으면 "이 트리거에 경쟁이 있고 사용자는 늘 한쪽만 본다"는
        // 사실을 심층 분석이 알 길이 없다. choice 에 이긴 제안의 시그니처를
        // 남긴다 — 무엇에 밀렸는지가 곧 우선순위 재조정의 재료다.
        for (p in eligible.drop(1)) {
            if (now - (lastEclipsed[p.signature] ?: 0L) < NEAR_MISS_GAP_MS) continue
            lastEclipsed[p.signature] = now
            dao.logProposalEvent(
                DecisionContext.capture(
                    ctx, p.signature, "eclipsed", lastForegroundPkg, now,
                    choice = winner.signature,
                )
            )
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

    /**
     * 지금 이 순간 트리거가 오면 이 제안이 뜰 수 있는가 — 못 뜨면 왜.
     * 루틴·제안 카드의 진단 표시용. 억제는 설계지만 안 보이면 고장으로
     * 읽힌다(실측: 수락 직후 페어 흐름 억제를 "트리거가 안 온다"로 오해).
     * dispatch 와 같은 검사를 읽기 전용으로 재현한다 — 락 없이 흘끗 보는
     * 근사지만 표시용으로 충분하다. null 이면 억제 없음.
     */
    fun whyQuiet(ctx: Context, p: ProposalRow): String? {
        if (p.state == "dismissed") return "보지 않기 상태 — 다시 뜨지 않습니다"
        val now = System.currentTimeMillis()
        val here = DecisionContext.capture(ctx, "", "probe", lastForegroundPkg, now)
        if (!inCondition(p, here)) {
            return buildString {
                append("조건 밖 — ")
                p.conditionWeekdays?.let { append("요일 $it ") }
                p.conditionHours?.let { append("시간 $it ") }
                append("창에서만 뜹니다")
            }
        }
        if (p.actionType == "app_pair") {
            // 수락 여부와 무관하게 조건이 같다(통일): 전환 순간에만 묻고,
            // 같은 흐름 안에서는 한 번만.
            val recentSeen = Applier.params(p)
                .mapNotNull { pkgLastSeen[it] }.maxOrNull() ?: 0L
            if (now - recentSeen >= PAIR_FLOW_MS) {
                return "전환 대기 — 한쪽 앱을 쓰다 3분 안에 다른 쪽으로 건너간 순간에만 물어봅니다"
            }
            val sinceSurf = now - (p.lastSurfacedAt ?: 0L)
            if (p.state == "accepted" && sinceSurf < PAIR_FLOW_MS) {
                val left = (PAIR_FLOW_MS - sinceSurf) / 60_000L + 1
                return "이 흐름에서는 이미 물어봤어요 — 약 ${left}분 뒤부터 다시"
            }
        }
        val sinceSurfaced = now - (p.lastSurfacedAt ?: 0L)
        if (sinceSurfaced < REPEAT_GAP_MS) {
            return "방금 떠서 재노출 대기 — ${(REPEAT_GAP_MS - sinceSurfaced) / 1000}초 뒤부터"
        }
        return null
    }

    /** P3 가 조건을 좁혀두었으면 그 조건 밖에서는 뜨지 않는다 */
    private fun inCondition(p: ProposalRow, here: ProposalEventRow): Boolean =
        matchesSpec(p.conditionHours, here.hour, endInclusive = false, wrap = true) &&
            matchesSpec(p.conditionWeekdays, here.weekday, endInclusive = true, wrap = false)

    /**
     * 조건 문자열은 LLM 이 쓴다 — 방어적으로 파싱한다. 깨진 부분은 무시하고,
     * 전부 깨졌으면 조건 없음으로 본다: 잘못된 조건 하나가 루틴을 영영
     * 죽이는 것(검토에서 확인)보다 조건을 무시하는 쪽이 낫다.
     *
     * hours 는 끝 미포함("9-16")에 자정 넘김("22-2")을 지원하고, weekdays 는
     * 끝 포함("1-5" = 월~금)이다.
     */
    private fun matchesSpec(spec: String?, v: Int, endInclusive: Boolean, wrap: Boolean): Boolean {
        spec ?: return true
        var sawValid = false
        for (part in spec.split(',')) {
            val r = part.trim().split('-')
            val a = r.getOrNull(0)?.trim()?.toIntOrNull()
            if (r.size == 2) {
                val b = r[1].trim().toIntOrNull()
                if (a == null || b == null) continue
                sawValid = true
                val inFwd = v >= a && (if (endInclusive) v <= b else v < b)
                val hit = if (a <= b) inFwd
                else wrap && (v >= a || (if (endInclusive) v <= b else v < b))
                if (hit) return true
            } else if (a != null) {
                sawValid = true
                if (v == a) return true
            }
        }
        return !sawValid
    }

    /**
     * 팝업·제안 탭의 '적용'이 app_mode 를 실제로 켰을 때 부른다 — 이탈 원복을
     * 걸어두는 유일한 통로다. 자동 실행이 없어졌으므로 적용 지점이 직접
     * 알려줘야 한다(이걸 빼먹으면 회전 잠금이 영영 안 풀린다).
     */
    suspend fun onModeApplied(ctx: Context, p: ProposalRow) = gate.withLock { rememberMode(ctx, p) }

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

    /**
     * 감시 서비스가 뜰 때 한 번 — 지난 프로세스가 못 되돌린 모드를 되돌린다.
     * 이 프로세스가 아직 추적 중인 모드(서비스만 껐다 켠 경우)는 고아가
     * 아니므로 건드리지 않는다 — 사용 중인 방해금지가 소리 없이 풀리면 안 된다.
     */
    suspend fun restoreModes(ctx: Context) = gate.withLock {
        val dao = Db.get(ctx).dao()
        val raw = dao.get(KEY_ACTIVE_MODES) ?: return@withLock
        val map = runCatching { Json.decodeFromString<Map<String, String>>(raw) }
            .getOrDefault(emptyMap())
        if (map.isEmpty()) return@withLock
        val alive = activeModes.values.map { it.signature }.toSet()
        for ((_, sig) in map) {
            if (sig in alive) continue
            dao.proposal(sig)?.let {
                Log.i(TAG, "지난 세션의 모드 복구: ${it.oneLine}")
                revertMode(ctx, it)
            }
        }
        persistModes(ctx)   // 현재 메모리 상태로 KV 재동기화
    }

    private const val KEY_ACTIVE_MODES = "active_modes"
}
