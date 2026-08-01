package com.routineai.watch

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 앱 전환을 즉시 감지한다.
 *
 * UsageStats 폴링으로도 앞 앱을 알 수 있지만 몇 초 늦는다 — "그 순간의 제안"이
 * 핵심인 데모에서 그 지연은 치명적이다.
 *
 * 읽는 것은 이벤트의 패키지명뿐이다. 화면 내용 조회 플래그를 켜지 않았으므로
 * 텍스트·필드에는 접근할 수 없다(accessibility_config.xml).
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

        fun toggleSplitScreen(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN) ?: false

        fun isConnected(): Boolean = instance != null

        /** 사용자가 "앱을 열었다"고 느끼지 않는 표면들 */
        private val IGNORED = setOf(
            "com.android.systemui",
            "android",
            "com.samsung.android.app.aodservice",
            "com.samsung.android.messaging.ui",
        )
    }
}
