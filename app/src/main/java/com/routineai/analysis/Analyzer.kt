package com.routineai.analysis

import android.app.usage.UsageEvents
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import com.routineai.collect.NetworkCollector
import com.routineai.collect.UsageCollector
import com.routineai.collect.Permissions
import com.routineai.data.Db
import com.routineai.data.NotifEventRow
import com.routineai.data.UsageEventRow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 저장된 원본에서 리포트를 만든다.
 *
 * 규칙:
 *  - 시각은 전부 기기 표준시로 다룬다. epoch 를 UTC 로 풀면 조용히 어긋난다.
 *  - 평균은 **온전히 기록된 날**로만 낸다. 첫날·마지막날은 대개 부분일이다.
 *  - 평균과 함께 중앙값·편차·관측일수를 낸다.
 *  - 앱 시간 합계와 화면 시간을 서로 검산해 [Quality] 에 남긴다.
 */
class Analyzer(private val ctx: Context, private val demo: Boolean = false) {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val dao = (if (demo) Db.demo(ctx) else Db.get(ctx)).dao()
    private val pm = ctx.packageManager

    /**
     * @param onStep 진행 상황 콜백. UI 에서 어느 단계인지 보여주기 위한 것.
     */
    suspend fun build(days: Int = 30, onStep: (String) -> Unit = {}): Report {
        onStep("저장된 이벤트 읽는 중")
        // 데모 로그는 만든 시점이 고정되어 있다. 지금 시각을 기준으로 잡으면
        // 며칠만 지나도 "최근 30일" 창 밖으로 빠져나가 전부 0 이 된다.
        // 그래서 데모에서는 데이터 자체의 마지막 시각을 기준으로 삼는다.
        // +1: 조회가 ts < to (배타)라 기준으로 삼은 마지막 이벤트 자신이
        // 빠지는 off-by-one 을 막는다.
        val now = if (demo) (dao.lastEventTs() ?: System.currentTimeMillis()) + 1
        else System.currentTimeMillis()
        // 관측 창의 시작을 사용 이벤트의 시작에 맞춘다. 통신량·헬스는 몇 달을
        // 소급하지만 사용 이벤트는 며칠뿐이라, 그대로 두면 지표마다 관측 창이
        // 달라진다. 오래 쓴 기기에서는 요청한 창(days)이 그대로 유지된다.
        val requested = now - days.toLong() * 24 * 60 * 60 * 1000
        val from = maxOf(requested, dao.firstEventTs() ?: requested)

        val raw = dao.events(from, now)
        val notifs = dao.notifs(from, now)
        val netChanges = dao.netChanges(from, now)
        val btEvents = dao.btEvents(from, now)
        val healthSessions = dao.healthSessions(from, now)

        val launcher = resolveLauncher()
        val system = systemPackages()

        val s = Sessionizer.build(raw, launcher, system)
        val sessions = s.sessions

        // ---- 날짜별 커버리지: 부분일 판정 ----
        //
        // 판정 기준은 "사용자가 그 시간에 폰을 봤는가"가 아니라
        // "관측 창이 그 날 00:00~24:00 을 덮는가"다.
        // 전자로 판정하면 아침 늦게 일어난 날이 전부 부분일이 되어
        // 평균 계산 대상이 사라지고 모든 지표가 0 이 된다.
        val byDate = raw.groupBy { it.ts.toLocalDate() }
        val allDates = byDate.keys.sorted()
        val windowStart = raw.minOfOrNull { it.ts } ?: now
        val windowEnd = raw.maxOfOrNull { it.ts } ?: now
        var fullDates = allDates.filter { d ->
            val dayStart = d.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            windowStart <= dayStart && windowEnd >= dayEnd
        }
        // 수집한 지 하루가 안 됐으면 온전한 날이 하나도 없다.
        // 그래도 빈 리포트를 내놓는 것보다는 가진 날로 계산하고 그 사실을 알리는 편이 낫다.
        val fullDayFallback = fullDates.isEmpty() && allDates.isNotEmpty()
        if (fullDayFallback) fullDates = allDates
        val partial = allDates - fullDates.toSet()
        onStep("세션 조립 중")

        // ---- 일별 지표 ----
        val sessByDate = sessions.groupBy { it.start.toLocalDate() }
        // 같은 알림의 재게시(미디어 진행률 갱신 등)를 도착 1건으로 접는다.
        // 리스너가 key 로 걸러내기 전에 쌓인 행에는 이게 섞여 있고,
        // key 는 저장하지 않으므로 (앱, 채널) 5초 창으로 근사한다.
        var repostsCollapsed = 0
        val liveNotifs = notifs.filter { !it.ongoing }
            .groupBy { it.pkg to it.channel }
            .flatMap { (_, group) ->
                // 초기값이 Long.MIN_VALUE 면 ts - lastKept 가 넘쳐 음수가 되어
                // 전부 버려진다. ts 는 epoch ms 라 0 이면 첫 건이 항상 살아남는다.
                var lastKept = 0L
                group.sortedBy { it.ts }.filter { n ->
                    val keep = n.ts - lastKept >= NOTIF_REPOST_WINDOW_MS
                    if (keep) lastKept = n.ts else repostsCollapsed++
                    keep
                }
            }
        val notifByDate = liveNotifs.groupBy { it.ts.toLocalDate() }
        // 리스너는 권한을 켠 뒤부터만 기록된다. 그 이전 날을 평균에 넣으면
        // 알림 수가 실제보다 낮게 나오므로 날짜마다 표시해 둔다.
        val notifSince = notifs.minOfOrNull { it.ts }
        val notifSinceDate = notifSince?.toLocalDate()
        val dayStats = allDates.map { d ->
            val ss = sessByDate[d].orEmpty()
            DayStat(
                date = d.toString(),
                weekday = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN),
                isWeekend = d.dayOfWeek.value >= 6,
                full = d in fullDates,
                screenMinutes = ss.sumOf { it.durationMs }.toDouble() / 60_000,
                wakes = ss.size,
                microWakes = ss.count { it.durationMs < 30_000 },
                noAppWakes = ss.count { it.durationMs < 30_000 && it.apps(launcher).isEmpty() },
                notifs = notifByDate[d].orEmpty().size,
                notifCovered = notifSinceDate != null && !d.isBefore(notifSinceDate),
            )
        }
        val fullStats = dayStats.filter { it.full }
        val nFull = fullStats.size.coerceAtLeast(1)

