package com.routineai.analysis

import android.app.usage.UsageEvents
import com.routineai.data.UsageEventRow

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
        /** "screen_events" 또는 "activity_gap" */
        val source: String,
        val screenEventCount: Int,
    )

    /** 앱 전환이 이만큼 끊기면 다른 세션으로 본다 (화면 이벤트가 없을 때만 사용) */
    private const val GAP_SPLIT_MS = 5L * 60 * 1000

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

        val screenCount = events.count { it.type == SCREEN_ON || it.type == SCREEN_OFF }

        // 화면 이벤트를 안 주는 기기가 있다. 그러면 세션이 하나도 안 만들어지고
        // 모든 통계가 0 이 된다. 이 경우 앱 전환 간격으로 세션을 대신 만든다.
        if (sessions.isEmpty() && screenCount == 0) {
            val fallback = buildFromActivityGaps(events, launcherPkg, systemPkgs, coUseSeconds)
            return Result(
                sessions = fallback.filter { it.durationMs >= MIN_WAKE_MS },
                coUse = coUse,
                duplicatesDropped = dupes,
                shortWakesDropped = fallback.count { it.durationMs < MIN_WAKE_MS },
                coUseEventsExcluded = coUseExcluded,
                source = "activity_gap",
                screenEventCount = 0,
            )
        }

        val short = sessions.count { it.durationMs < MIN_WAKE_MS }
        return Result(
            sessions = sessions.filter { it.durationMs >= MIN_WAKE_MS },
            coUse = coUse,
            duplicatesDropped = dupes,
            shortWakesDropped = short,
            coUseEventsExcluded = coUseExcluded,
            source = "screen_events",
            screenEventCount = screenCount,
        )
    }

    /**
     * 화면 이벤트가 없을 때의 대체 경로.
     *
     * 앱이 포그라운드에 올라온 시각들을 5분 간격으로 끊어 세션으로 본다.
     * 화면 켜짐 기준이 아니므로 '화면을 켰지만 앱을 안 연' 경우는 잡히지 않는다.
     * 이 차이는 [Result.source] 로 알리고 리포트에도 표시한다.
     */
    private fun buildFromActivityGaps(
        events: List<UsageEventRow>,
        launcherPkg: String,
        systemPkgs: Set<String>,
        coUseSeconds: Set<Long>,
    ): List<Session> {
        val opens = events.filter {
            it.type == RESUMED && it.pkg !in systemPkgs && it.ts / 1000 !in coUseSeconds
        }
        if (opens.isEmpty()) return emptyList()

        val out = ArrayList<Session>()
        var spans = ArrayList<AppSpan>()
        var start = opens.first().ts
        var prev: UsageEventRow? = null
        var pendingLauncher = false

        fun flush(end: Long) {
            if (spans.isNotEmpty() || end > start) out.add(Session(start, end, spans))
            spans = ArrayList()
        }

        // 세션의 마지막 앱도 스팬으로 닫는다 — 안 닫으면 그 앱의 체류가
        // 통째로 0 이 되고, 한 앱만 열고 내려놓는 세션은 빈 스팬이 된다
        // (검토에서 확인: "상한까지 인정한다"는 주석이 실제론 0 으로 인정했다).
        fun closeLast(p: UsageEventRow, end: Long) {
            if (p.pkg != launcherPkg && end > p.ts) {
                spans.add(AppSpan(p.pkg, p.ts, end, pendingLauncher))
            }
        }

        for (e in opens) {
            val p = prev
            if (p != null && e.ts - p.ts > GAP_SPLIT_MS) {
                // 마지막 앱은 다음 이벤트까지가 아니라 상한까지만 인정한다.
                val end = minOf(p.ts + MAX_FOREGROUND_MS, p.ts + GAP_SPLIT_MS)
                closeLast(p, end)
                flush(end)
                start = e.ts
                pendingLauncher = false
            } else if (p != null && p.pkg != launcherPkg) {
                spans.add(AppSpan(p.pkg, p.ts, e.ts, pendingLauncher))
                pendingLauncher = false
            } else if (p != null) {
                pendingLauncher = true
            }
            prev = e
        }
        prev?.let {
            val end = it.ts + 60_000
            closeLast(it, end)
            flush(end)
        }
        return out
    }
}
