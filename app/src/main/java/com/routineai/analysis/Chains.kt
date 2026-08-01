package com.routineai.analysis

import android.app.usage.UsageEvents
import com.routineai.data.BtEventRow
import com.routineai.data.HealthSessionRow
import com.routineai.data.NetworkChangeRow
import com.routineai.data.NotifEventRow
import com.routineai.data.UsageEventRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * 이벤트 연쇄(질문: 어떤 사건 뒤에 어떤 앱을 여는가)와
 * 상태 맥락(질문: 그 앱을 열 때 기기는 어떤 상태인가)을 집계한다.
 *
 * 순수 계산만 한다 — Context 도 DB 도 모른다. Analyzer 가 재료를 넘긴다.
 */
object Chains {

    /** 트리거 뒤 이 시간 안에 열린 앱만 연쇄로 본다. 길수록 우연이 섞인다. */
    private const val CHAIN_WINDOW_MS = 5L * 60 * 1000

    /** 알림이 실행보다 이만큼 앞서 있으면 '알림 주도 실행'으로 본다 */
    private const val NOTIF_LEAD_MS = 30_000L

    data class Input(
        val raw: List<UsageEventRow>,
        val notifs: List<NotifEventRow>,
        val netChanges: List<NetworkChangeRow>,
        val btEvents: List<BtEventRow>,
        val healthSessions: List<HealthSessionRow>,
        val ssidAlias: Map<String, String>,
        val systemPkgs: Set<String>,
        val launcherPkg: String,
        val zone: ZoneId,
    )

    fun build(input: Input, label: (String) -> String): Pair<List<ChainStat>, List<AppContextStat>> {
        val launches = input.raw
            .filter {
                it.type == UsageEvents.Event.ACTIVITY_RESUMED &&
                    it.pkg != input.launcherPkg && it.pkg !in input.systemPkgs
            }
            .sortedBy { it.ts }
        if (launches.isEmpty()) return emptyList<ChainStat>() to emptyList()

        val (chainStats, chainPkgs) = chains(input, launches, label)
        // 연쇄에 등장한 앱은 실행 순위와 무관하게 맥락을 싣는다. 연쇄의 판정
        // 재료(조건부 비율)가 빠지면, 실행이 적지만 습관이 강한 앱이
        // 다빈도 앱에 밀려 보이지 않게 된다.
        return chainStats to contexts(input, launches, label, chainPkgs)
    }

    // ------------------------------------------------------------------