        // ---- 시간대 리듬 ----
        val screenPerHour = DoubleArray(24)
        for (s0 in sessions) {
            if (s0.start.toLocalDate() !in fullDates) continue
            var t = s0.start
            while (t < s0.end) {
                val ldt = t.toLocalDateTime()
                val nextHour = ldt.withMinute(0).withSecond(0).withNano(0)
                    .plusHours(1).atZone(zone).toInstant().toEpochMilli()
                val slice = minOf(s0.end, nextHour) - t
                screenPerHour[ldt.hour] += slice.toDouble() / 60_000
                t += slice
            }
        }
        val notifPerHour = DoubleArray(24)
        val notifDates = fullDates.filter { notifSinceDate != null && !it.isBefore(notifSinceDate) }
        liveNotifs.filter { it.ts.toLocalDate() in notifDates }
            .forEach { notifPerHour[it.ts.toLocalDateTime().hour] += 1 }
        val nNotifDays = notifDates.size.coerceAtLeast(1)
        val hourly = (0..23).map {
            HourStat(it, screenPerHour[it] / nFull, notifPerHour[it] / nNotifDays)
        }

        onStep("앱별 사용 시간 계산 중")

        // ---- 앱별 지표 ----
        val perDayPerApp = HashMap<String, HashMap<LocalDate, Double>>()
        for (s0 in sessions) for (sp in s0.spans) {
            if (sp.pkg == launcher) continue
            val d = sp.start.toLocalDate()
            perDayPerApp.getOrPut(sp.pkg) { HashMap() }
                .merge(d, (sp.end - sp.start).toDouble() / 60_000, Double::plus)
        }
        val launchesPerApp = raw.filter {
            it.type == UsageEvents.Event.ACTIVITY_RESUMED && it.ts.toLocalDate() in fullDates
        }.groupingBy { it.pkg }.eachCount()

        val apps = perDayPerApp.entries.mapNotNull { (pkg, m) ->
            val vals = fullDates.map { m[it] ?: 0.0 }
            if (vals.sum() < 0.5) return@mapNotNull null
            val used = vals.count { it > 0.5 }
            val launches = (launchesPerApp[pkg] ?: 0).toDouble() / nFull
            AppStat(
                pkg = pkg,
                label = label(pkg),
                category = appCategory(pm, pkg),
                meanMinutes = vals.average().r2(),
                medianMinutes = vals.median().r2(),
                sdMinutes = vals.sd().r2(),
                minMinutes = (vals.minOrNull() ?: 0.0).r2(),
                maxMinutes = (vals.maxOrNull() ?: 0.0).r2(),
                daysUsed = used,
                launchesPerDay = launches.r2(),
                secondsPerLaunch = if (launches > 0) (vals.sum() * 60 / launches / nFull).r2() else 0.0,
                dailyMinutes = vals.map { it.r2() },
            )
        }.sortedByDescending { it.meanMinutes }

        onStep("앱 전환 분석 중")

