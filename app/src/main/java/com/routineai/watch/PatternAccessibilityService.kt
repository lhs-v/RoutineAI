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

        scope.launch { PatternWatcher.onAppForeground(applicationContext, pkg) }
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
        val opts = await { root ->
            find(root) { n ->
                val d = n.contentDescription?.toString().orEmpty()
                d.startsWith(ADV_OPTIONS) && d.contains(anchorLabel)
            } ?: find(root) { n ->
                n.contentDescription?.toString().orEmpty().startsWith(ADV_OPTIONS)
            }
        } ?: return false.also { Log.w(TAG, "고급 옵션 버튼 없음") }
        if (!click(opts)) return false

        // 2) "분할 화면으로 열기"
        val split = await { root -> find(root) { it.text?.toString() == SPLIT_MENU } }
            ?: return false.also { Log.w(TAG, "분할 메뉴 없음") }
        if (!click(split)) return false

        // 3) 앱 선택에서 상대 앱. 목록에 없으면 한 번 스크롤해 본다 —
        //    오래 안 쓴 앱은 첫 화면에 없다.
        var target = await { root -> findVisible(root, otherLabel) }
        if (target == null) {
            scrollOnce()
            target = await { root -> findVisible(root, otherLabel) }
        }
        if (target == null) {
            Log.w(TAG, "앱 선택에서 $otherLabel 못 찾음")
            return false
        }

        // 여기는 ACTION_CLICK 이 통하지 않는다 — 삼성 앱 선택 그리드는 true 를
        // 돌려주고도 아무 일도 하지 않았다(실측: split=true 인데 선택 안 됨).
        // 사람 손가락과 같은 진짜 터치를 보낸다.
        //
        // 어디를 누르냐가 관건이다: 앱 이름 글자만 누르면 터치 영역 밖이라
        // 아무 일도 안 일어난다(실측). 실제 대상은 아이콘과 이름을 함께 감싼
        // 항목이므로, 누를 수 있는 조상의 한가운데부터 시도하고 안 되면
        // 라벨 자신으로 물러난다.
        for (rect in tapTargets(target)) {
            Log.i(TAG, "탭: ${rect.centerX()},${rect.centerY()} (${rect.width()}x${rect.height()})")
            if (!tapAt(rect)) continue
            val gone = await(2_500L) { root ->
                if (find(root) { it.text?.toString() == PICKER_TITLE } == null) true else null
            } ?: false
            if (gone) return true
        }
        Log.w(TAG, "탭했지만 앱 선택 화면이 그대로다")
        return false
    }

    /**
     * 누를 후보 좌표들 — 넓은 항목부터 좁은 라벨 순으로.
     * 앱 목록은 아이콘 + 이름이 한 항목이라 이름만 누르면 빗나간다.
     */
    private fun tapTargets(node: AccessibilityNodeInfo): List<android.graphics.Rect> {
        val out = ArrayList<android.graphics.Rect>()
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth++ < 4) {
            if (n.isClickable) {
                out += android.graphics.Rect().also { n!!.getBoundsInScreen(it) }
                break
            }
            n = n.parent
        }
        // 조상을 못 찾았거나 그것으로도 안 될 때를 대비해 라벨 자신도 후보.
        out += android.graphics.Rect().also { node.getBoundsInScreen(it) }
        return out.filter { it.width() > 0 && it.height() > 0 }.distinct()
    }

    /** 화면에 실제로 보이는 것 중에서 찾는다 — 접힌 목록의 노드를 눌러도 소용없다. */
    private fun findVisible(root: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? =
        find(root) { n ->
            n.text?.toString() == label && n.isVisibleToUser &&
                android.graphics.Rect().also { n.getBoundsInScreen(it) }.width() > 0
        }

    /** 그 자리를 실제로 터치한다. */
    private fun tapAt(r: android.graphics.Rect): Boolean {
        val path = android.graphics.Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(
                android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 80)
            )
            .build()
        val ok = dispatchGesture(gesture, null, null)
        Thread.sleep(400)
        return ok
    }

    /** 자동화가 실패했을 때 앱 선택 화면에 사용자를 버려두지 않는다. */
    private fun leavePickerIfOpen() {
        val open = rootInActiveWindow?.let {
            find(it) { n -> n.text?.toString() == PICKER_TITLE } != null
        } ?: false
        if (open) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            Thread.sleep(400)
        }
    }

    /** 조건이 만족될 때까지 짧게 다시 본다. 화면이 바뀌는 데 시간이 걸린다. */
    private fun <T> await(timeoutMs: Long = 2_500L, block: (AccessibilityNodeInfo) -> T?): T? {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            rootInActiveWindow?.let { root ->
                block(root)?.let { return it }
            }
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

    private fun scrollOnce() {
        rootInActiveWindow?.let { root ->
            find(root) { it.isScrollable }?.performAction(
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            )
        }
        Thread.sleep(400)
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

        /** 사용자가 "앱을 열었다"고 느끼지 않는 표면들 */
        private val IGNORED = setOf(
            "com.android.systemui",
            "android",
            "com.samsung.android.app.aodservice",
            "com.samsung.android.messaging.ui",
        )
    }
}
