package com.routineai.watch

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 앱 전환을 즉시 감지하고, 분할화면을 만든다.
 *
 * UsageStats 폴링으로도 앞 앱을 알 수 있지만 몇 초 늦는다 — "그 순간의 제안"이
 * 핵심인 데모에서 그 지연은 치명적이다.
 *
 * 평상시 읽는 것은 이벤트의 패키지명뿐이다. 화면 내용 조회는 [splitWith] 가
 * 도는 몇 초 동안 시스템 런처(최근앱·앱 선택) 화면에서만 쓴다 — 삼성에서
 * 서드파티가 분할화면을 만드는 유일한 경로이기 때문이다.
 */
class PatternAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastPkg: String? = null
    private var lastAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        // 같은 앱 안의 화면 전환도 이 이벤트를 낸다. 앱이 바뀐 순간만 본다.
        val now = System.currentTimeMillis()
        if (pkg == lastPkg && now - lastAt < 1_500) return
        if (pkg == packageName) return          // 우리 앱 자신
        if (pkg in IGNORED) return
        lastPkg = pkg
        lastAt = now

        // 미포착 예외가 프로세스를 죽이지 않게 감싼다 — 폴링 경로와 달리
        // 이 코루틴에는 지붕이 없다(검토에서 확인).
        scope.launch {
            runCatching { PatternWatcher.onAppForeground(applicationContext, pkg) }
                .onFailure { android.util.Log.w("PatternA11y", "디스패치 실패", it) }
        }
    }

    override fun onInterrupt() = Unit

    // ------------------------------------------------------------------
    // 분할화면 자동화. 사람이 손으로 하는 조작을 그대로 따라간다.

    /**
     * 한 단계씩 "화면에 그것이 나타날 때까지" 기다렸다 누른다. 고정 대기는
     * 기기가 느릴 때 깨지고 빠를 때 시간을 버린다.
     */
    private fun runSplit(anchorLabel: String, otherLabel: String): Boolean {
        if (!performGlobalAction(GLOBAL_ACTION_RECENTS)) return false

        // 1) 앵커 카드의 "고급 옵션" 버튼. content-desc 가 "고급 옵션, <앱>, 버튼"
        //    형태라 앱 이름으로 어느 카드인지 특정한다.
        val opts = await { roots ->
            roots.firstNotNullOfOrNull { r ->
                find(r) { n ->
                    val d = n.contentDescription?.toString().orEmpty()
                    d.startsWith(ADV_OPTIONS) && d.contains(anchorLabel)
                }
            } ?: roots.firstNotNullOfOrNull { r ->
                find(r) { n ->
                    n.contentDescription?.toString().orEmpty().startsWith(ADV_OPTIONS)
                }
            }
        } ?: return false.also { Log.w(TAG, "고급 옵션 버튼 없음") }
        if (!click(opts)) return false

        // 2) "분할 화면으로 열기"
        val split = await { roots ->
            roots.firstNotNullOfOrNull { r -> find(r) { it.text?.toString() == SPLIT_MENU } }
        } ?: return false.also { Log.w(TAG, "분할 메뉴 없음") }
        if (!click(split)) return false

        // 3) 여기까지다 — 앱 선택 화면을 열어두고 사용자에게 넘긴다.
        //
        // 두 번째 앱까지 자동으로 고르는 것은 포기했다(사용자 결정). 삼성
        // 앱 선택 그리드는 ACTION_CLICK 에 true 를 돌려주고도 아무 일도 하지
        // 않고, 좌표 탭도 안정적으로 먹지 않았다. 실패하면 순차 실행으로
        // 낙하하는데 그게 "분할이 아니라 화면 전환"으로 보여 더 나빴다.
        // 마지막 한 번의 탭은 사람이 하는 편이 정직하고 결과도 확실하다.
        val pickerUp = await { roots -> if (pickerRoot(roots) != null) true else null } ?: false
        Log.i(TAG, "분할 앱 선택 화면 열림=$pickerUp (상대 앱 $otherLabel 은 사용자가 선택)")
        return pickerUp
    }

    /** 앱 선택 창의 루트. 홈 화면·태스크바와 구별하는 유일한 표식이 제목이다. */
    private fun pickerRoot(roots: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? =
        roots.firstOrNull { find(it) { n -> n.text?.toString() == PICKER_TITLE } != null }

    /** 접근성이 볼 수 있는 모든 창의 루트. 활성 창 하나만으로는 부족하다. */
    private fun roots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        runCatching { windows }.getOrNull()?.forEach { w ->
            runCatching { w.root }.getOrNull()?.let { out += it }
        }
        rootInActiveWindow?.let { out += it }
        return out
    }

    // 앱 선택 그리드를 자동으로 탭하던 코드(tapTargets/findVisible/tapAt/
    // scrollOnce)는 제거했다 — 삼성 그리드가 ACTION_CLICK 에 true 를 주고도
    // 아무 일도 안 하고 좌표 탭도 불안정해, 두 번째 앱은 사용자가 고르는
    // 것으로 정했다(사용자 결정). 되살리려면 이 커밋의 부모를 보라.

    /** 분할 진입 자체가 실패했을 때 앱 선택 화면에 사용자를 버려두지 않는다. */
    private fun leavePickerIfOpen() {
        val open = pickerRoot(roots()) != null
        if (open) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            Thread.sleep(400)
        }
    }

    /** 조건이 만족될 때까지 짧게 다시 본다. 화면이 바뀌는 데 시간이 걸린다. */
    private fun <T> await(
        timeoutMs: Long = 2_500L,
        block: (List<AccessibilityNodeInfo>) -> T?,
    ): T? {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            val rs = roots()
            if (rs.isNotEmpty()) block(rs)?.let { return it }
            Thread.sleep(120)
        }
        return null
    }

    /** 눌러야 할 것이 자신이 아니라 부모일 때가 있어 위로 올라가며 시도한다. */
    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth++ < 5) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }


    /** 트리를 훑어 조건에 맞는 첫 노드. */
    private fun find(
        node: AccessibilityNodeInfo,
        depth: Int = 0,
        pred: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (depth > 24) return null
        if (runCatching { pred(node) }.getOrDefault(false)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            find(child, depth + 1, pred)?.let { return it }
        }
        return null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Applier.logDeviceState(applicationContext)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        /**
         * 분할화면 토글에 필요하다. 인접 실행 플래그는 이미 멀티윈도우일 때만
         * 동작하므로, 단일 화면에서 진짜로 화면을 가르는 공개 경로는
         * 접근성의 전역 동작뿐이다.
         */
        @Volatile
        private var instance: PatternAccessibilityService? = null

        /**
         * 분할화면 토글.
         *
         * 실측: 삼성 One UI 는 이 전역 동작을 거부한다(a11y 는 붙어 있는데
         * false 반환). 화면이 좁아서인지 기기 정책인지는 반환값만으로 알 수
         * 없어, 호출부가 실패를 그대로 받아 순차 실행으로 떨어지게 한다.
         */
        fun toggleSplitScreen(): Boolean = runCatching {
            instance?.performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN) ?: false
        }.getOrDefault(false)

        /**
         * 최근앱 메뉴를 눌러 [anchorLabel] 과 [otherLabel] 을 분할화면으로 만든다.
         *
         * 실측으로 확인한 유일한 경로다. 다른 길은 전부 막혀 있다:
         * 전역 토글은 One UI 가 거부하고, windowingMode 6 은 태스크 모드만
         * 바뀌고 화면은 전체를 덮으며, freeform 은 화면이 깨지고, 레거시
         * 분할 모드(3/4)는 제거됐다. 인접 실행 플래그는 분할 대기 상태에서도
         * 전체화면으로 열린다 — 앱 선택은 **진짜 탭**이어야 한다.
         *
         * 흐름: 최근앱 → (앵커 카드의) 고급 옵션 → 분할 화면으로 열기 →
         * 앱 선택에서 상대 앱 탭.
         *
         * @return 성공 여부. 실패하면 호출부가 순차 실행으로 떨어진다.
         */
        fun splitWith(anchorLabel: String, otherLabel: String): Boolean {
            val svc = instance ?: return false
            val ok = runCatching { svc.runSplit(anchorLabel, otherLabel) }
                .onFailure { Log.w(TAG, "분할 자동화 실패", it) }
                .getOrDefault(false)
            // 실패했으면 앱 선택 화면에 사용자를 버려두지 않는다 — 그 상태에서
            // 순차 실행을 하면 새 앱이 분할을 덮어써 더 이상해진다(실측).
            if (!ok) runCatching { svc.leavePickerIfOpen() }
            return ok
        }

        fun isConnected(): Boolean = instance != null

        private const val TAG = "PatternA11y"

        /** 최근앱 카드의 메뉴 버튼 — content-desc 가 "고급 옵션, <앱>, 버튼" */
        private const val ADV_OPTIONS = "고급 옵션"
        private const val SPLIT_MENU = "분할 화면으로 열기"

        /** 두 번째 앱을 고르는 화면의 제목 — 사라졌으면 선택이 먹혔다는 뜻 */
        private const val PICKER_TITLE = "앱 선택"

        /**
         * 사용자가 "앱을 열었다"고 느끼지 않는 표면들.
         * 키보드가 여기 있는 이유: TYPE_WINDOW_STATE_CHANGED 는 IME 창에도
         * 발생해서, 검색창을 탭하는 순간이 "앱 전환"으로 처리되면 체류 측정과
         * 모드 원복이 오염된다(검토에서 확인).
         */
        private val IGNORED = setOf(
            "com.android.systemui",
            "android",
            "com.samsung.android.app.aodservice",
            "com.samsung.android.messaging.ui",
            "com.samsung.android.honeyboard",
            "com.google.android.inputmethod.latin",
        )
    }
}