        // ---- 앱 전환 (홈·동시실행 제외) ----
        val trans = HashMap<Pair<String, String>, MutableList<Long>>()
        val transVia = HashMap<Pair<String, String>, Int>()
        val transDays = HashMap<Pair<String, String>, MutableSet<LocalDate>>()
        for (s0 in sessions) {
            val sp = s0.spans.filter { it.pkg != launcher }
            for (i in 0 until sp.size - 1) {
                val a = sp[i]; val b = sp[i + 1]
                if (a.pkg == b.pkg) continue
                val k = a.pkg to b.pkg
                trans.getOrPut(k) { ArrayList() }.add((b.start - a.start) / 1000)
                if (b.viaLauncher) transVia.merge(k, 1, Int::plus)
                transDays.getOrPut(k) { HashSet() }.add(a.start.toLocalDate())
            }
        }
        val transitions = trans.entries.map { (k, gaps) ->
            TransitionStat(
                from = label(k.first), to = label(k.second),
                count = gaps.size,
                viaLauncher = transVia[k] ?: 0,
                medianGapSeconds = gaps.map { it.toDouble() }.median().roundToInt(),
                distinctDays = transDays[k]?.size ?: 0,
            )
        }.sortedByDescending { it.count }.take(20)

        // ---- 앱 페어: 양방향 전환 합산 + 분할화면 결합 ----
        class PairAcc {
            val gaps = ArrayList<Long>()
            var ab = 0; var via = 0
            val days = HashSet<LocalDate>()
        }
        val pairAgg = HashMap<Pair<String, String>, PairAcc>()
        for ((k, gaps) in trans) {
            val sorted = if (k.first < k.second) k else k.second to k.first
            val acc = pairAgg.getOrPut(sorted) { PairAcc() }
            acc.gaps += gaps
            if (k == sorted) acc.ab += gaps.size
            acc.via += transVia[k] ?: 0
            acc.days += transDays[k] ?: emptySet()
        }
        val appPairs = pairAgg.entries
            .filter { it.value.gaps.size >= 6 }
            .sortedByDescending { it.value.gaps.size }
            .take(10)
            .map { (k, acc) ->
                val n = acc.gaps.size
                AppPairStat(
                    a = label(k.first), b = label(k.second),
                    roundTrips = n,
                    abPct = (acc.ab * 100.0 / n).r2(),
                    medianGapSeconds = acc.gaps.map { it.toDouble() }.median().roundToInt(),
                    viaLauncherPct = (acc.via * 100.0 / n).r2(),
                    coUseCount = s.coUse[k]?.size ?: 0,
                    distinctDays = acc.days.size,
                )
            }

        val coUse = s.coUse.entries.map { (k, times) ->
            CoUseStat(
                a = label(k.first), b = label(k.second), count = times.size,
                distinctDays = times.map { it.toLocalDate() }.toSet().size,
            )
        }.sortedByDescending { it.count }.take(12)

        // ---- 첫 앱 / 마지막 앱 / 세션당 앱 개수 ----
        val first = sessions.mapNotNull { it.apps(launcher).firstOrNull() }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(10)
            .map { CountStat(label(it.key), it.value.toDouble()) }

        val lastApps = sessions.mapNotNull { it.apps(launcher).lastOrNull() }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(10)
            .map { CountStat(label(it.key), it.value.toDouble()) }

        // 아침 세션의 진입점. 기상 직후 고정 동작(결제·교통카드 등)이 여기서 보인다.
        val morningFirst = sessions
            .filter { it.start.toLocalDateTime().hour in 5..8 }
            .mapNotNull { it.apps(launcher).firstOrNull() }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(8)
            .map { CountStat(label(it.key), it.value.toDouble()) }

        val sessionAppCount = sessions.groupingBy {
            it.apps(launcher).toSet().size.coerceAtMost(5)
        }.eachCount().entries.sortedBy { it.key }.map {
            CountStat(if (it.key >= 5) "5개 이상" else "${it.key}개", it.value.toDouble())
        }

        onStep("수면 구간 추정 중")

        // ---- 수면 추정 ----
        val sleep = estimateSleep(sessions)

        // ---- 시각 고정성 ----
        val timeFixed = timeFixed(raw, fullDates, launcher, system, apps)

        // ---- 알림 ----
        val notifByApp = liveNotifs.filter { it.ts.toLocalDate() in notifDates }
            .groupingBy { it.pkg }.eachCount().entries
            .sortedByDescending { it.value }.take(10)
            .map { CountStat(label(it.key), (it.value.toDouble() / nNotifDays).r2()) }

        // 시스템 방해 이벤트 기준 발신 앱별 집계. 리스너와 달리 소급되고,
        // 관측 창도 리스너와 다르므로 하루 평균은 이 이벤트가 있는 날수로 나눈다.
        val sysInterrupts = raw.filter { it.type == EVENT_NOTIFICATION_INTERRUPTION }
        val sysInterruptDays = sysInterrupts.map { it.ts.toLocalDate() }
            .distinct().size.coerceAtLeast(1)
        val notifByAppInterrupt = sysInterrupts.groupingBy { it.pkg }.eachCount().entries
            .sortedByDescending { it.value }.take(10)
            .map { CountStat(label(it.key), (it.value.toDouble() / sysInterruptDays).r2()) }

        // ---- 장소 ----
        val ssidAlias = ssidAliases(netChanges)
        val places = places(netChanges, ssidAlias, now)

