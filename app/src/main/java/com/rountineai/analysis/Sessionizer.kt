package com.rountineai.analysis

import android.app.usage.UsageEvents
import com.rountineai.data.UsageEventRow

/**
 * 원본 이벤트를 화면 세션으로 조립한다.
 *
 * 여기서 세 가지 계측 함정을 처리한다. 이 처리를 빼면 통계가 조용히 틀린다.
 *
 *  1. 같은 사건 중복 — 화면·잠금 이벤트는 사용자 프로필마다 따로 기록될 수 있다.
 *     (종류, 초 단위 시각)으로 접는다.
 *  2. 화면 꺼짐 이후 누적 — 앱 시작만 있고 종료가 없으면 다음 이벤트까지 시간이
 *     계속 쌓인다. 화면이 켜진 구간으로만 잘라 집계한다.
 *  3. 동시 실행 — 같은 초에 두 앱이 함께 시작되면 그건 전환이 아니라 분할화면이다.
 *     전환 통계에서 빼고 별도 지표로 남긴다.
 */
object Sessionizer {

    private const val RESUMED = UsageEvents.Event.ACTIVITY_RESUMED
    private const val PAUSED = UsageEvents.Event.ACTIVITY_PAUSED
    private const val SCREEN_ON = UsageEvents.Event.SCREEN_INTERACTIVE
    private const val SCREEN_OFF = UsageEvents.Event.SCREEN_NON_INTERACTIVE

    /** 3초 미만 점등은 주머니 오작동·인식 실패로 보고 제외한다. */
    const val MIN_WAKE_MS = 3_000L

    /** 한 번의 앱 체류에 두는 상한. 이벤트 유실 시 폭주를 막는 안전장치. */
    private const val MAX_FOREGROUND_MS = 60L * 60 * 1000

    data class AppSpan(val pkg: String, val start: Long, val end: Long, val viaLauncher: Boolean)

    data class Session(
        val start: Long,
        val end: Long,
        val spans: List<AppSpan>,
    ) {
        val durationMs: Long get() = end - start
        fun apps(launcher: String) = spans.map { it.pkg }.filter { it != launcher }
    }

    data class Result(
        val sessions: List<Session>,
        val coUse: Map<Pair<String, String>, MutableList<Long>>,
        val duplicatesDropped: Int,
        val shortWakesDropped: Int,
        val coUseEventsExcluded: Int,
    )

    fun build(
        raw: List<UsageEventRow>,
        launcherPkg: String,
        systemPkgs: Set<String>,
    ): Result {
        // --- 1) 기기 단위 사건 중복 제거 ---
        val deviceEventTypes = setOf(SCREEN_ON, SCREEN_OFF)
        val seen = HashSet<Long>()
        var dupes = 0
        val events = ArrayList<UsageEventRow>(raw.size)
        for (e in raw.sortedBy { it.ts }) {
            if (e.type in deviceEventTypes) {
                val key = (e.ts / 1000) * 100 + e.type
                if (!seen.add(key)) { dupes++; continue }
            }
            events.add(e)
        }

        // --- 3) 동시 실행 탐지: 같은 초에 시작된 서로 다른 앱들 ---
        val bySecond = HashMap<Long, MutableSet<String>>()
        for (e in events) {
            if (e.type == RESUMED && e.pkg != launcherPkg && e.pkg !in systemPkgs) {
                bySecond.getOrPut(e.ts / 1000) { HashSet() }.add(e.pkg)
            }
        }
        val coUseSeconds = HashSet<Long>()
        val coUse = HashMap<Pair<String, String>, MutableList<Long>>()
        for ((sec, pkgs) in bySecond) {
            if (pkgs.size < 2) continue
            coUseSeconds.add(sec)
            val sorted = pkgs.sorted()
            for (i in sorted.indices) for (j in i + 1 until sorted.size) {
                coUse.getOrPut(sorted[i] to sorted[j]) { ArrayList() }.add(sec * 1000)
            }
        }

        // --- 2) 화면 ON 구간 안에서만 앱 체류 시간 계산 ---
        val sessions = ArrayList<Session>()
        var openStart: Long? = null
        var spans = ArrayList<AppSpan>()
        var cur: Pair<String, Long>? = null      // pkg, start
        var pendingLauncher = false
        var coUseExcluded = 0

        fun closeSpan(at: Long) {
            val c = cur ?: return
            if (at > c.second) {
                val end = minOf(at, c.second + MAX_FOREGROUND_MS)
                spans.add(AppSpan(c.first, c.second, end, pendingLauncher))
                pendingLauncher = false
            }
            cur = null
        }

        for (e in events) {
            when (e.type) {
                SCREEN_ON -> if (openStart == null) {
                    openStart = e.ts; spans = ArrayList(); cur = null; pendingLauncher = false
                }

                SCREEN_OFF -> {
                    val s = openStart ?: continue
                    closeSpan(e.ts)
                    sessions.add(Session(s, e.ts, spans))
                    openStart = null
                }

                RESUMED -> {
                    if (openStart == null) continue
                    if (e.pkg in systemPkgs) continue
                    if (e.ts / 1000 in coUseSeconds) { coUseExcluded++; continue }
                    if (e.pkg == launcherPkg) {
                        // 홈 화면은 목적지가 아니라 통로다. 다음 앱에 '경유' 표식만 남긴다.
                        closeSpan(e.ts); pendingLauncher = true; continue
                    }
                    if (cur?.first == e.pkg) continue
                    closeSpan(e.ts)
                    cur = e.pkg to e.ts
                }

                PAUSED -> if (cur?.first == e.pkg) closeSpan(e.ts)
            }
        }
        // 마지막 세션이 열린 채 끝났으면 마지막 이벤트에서 닫는다.
        openStart?.let { s ->
            val last = events.lastOrNull()?.ts ?: s
            closeSpan(last)
            sessions.add(Session(s, last, spans))
        }

        val short = sessions.count { it.durationMs < MIN_WAKE_MS }
        return Result(
            sessions = sessions.filter { it.durationMs >= MIN_WAKE_MS },
            coUse = coUse,
            duplicatesDropped = dupes,
            shortWakesDropped = short,
            coUseEventsExcluded = coUseExcluded,
        )
    }
}
