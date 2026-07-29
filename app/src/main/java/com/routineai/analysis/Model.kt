package com.routineai.analysis

import kotlinx.serialization.Serializable

/**
 * 대시보드가 소비하는 리포트. 이 객체가 그대로 JSON 이 되어 WebView 로 넘어간다.
 *
 * 원칙: 여기에는 **계산된 값만** 담는다. 해석 문장은 들어가지 않는다.
 * 해석은 [com.routineai.interpret.Interpreter] 가 이 JSON 을 보고 따로 만든다.
 */
@Serializable
data class Report(
    val meta: Meta,
    val days: List<DayStat>,
    val hourly: List<HourStat>,
    val apps: List<AppStat>,
    val sleep: List<SleepStat>,
    val transitions: List<TransitionStat>,
    val coUse: List<CoUseStat>,
    val firstApps: List<CountStat>,
    val notifByApp: List<CountStat>,
    val sessionAppCount: List<CountStat>,
    val places: List<PlaceStat>,
    val timeFixed: List<TimeFixedStat>,
    val quality: Quality,
    val diagnostics: Diagnostics,
)

/**
 * "왜 값이 비었는가"에 답하기 위한 진단 정보.
 *
 * 대시보드가 0으로 나올 때 원인이 권한인지, 수집인지, 집계인지 구분할 수 있어야 한다.
 * 기기마다 어떤 이벤트 종류를 실제로 내주는지도 다르므로 그 분포를 그대로 싣는다.
 */
@Serializable
data class Diagnostics(
    val storedEvents: Int,
    val oldestEventTs: Long?,
    val newestEventTs: Long?,
    val lastCollectTs: Long?,
    /** 이벤트 종류별 개수. 기기가 무엇을 주는지 확인하는 용도 */
    val eventTypeCounts: Map<String, Int>,
    val screenEvents: Int,
    val activityResumedEvents: Int,
    val sessionsBuilt: Int,
    /** "screen_events" = 화면 이벤트로 세션 구성, "activity_gap" = 앱 전환 간격으로 대체 */
    val sessionSource: String,
    val storedNotifs: Int,
    val netChangeRecords: Int,
    val usageAccessGranted: Boolean,
)

@Serializable
data class Meta(
    val generatedAt: Long,
    val tz: String,
    val spanFrom: Long,
    val spanTo: Long,
    /** 온전히 기록된 날 (부분일 제외). 모든 평균은 이 날들로만 계산한다. */
    val fullDays: List<String>,
    val partialDays: List<String>,
    val eventCount: Int,
    val device: String,
)

@Serializable
data class DayStat(
    val date: String,
    val weekday: String,
    val isWeekend: Boolean,
    val full: Boolean,
    val screenMinutes: Double,
    val wakes: Int,
    val microWakes: Int,
    val noAppWakes: Int,
    val notifs: Int,
)

@Serializable
data class HourStat(val hour: Int, val screenMinutes: Double, val notifs: Double)

/**
 * 앱별 지표. 평균만 쓰면 습관과 사건을 구분할 수 없으므로
 * 중앙값·편차·사용일수를 항상 함께 낸다.
 */
@Serializable
data class AppStat(
    val pkg: String,
    val label: String,
    val meanMinutes: Double,
    val medianMinutes: Double,
    val sdMinutes: Double,
    val minMinutes: Double,
    val maxMinutes: Double,
    val daysUsed: Int,
    val launchesPerDay: Double,
    val secondsPerLaunch: Double,
)

@Serializable
data class SleepStat(
    val nightOf: String,
    val weekday: String,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val hours: Double,
)

/** 홈 화면과 동시 실행을 걷어낸 순수 앱→앱 이동 */
@Serializable
data class TransitionStat(
    val from: String,
    val to: String,
    val count: Int,
    val viaLauncher: Int,
    val medianGapSeconds: Int,
    val distinctDays: Int,
)

/** 같은 시각에 함께 떠 있던 조합 = 분할화면 */
@Serializable
data class CoUseStat(val a: String, val b: String, val count: Int, val distinctDays: Int)

@Serializable
data class CountStat(val key: String, val count: Double)

/** 앱이 설치된 뒤부터 직접 기록한 네트워크 체류 */
@Serializable
data class PlaceStat(
    val alias: String,
    val kind: String,
    val nights: Int,
    val daytimeShare: Double,
    val totalHours: Double,
)

/** N일 중 n일 그 시간대에 그 앱을 열었는지 */
@Serializable
data class TimeFixedStat(
    val pkg: String,
    val label: String,
    val hour: Int,
    val daysHit: Int,
    val daysObserved: Int,
    val launchesPerDay: Double,
)

/**
 * 데이터 품질 자체를 리포트에 담는다.
 * 해석하는 쪽이 이 값을 보고 결론 등급을 정할 수 있어야 한다.
 */
@Serializable
data class Quality(
    val screenMinutesFromSessions: Double,
    val screenMinutesFromApps: Double,
    /** 두 값의 차이. 앱 합계가 더 크면 계산이 잘못된 것이다. */
    val reconciliationDelta: Double,
    val duplicateEventsDropped: Int,
    val coUseEventsExcluded: Int,
    val shortWakesDropped: Int,
    val weekdayCount: Int,
    val weekendCount: Int,
    val notifAccessGranted: Boolean,
    val locationGranted: Boolean,
    /** 관측 창이 하루를 온전히 덮은 날이 없어 전체 날짜를 그대로 쓴 경우 true */
    val fullDayFallback: Boolean,
    val warnings: List<String>,
)