        // ---- 이벤트 연쇄 · 상태 맥락 ----
        onStep("이벤트 연쇄 분석 중")
        val (eventChains, appContext) = Chains.build(
            Chains.Input(
                raw = raw, notifs = notifs, netChanges = netChanges,
                btEvents = btEvents, healthSessions = healthSessions,
                ssidAlias = ssidAlias, systemPkgs = system,
                launcherPkg = launcher, zone = zone,
            ),
            label = ::label,
        )

        onStep("외출 지표 계산 중")
        // 다른 지표와 같은 창으로 조회한다. 통신량은 몇 달 소급되지만,
        // 지표마다 관측 창이 다르면 해석할 때마다 기간을 따로 밝혀야 한다.
        val netBuckets = dao.net(from, now)
        val outing = outing(netBuckets)
        val netApps = netApps(netBuckets)
        val outingHour = outingByHour(netBuckets)
        val outingWd = outing.groupBy { it.weekday }.map { (wd, list) ->
            WeekdayOuting(
                weekday = wd,
                isWeekend = list.first().isWeekend,
                meanShare = list.map { it.mobileShare }.average().r2(),
                days = list.size,
            )
        }.sortedBy { WEEKDAY_ORDER.indexOf(it.weekday) }

        onStep("검산 중")

        // ---- 검산 ----
        val screenFromSessions = fullStats.sumOf { it.screenMinutes } / nFull
        val screenFromApps = apps.sumOf { it.meanMinutes }
        val warnings = ArrayList<String>()
        if (screenFromApps > screenFromSessions * 1.02) {
            warnings += "앱 사용 시간 합계가 화면 시간을 넘습니다. 집계가 잘못되었을 수 있습니다."
        }
        if (fullStats.count { it.isWeekend } < 3) {
            warnings += "주말 표본이 ${fullStats.count { it.isWeekend }}일뿐입니다. 주말 수치는 참고용입니다."
        }
        if (sleep.size < 5) warnings += "수면 추정 표본이 ${sleep.size}일뿐입니다."
        // 데모는 남의 기기에서 만든 고정 로그다. 이 기기의 권한 상태로 판단하면
        // 엉뚱한 경고가 붙으므로, 대신 데모라는 사실 자체를 맨 앞에 알린다.
        if (demo) {
            warnings.add(
                0,
                "데모 데이터로 만든 리포트입니다. 이 기기의 실제 사용 기록이 아닙니다."
            )
        } else if (!Permissions.hasNotificationAccess(ctx)) {
            warnings += "알림 접근이 꺼져 있어 알림 통계가 비어 있습니다."
        }
        if (notifSinceDate != null && fullDates.any { it.isBefore(notifSinceDate) }) {
            warnings += "알림 리스너는 ${notifSinceDate}부터 기록됐습니다. " +
                "그 이전 날짜의 알림 수는 0으로 보이지만 실제로 0이었던 것은 아닙니다."
        }
        if (notifs.isEmpty() && raw.any { it.type == EVENT_NOTIFICATION_INTERRUPTION }) {
            warnings += "알림 리스너 기록은 없지만 시스템 알림 기록은 있습니다. " +
                "알림 접근을 켜기 전 구간이라 그렇습니다."
        }
        if (netBuckets.isEmpty()) {
            warnings += "통신량 기록이 없어 외출 지표를 만들 수 없습니다. " +
                "수집을 한 번 더 실행하거나 READ_PHONE_STATE 권한을 확인하세요."
        }
        if (netChanges.isEmpty()) {
            warnings += "네트워크 변경 기록이 없습니다. 장소 축을 만들 수 없습니다."
        }
        if (raw.isEmpty() && !demo) {
            warnings += "저장된 이벤트가 0건입니다. '사용 정보 접근' 권한을 켠 뒤 수집을 다시 실행하세요."
        }
        if (fullDayFallback) {
            warnings += "아직 온전한 하루가 없습니다(수집 시작 후 24시간 미만). " +
                "부분 기록된 날로 계산한 값이라 하루 평균이 실제보다 작습니다."
        }
        if (s.source == "activity_gap") {
            warnings += "이 기기가 화면 켜짐/꺼짐 이벤트를 주지 않아, 앱 전환 간격으로 세션을 대신 만들었습니다. " +
                "'앱을 열지 않고 화면만 켠' 경우는 집계되지 않습니다."
        }
        if (sessions.isEmpty() && raw.isNotEmpty()) {
            warnings += "이벤트는 ${raw.size}건 있는데 세션이 하나도 만들어지지 않았습니다. " +
                "진단의 이벤트 종류 분포를 확인해 주세요."
        }

