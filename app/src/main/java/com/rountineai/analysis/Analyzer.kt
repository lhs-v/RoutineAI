package com.rountineai.analysis

import android.app.usage.UsageEvents
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import com.rountineai.collect.NetworkCollector
import com.rountineai.collect.Permissions
import com.rountineai.data.Db
import com.rountineai.data.NotifEventRow
import com.rountineai.data.UsageEventRow
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
class Analyzer(private val ctx: Context) {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val dao = Db.get(ctx).dao()
    private val pm = ctx.packageManager

    suspend fun build(days: Int = 30): Report {
        val now = System.currentTimeMillis()
        val from = now - days.toLong() * 24 * 60 * 60 * 1000

        val raw = dao.events(from, now)
        val notifs = dao.notifs(from, now)
        val netChanges = dao.netChanges(from, now)

        val launcher = resolveLauncher()
        val system = systemPackages()

        val s = Sessionizer.build(raw, launcher, system)
        val sessions = s.sessions

        // ---- 날짜별 커버리지: 부분일 판정 ----
        val byDate = raw.groupBy { it.ts.toLocalDate() }
        val allDates = byDate.keys.sorted()
        val fullDates = allDates.filter { d ->
            val ts = byDate.getValue(d)
            val first = ts.minOf { it.ts }.toLocalDateTime()
            val last = ts.maxOf { it.ts }.toLocalDateTime()
            // 하루의 양 끝을 충분히 덮어야 온전한 날로 본다.
            first.hour <= 2 && last.hour >= 21
        }
        val partial = allDates - fullDates.toSet()

        // ---- 일별 지표 ----
        val sessByDate = sessions.groupBy { it.start.toLocalDate() }
        val notifByDate = notifs.filter { it.interruptive }.groupBy { it.ts.toLocalDate() }
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
        notifs.filter { it.interruptive && it.ts.toLocalDate() in fullDates }
            .forEach { notifPerHour[it.ts.toLocalDateTime().hour] += 1 }
        val hourly = (0..23).map {
            HourStat(it, screenPerHour[it] / nFull, notifPerHour[it] / nFull)
        }

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
                meanMinutes = vals.average().r2(),
                medianMinutes = vals.median().r2(),
                sdMinutes = vals.sd().r2(),
                minMinutes = (vals.minOrNull() ?: 0.0).r2(),
                maxMinutes = (vals.maxOrNull() ?: 0.0).r2(),
                daysUsed = used,
                launchesPerDay = launches.r2(),
                secondsPerLaunch = if (launches > 0) (vals.sum() * 60 / launches / nFull).r2() else 0.0,
            )
        }.sortedByDescending { it.meanMinutes }

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

        val coUse = s.coUse.entries.map { (k, times) ->
            CoUseStat(
                a = label(k.first), b = label(k.second), count = times.size,
                distinctDays = times.map { it.toLocalDate() }.toSet().size,
            )
        }.sortedByDescending { it.count }.take(12)

        // ---- 첫 앱 / 세션당 앱 개수 ----
        val first = sessions.mapNotNull { it.apps(launcher).firstOrNull() }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(10)
            .map { CountStat(label(it.key), it.value.toDouble()) }

        val sessionAppCount = sessions.groupingBy {
            it.apps(launcher).toSet().size.coerceAtMost(5)
        }.eachCount().entries.sortedBy { it.key }.map {
            CountStat(if (it.key >= 5) "5개 이상" else "${it.key}개", it.value.toDouble())
        }

        // ---- 수면 추정 ----
        val sleep = estimateSleep(sessions)

        // ---- 시각 고정성 ----
        val timeFixed = timeFixed(raw, fullDates, launcher, system, apps)

        // ---- 알림 ----
        val notifByApp = notifs.filter { it.interruptive && it.ts.toLocalDate() in fullDates }
            .groupingBy { it.pkg }.eachCount().entries
            .sortedByDescending { it.value }.take(10)
            .map { CountStat(label(it.key), (it.value.toDouble() / nFull).r2()) }

        // ---- 장소 ----
        val places = places(netChanges, now)

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
        if (!Permissions.hasNotificationAccess(ctx)) {
            warnings += "알림 접근이 꺼져 있어 알림 통계가 비어 있습니다."
        }
        if (netChanges.isEmpty()) {
            warnings += "네트워크 변경 기록이 없습니다. 장소 축을 만들 수 없습니다."
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
            transitions = transitions, coUse = coUse, firstApps = first,
            notifByApp = notifByApp, sessionAppCount = sessionAppCount,
            places = places, timeFixed = timeFixed,
            quality = Quality(
                screenMinutesFromSessions = screenFromSessions.r2(),
                screenMinutesFromApps = screenFromApps.r2(),
                reconciliationDelta = (screenFromSessions - screenFromApps).r2(),
                duplicateEventsDropped = s.duplicatesDropped,
                coUseEventsExcluded = s.coUseEventsExcluded,
                shortWakesDropped = s.shortWakesDropped,
                weekdayCount = fullStats.count { !it.isWeekend },
                weekendCount = fullStats.count { it.isWeekend },
                notifAccessGranted = Permissions.hasNotificationAccess(ctx),
                locationGranted = ctx.checkSelfPermission(
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED,
                warnings = warnings,
            ),
        )
    }

    // ------------------------------------------------------------------

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
     * 네트워크 변경 기록으로 체류를 만든다.
     * SSID 는 그대로 두지 않고 등장 순서대로 별칭을 준다 — 화면에 실제 이름을 띄우지 않기 위해서.
     */
    private fun places(changes: List<com.rountineai.data.NetworkChangeRow>, now: Long): List<PlaceStat> {
        if (changes.isEmpty()) return emptyList()
        val alias = HashMap<String, String>()
        fun aliasOf(r: com.rountineai.data.NetworkChangeRow): String = when (r.kind) {
            "wifi" -> alias.getOrPut(r.ssid ?: "wifi-unknown") {
                "Wi-Fi " + ('A' + alias.size)
            }
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
}

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