    /** 트리거 이벤트 목록: (ts, 라벨) */
    private fun triggers(input: Input): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        for (b in input.btEvents) {
            out += b.ts to (if (b.action == "connect") "BT연결:${b.name}" else "BT해제:${b.name}")
        }
        for (n in input.netChanges) {
            out += when (n.kind) {
                "wifi" -> n.ts to "Wi-Fi 연결:${input.ssidAlias[n.ssid ?: ""] ?: "Wi-Fi"}"
                "cellular" -> n.ts to "모바일 전환"
                else -> continue
            }
        }
        for (h in input.healthSessions) {
            if (h.kind.startsWith("exercise:")) {
                val name = h.kind.removePrefix("exercise:")
                out += h.tsStart to "운동 시작:$name"
                out += h.tsEnd to "운동 종료:$name"
            }
        }
        return out.sortedBy { it.first }
    }

    /**
     * 트리거마다 그 뒤 처음 열린 앱 하나만 짝짓는다.
     *
     * 창 안의 모든 앱과 짝지으면 트리거 하나가 여러 연쇄에 세어져
     * 자주 쓰는 앱이 전부 위로 올라온다. "직후 행동"만이 트리거의 결과다.
     */
    private fun chains(
        input: Input,
        launches: List<UsageEventRow>,
        label: (String) -> String,
    ): Pair<List<ChainStat>, Set<String>> {
        val gaps = HashMap<Pair<String, String>, MutableList<Long>>()
        val days = HashMap<Pair<String, String>, MutableSet<LocalDate>>()
        var li = 0
        for ((ts, trig) in triggers(input)) {
            while (li < launches.size && launches[li].ts <= ts) li++
            val next = launches.getOrNull(li) ?: continue
            val gap = next.ts - ts
            if (gap > CHAIN_WINDOW_MS) continue
            val key = trig to next.pkg
            gaps.getOrPut(key) { ArrayList() }.add(gap)
            days.getOrPut(key) { HashSet() }
                .add(Instant.ofEpochMilli(ts).atZone(input.zone).toLocalDate())
        }
        val kept = gaps.entries
            .filter { it.value.size >= 3 }
            .sortedByDescending { it.value.size }
            .take(15)
        val stats = kept.map { (key, g) ->
            val s = g.sorted()
            ChainStat(
                trigger = key.first,
                app = label(key.second),
                count = g.size,
                minGapSeconds = (s.first() / 1000.0).roundToInt(),
                medianGapSeconds = (s[s.size / 2] / 1000.0).roundToInt(),
                maxGapSeconds = (s.last() / 1000.0).roundToInt(),
                distinctDays = days[key]?.size ?: 0,
            )
        }
        return stats to kept.map { it.key.second }.toSet()
    }

    // ------------------------------------------------------------------

    private fun contexts(
        input: Input,
        launches: List<UsageEventRow>,
        label: (String) -> String,
        mustInclude: Set<String>,
    ): List<AppContextStat> {
        // 실행 순간의 상태를 되짚기 위한 정렬된 타임라인들
        val netTimeline = input.netChanges.sortedBy { it.ts }
        val btTimeline = input.btEvents.sortedBy { it.ts }
        val notifByPkg = input.notifs.groupBy({ it.pkg }, { it.ts })
            .mapValues { it.value.sorted() }

        fun cellularAt(ts: Long): Boolean? {
            val row = netTimeline.lastOrNull { it.ts <= ts } ?: return null
            return row.kind == "cellular"
        }

        /** ts 시점에 연결돼 있던 기기 이름. 기기별 마지막 connect/disconnect 로 판정. */
        fun btAt(ts: Long): String? {
            val lastByDevice = HashMap<String, String>()
            for (b in btTimeline) {
                if (b.ts > ts) break
                lastByDevice[b.name] = b.action
            }
            return lastByDevice.entries.firstOrNull { it.value == "connect" }?.key
        }

        fun notifLed(pkg: String, ts: Long): Boolean {
            val list = notifByPkg[pkg] ?: return false
            val i = list.binarySearch(ts).let { if (it < 0) -it - 2 else it }
            val prev = list.getOrNull(i) ?: return false
            return ts - prev in 0..NOTIF_LEAD_MS
        }

        val byPkg = launches.groupBy { it.pkg }
        val top = byPkg.entries.sortedByDescending { it.value.size }.take(12).map { it.key }
        val selected = (top + mustInclude.filter { it in byPkg }).distinct()
        return selected.map { pkg -> pkg to byPkg.getValue(pkg) }
            .sortedByDescending { it.second.size }
            .map { (pkg, list) ->
                var cell = 0; var cellKnown = 0
                var bt = 0
                val btDevices = HashMap<String, Int>()
                var led = 0
                val hourCount = IntArray(24)
                for (e in list) {
                    cellularAt(e.ts)?.let { cellKnown++; if (it) cell++ }
                    btAt(e.ts)?.let { bt++; btDevices.merge(it, 1, Int::plus) }
                    if (notifLed(pkg, e.ts)) led++
                    hourCount[Instant.ofEpochMilli(e.ts).atZone(input.zone).hour]++
                }
                val n = list.size
                AppContextStat(
                    label = label(pkg),
                    launches = n,
                    cellularPct = if (cellKnown > 0) (cell * 100.0 / cellKnown).r2() else -1.0,
                    btConnectedPct = if (input.btEvents.isNotEmpty())
                        (bt * 100.0 / n).r2() else -1.0,
                    btTopDevice = btDevices.maxByOrNull { it.value }?.key,
                    notifLedPct = (led * 100.0 / n).r2(),
                    topHours = hourCount.withIndex().sortedByDescending { it.value }
                        .take(3).filter { it.value > 0 }.map { it.index },
                )
            }
    }

    private fun Double.r2() = Math.round(this * 100.0) / 100.0
}