        return Report(
            meta = Meta(
                generatedAt = now, tz = zone.id,
                spanFrom = raw.minOfOrNull { it.ts } ?: from,
                spanTo = raw.maxOfOrNull { it.ts } ?: now,
                fullDays = fullDates.map { it.toString() },
                partialDays = partial.map { it.toString() },
                eventCount = raw.size,
                device = android.os.Build.MODEL,
            ),
            days = dayStats, hourly = hourly, apps = apps.take(20), sleep = sleep,
            nightHabit = nightHabit(sessions, launcher, system),
            health = healthSessions.groupBy { it.kind }.map { (k, list) ->
                HealthStat(
                    kind = k,
                    sessions = list.size,
                    meanMinutes = list.map { (it.tsEnd - it.tsStart) / 60_000.0 }
                        .average().r2(),
                    lastStart = list.maxOf { it.tsStart },
                )
            }.sortedByDescending { it.sessions },
            transitions = transitions, appPairs = appPairs,
            eventChains = eventChains, appContext = appContext,
            coUse = coUse, firstApps = first,
            notifByApp = notifByApp, notifByAppInterrupt = notifByAppInterrupt,
            lastApps = lastApps, morningFirstApps = morningFirst,
            sessionAppCount = sessionAppCount,
            places = places, timeFixed = timeFixed, netApps = netApps,
            outing = outing, outingByWeekday = outingWd, outingByHour = outingHour,
            quality = Quality(
                screenMinutesFromSessions = screenFromSessions.r2(),
                screenMinutesFromApps = screenFromApps.r2(),
                reconciliationDelta = (screenFromSessions - screenFromApps).r2(),
                duplicateEventsDropped = s.duplicatesDropped,
                coUseEventsExcluded = s.coUseEventsExcluded,
                shortWakesDropped = s.shortWakesDropped,
                weekdayCount = fullStats.count { !it.isWeekend },
                weekendCount = fullStats.count { it.isWeekend },
                // 데모는 이 기기의 권한과 무관하다. 데이터가 실제로 들어 있는지로 답한다.
                notifAccessGranted = if (demo) notifs.isNotEmpty()
                else Permissions.hasNotificationAccess(ctx),
                locationGranted = if (demo) netChanges.any { it.ssid != null }
                else ctx.checkSelfPermission(
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED,
                fullDayFallback = fullDayFallback,
                warnings = warnings,
            ),
            diagnostics = Diagnostics(
                storedEvents = dao.eventCount(),
                oldestEventTs = dao.firstEventTs(),
                newestEventTs = raw.maxOfOrNull { it.ts },
                lastCollectTs = dao.get(UsageCollector.KEY_LAST_SYNC)?.toLongOrNull(),
                eventTypeCounts = raw.groupingBy { eventTypeName(it.type) }.eachCount()
                    .entries.sortedByDescending { it.value }.associate { it.key to it.value },
                screenEvents = s.screenEventCount,
                activityResumedEvents = raw.count {
                    it.type == UsageEvents.Event.ACTIVITY_RESUMED
                },
                sessionsBuilt = sessions.size,
                sessionSource = s.source,
                storedNotifs = notifs.size,
                notifRepostsCollapsed = repostsCollapsed,
                systemInterruptEvents = raw.count { it.type == EVENT_NOTIFICATION_INTERRUPTION },
                notifListenerSince = notifSince,
                netChangeRecords = netChanges.size,
                usageAccessGranted = if (demo) true else Permissions.hasUsageAccess(ctx),
            ),
        )
    }

    // ------------------------------------------------------------------

    /**
     * 수면 직전 60분과 심야(22~02시)에 어떤 앱을 쓰는가.
     *
     * 수면 창 판정은 [estimateSleep] 과 같은 규칙(3시간 이상 공백, 밤 시작)을
     * 쓴다 — 두 지표가 다른 밤을 세면 해석하는 쪽이 교차 검증을 할 수 없다.
     */
    private fun nightHabit(
        sessions: List<Sessionizer.Session>,
        launcher: String,
        system: Set<String>,
    ): List<NightHabitStat> {
        data class Acc(var preMs: Long = 0, val preNights: MutableSet<Long> = HashSet(), var lateMs: Long = 0)
        val acc = HashMap<String, Acc>()
        var nights = 0
        var preTotalMs = 0L

        fun overlap(s: Long, e: Long, from: Long, to: Long) = maxOf(0L, minOf(e, to) - maxOf(s, from))

        val seenNights = HashSet<LocalDate>()
        for (i in 0 until sessions.size - 1) {
            val gapStart = sessions[i].end
            val gapEnd = sessions[i + 1].start
            if ((gapEnd - gapStart).toDouble() / 3_600_000 < 3.0) continue
            val st = gapStart.toLocalDateTime()
            if (st.hour < 20 && st.hour >= 5) continue
            // 같은 밤에 공백이 두 번(자다 깨서 확인) 생겨도 심야 창은 한 번만
            // 센다 — 이중 가산되면 밤당 평균이 부풀려진다(검토에서 확인).
            // 첫 공백 = 처음 잠든 시각이 그 밤의 대표다.
            val nightDate = if (st.hour >= 20) st.toLocalDate() else st.toLocalDate().minusDays(1)
            if (!seenNights.add(nightDate)) continue
            nights++

            // 수면 직전 60분
            val preFrom = gapStart - 60L * 60 * 1000
            val lateFrom = nightDate.atTime(22, 0).atZone(zone).toInstant().toEpochMilli()
            val lateTo = nightDate.plusDays(1).atTime(2, 0).atZone(zone).toInstant().toEpochMilli()

            for (sess in sessions) {
                if (sess.end < minOf(preFrom, lateFrom)) continue
                if (sess.start > maxOf(gapStart, lateTo)) break
                for (span in sess.spans) {
                    if (span.pkg == launcher || span.pkg in system) continue
                    val pre = overlap(span.start, span.end, preFrom, gapStart)
                    val late = overlap(span.start, span.end, lateFrom, lateTo)
                    if (pre <= 0 && late <= 0) continue
                    val a = acc.getOrPut(span.pkg) { Acc() }
                    if (pre > 0) { a.preMs += pre; a.preNights += gapStart; preTotalMs += pre }
                    a.lateMs += late
                }
            }
        }
        if (nights == 0) return emptyList()

        return acc.entries
            .filter { it.value.preNights.size >= 2 || it.value.lateMs >= 20L * 60 * 1000 }
            .map { (pkg, a) ->
                NightHabitStat(
                    label = label(pkg),
                    preSleepSharePct = if (preTotalMs > 0)
                        (a.preMs * 100.0 / preTotalMs).r2() else 0.0,
                    preSleepNights = a.preNights.size,
                    lateNightMinutesPerNight = (a.lateMs / 60_000.0 / nights).r2(),
                    nightsObserved = nights,
                )
            }
            .sortedByDescending { it.preSleepSharePct }
            .take(8)
    }

    /**
     * 화면 활동의 긴 공백을 수면 후보로 본다.
     * 이건 "잤다"가 아니라 "폰을 안 봤다"는 사실이므로, 이름과 설명을 그렇게 유지한다.
     */
    private fun estimateSleep(sessions: List<Sessionizer.Session>): List<SleepStat> {
        val out = ArrayList<SleepStat>()
        for (i in 0 until sessions.size - 1) {
            val gapStart = sessions[i].end
            val gapEnd = sessions[i + 1].start
            val hours = (gapEnd - gapStart).toDouble() / 3_600_000
            if (hours < 3.0) continue
            val st = gapStart.toLocalDateTime()
            // 밤에 시작한 공백만 수면 후보로 본다. 낮의 긴 공백은 다른 활동이다.
            if (st.hour < 20 && st.hour >= 5) continue
            val en = gapEnd.toLocalDateTime()
            val nightOf = if (st.hour >= 20) st.toLocalDate() else st.toLocalDate().minusDays(1)
            out += SleepStat(
                nightOf = nightOf.toString(),
                weekday = nightOf.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN),
                startMinuteOfDay = st.hour * 60 + st.minute,
                endMinuteOfDay = en.hour * 60 + en.minute,
                hours = hours.r2(),
            )
        }
        return out
    }

    /**
     * 시각 고정성. 자주 쓰는 앱은 우연히도 값이 높아지므로
     * 하루 실행 횟수를 함께 실어 보내 해석하는 쪽이 보정할 수 있게 한다.
     */
    private fun timeFixed(
        raw: List<UsageEventRow>, fullDates: List<LocalDate>,
        launcher: String, system: Set<String>, apps: List<AppStat>,
    ): List<TimeFixedStat> {
        val launchRate = apps.associate { it.pkg to it.launchesPerDay }
        val band = HashMap<Pair<String, Int>, MutableSet<LocalDate>>()
        raw.asSequence()
            .filter { it.type == UsageEvents.Event.ACTIVITY_RESUMED }
            .filter { it.pkg != launcher && it.pkg !in system }
            .forEach {
                val ldt = it.ts.toLocalDateTime()
                if (ldt.toLocalDate() in fullDates) {
                    band.getOrPut(it.pkg to ldt.hour) { HashSet() }.add(ldt.toLocalDate())
                }
            }
        val n = fullDates.size
        return band.entries
            .filter { it.value.size >= maxOf(3, (n * 0.6).roundToInt()) }
            .sortedByDescending { it.value.size }
            .take(15)
            .map {
                TimeFixedStat(
                    pkg = it.key.first, label = label(it.key.first), hour = it.key.second,
                    daysHit = it.value.size, daysObserved = n,
                    launchesPerDay = launchRate[it.key.first] ?: 0.0,
                )
            }
    }

    /**
     * SSID → 별칭(Wi-Fi A, B…) 매핑. 등장 순서대로 준다 —
     * 화면과 리포트에 실제 이름을 띄우지 않기 위해서.
     * places 와 이벤트 연쇄가 같은 별칭을 쓰도록 한 곳에서 만든다.
     */
    private fun ssidAliases(changes: List<com.routineai.data.NetworkChangeRow>): Map<String, String> {
        val alias = LinkedHashMap<String, String>()
        for (r in changes.sortedBy { it.ts }) {
            if (r.kind == "wifi") {
                alias.getOrPut(r.ssid ?: "wifi-unknown") { "Wi-Fi " + ('A' + alias.size) }
            }
        }
        return alias
    }

    /** 네트워크 변경 기록으로 체류를 만든다. */
    private fun places(
        changes: List<com.routineai.data.NetworkChangeRow>,
        alias: Map<String, String>,
        now: Long,
    ): List<PlaceStat> {
        if (changes.isEmpty()) return emptyList()
        fun aliasOf(r: com.routineai.data.NetworkChangeRow): String = when (r.kind) {
            "wifi" -> alias[r.ssid ?: "wifi-unknown"] ?: "Wi-Fi"
            "cellular" -> "모바일"
            else -> "연결 없음"
        }
        val hours = HashMap<String, Double>()
        val nights = HashMap<String, Int>()
        val daytime = HashMap<String, Double>()
        var dayTotal = 0.0
        for (i in changes.indices) {
            val a = changes[i]
            val end = changes.getOrNull(i + 1)?.ts ?: now
            val key = aliasOf(a)
            var t = a.ts
            while (t < end) {
                val ldt = t.toLocalDateTime()
                val nextHour = ldt.withMinute(0).withSecond(0).withNano(0)
                    .plusHours(1).atZone(zone).toInstant().toEpochMilli()
                val slice = (minOf(end, nextHour) - t).toDouble() / 3_600_000
                hours.merge(key, slice, Double::plus)
                if (ldt.hour in 0..5) nights.merge(key, 1, Int::plus)
                if (ldt.hour in 9..18) { daytime.merge(key, slice, Double::plus); dayTotal += slice }
                t = minOf(end, nextHour)
            }
        }
        return hours.entries.sortedByDescending { it.value }.map {
            PlaceStat(
                alias = it.key,
                kind = if (it.key.startsWith("Wi-Fi")) "wifi" else "other",
                nights = (nights[it.key] ?: 0) / 6,
                daytimeShare = if (dayTotal > 0) ((daytime[it.key] ?: 0.0) / dayTotal * 100).r2() else 0.0,
                totalHours = it.value.r2(),
            )
        }
    }

    /**
     * 낮(09~19시) 시간 버킷마다 모바일 바이트와 Wi-Fi 바이트를 견주어
     * 모바일이 우세했던 버킷의 비율을 낸다.
     *
     * 하루에 관측 버킷이 3개 미만이면 표본이 모자라 제외한다.
     */
    private fun outing(rows: List<com.routineai.data.NetBucketRow>): List<OutingStat> {
        if (rows.isEmpty()) return emptyList()
        // (날짜, 버킷) -> transport 별 바이트
        val cell = HashMap<Pair<LocalDate, Long>, LongArray>()
        for (r in rows) {
            val ldt = r.bucketStart.toLocalDateTime()
            if (ldt.hour !in 9..18) continue
            val key = ldt.toLocalDate() to (r.bucketStart / BUCKET_MS)
            val arr = cell.getOrPut(key) { LongArray(2) }
            arr[r.transport] += r.rxBytes + r.txBytes
        }
        return cell.entries.groupBy { it.key.first }
            .filter { it.value.size >= 3 }
            .map { (d, buckets) ->
                val mobile = buckets.count { it.value[0] > it.value[1] }
                OutingStat(
                    date = d.toString(),
                    weekday = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN),
                    isWeekend = d.dayOfWeek.value >= 6,
                    mobileShare = (mobile.toDouble() / buckets.size * 100).r2(),
                    bucketsObserved = buckets.size,
                )
            }.sortedBy { it.date }
    }

    /**
     * 앱별 통신량. uid 를 패키지로 되돌려 이름을 붙인다.
     * 삭제된 앱·시스템 uid 는 되돌릴 수 없어 "uid1234" 로 남긴다.
     */
    private fun netApps(rows: List<com.routineai.data.NetBucketRow>): List<NetAppStat> {
        if (rows.isEmpty()) return emptyList()
        val byUid = HashMap<Int, LongArray>()   // [모바일, 전체]
        for (r in rows) {
            val a = byUid.getOrPut(r.uid) { LongArray(2) }
            val b = r.rxBytes + r.txBytes
            if (r.transport == NetworkCollector.TRANSPORT_MOBILE) a[0] += b
            a[1] += b
        }
        return byUid.entries.sortedByDescending { it.value[1] }.take(10).map { (uid, v) ->
            val pkg = runCatching { pm.getPackagesForUid(uid)?.firstOrNull() }.getOrNull()
            NetAppStat(
                label = pkg?.let { label(it) } ?: "uid$uid",
                totalMb = (v[1].toDouble() / 1_000_000).r2(),
                mobilePct = if (v[1] > 0) (v[0].toDouble() / v[1] * 100).r2() else 0.0,
            )
        }
    }

    /**
     * 시간대별 모바일 우세율. 요일 축과 달리 하루 안의 출입 경계가 보인다.
     * 버킷 시작 시각 기준이므로 시간대 눈금은 기기 표준시로 환산한 값이다.
     */
    private fun outingByHour(rows: List<com.routineai.data.NetBucketRow>): List<HourOuting> {
        if (rows.isEmpty()) return emptyList()
        val cell = HashMap<Pair<LocalDate, Int>, LongArray>()   // [모바일, Wi-Fi]
        for (r in rows) {
            val ldt = r.bucketStart.toLocalDateTime()
            val key = ldt.toLocalDate() to ldt.hour
            cell.getOrPut(key) { LongArray(2) }[r.transport] += r.rxBytes + r.txBytes
        }
        return cell.entries.groupBy { it.key.second }
            .map { (hour, list) ->
                val mobile = list.count { it.value[0] > it.value[1] }
                HourOuting(
                    hour = hour,
                    mobileShare = (mobile.toDouble() / list.size * 100).r2(),
                    days = list.size,
                )
            }.sortedBy { it.hour }
    }

    // ------------------------------------------------------------------

    private fun resolveLauncher(): String {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName ?: "com.android.launcher"
    }

    /** 사용자가 '연다'고 인식하지 않는 시스템 표면들 */
    private fun systemPackages(): Set<String> = setOf(
        "android", "com.android.systemui", "com.android.intentresolver",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
    ) + runCatching {
        pm.getInstalledApplications(0)
            .filter { it.packageName.contains("inputmethod") || it.packageName.contains("honeyboard") }
            .map { it.packageName }
    }.getOrDefault(emptyList())

    private val labelCache = HashMap<String, String>()
    private fun label(pkg: String): String = labelCache.getOrPut(pkg) {
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg.substringAfterLast('.'))
    }

    private fun Long.toLocalDateTime(): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(this), zone)

    private fun Long.toLocalDate(): LocalDate = toLocalDateTime().toLocalDate()

    /** 진단 화면에 보여줄 이벤트 종류 이름. 모르는 값은 번호 그대로 둔다. */
    private fun eventTypeName(t: Int): String = when (t) {
        1 -> "앱 시작 (ACTIVITY_RESUMED)"
        2 -> "앱 일시정지 (ACTIVITY_PAUSED)"
        23 -> "앱 정지 (ACTIVITY_STOPPED)"
        5 -> "화면 구성 변경"
        7 -> "사용자 조작"
        8 -> "바로가기 실행"
        11 -> "대기 버킷 변경"
        12 -> "알림 방해"
        10 -> "알림 확인"
        15 -> "화면 켜짐 (SCREEN_INTERACTIVE)"
        16 -> "화면 꺼짐 (SCREEN_NON_INTERACTIVE)"
        17 -> "잠금 표시"
        18 -> "잠금 해제"
        19 -> "포그라운드 서비스 시작"
        20 -> "포그라운드 서비스 종료"
        26 -> "기기 종료"
        27 -> "기기 시작"
        else -> "기타 (type=$t)"
    }
}

/** usagestats NOTIFICATION_INTERRUPTION. 공개 상수가 아니라 값으로 둔다. */
private const val EVENT_NOTIFICATION_INTERRUPTION = 12

private const val BUCKET_MS = 2L * 60 * 60 * 1000

/** 같은 (앱, 채널) 알림이 이 간격 안에 다시 오면 재게시로 본다 */
private const val NOTIF_REPOST_WINDOW_MS = 5_000L
private val WEEKDAY_ORDER = listOf("월", "화", "수", "목", "금", "토", "일")

/**
 * 개발자가 매니페스트에 선언한 앱 분류. 없으면 null — 지어내지 않는다.
 * 해석(1차·심층)이 앱의 목적을 정의할 때 세계지식의 보조 단서다.
 */
internal fun appCategory(pm: PackageManager, pkg: String): String? = runCatching {
    when (pm.getApplicationInfo(pkg, 0).category) {
        android.content.pm.ApplicationInfo.CATEGORY_GAME -> "게임"
        android.content.pm.ApplicationInfo.CATEGORY_AUDIO -> "오디오·음악"
        android.content.pm.ApplicationInfo.CATEGORY_VIDEO -> "영상"
        android.content.pm.ApplicationInfo.CATEGORY_IMAGE -> "사진·이미지"
        android.content.pm.ApplicationInfo.CATEGORY_SOCIAL -> "소셜·커뮤니케이션"
        android.content.pm.ApplicationInfo.CATEGORY_NEWS -> "뉴스"
        android.content.pm.ApplicationInfo.CATEGORY_MAPS -> "지도·이동"
        android.content.pm.ApplicationInfo.CATEGORY_PRODUCTIVITY -> "생산성"
        android.content.pm.ApplicationInfo.CATEGORY_ACCESSIBILITY -> "접근성"
        else -> null
    }
}.getOrNull()

// ---- 작은 통계 헬퍼 ----

private fun Double.r2() = Math.round(this * 100.0) / 100.0

private fun List<Double>.median(): Double {
    if (isEmpty()) return 0.0
    val s = sorted()
    val m = s.size / 2
    return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
}

private fun List<Double>.sd(): Double {
    if (size < 2) return 0.0
    val m = average()
    return sqrt(sumOf { (it - m) * (it - m) } / size)
}
