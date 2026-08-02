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
    /**
     * Health Connect 세션 요약(수면·운동). 세션이 연쇄 임계(횟수≥3)에 못
     * 미쳐도 여기엔 나온다 — "수집이 흐르고 있는가"가 보이지 않으면
     * 권한 문제와 구분할 수 없다(실제로 그래서 헤맸다).
     */
    val health: List<HealthStat> = emptyList(),
    val transitions: List<TransitionStat>,
    val appPairs: List<AppPairStat>,
    val eventChains: List<ChainStat>,
    val appContext: List<AppContextStat>,
    val coUse: List<CoUseStat>,
    val firstApps: List<CountStat>,
    val notifByApp: List<CountStat>,
    /**
     * 발신 앱별 알림 — 시스템 알림 이벤트(usagestats type 12) 기준, 건/일.
     *
     * [notifByApp] 과 달리 알림 접근 권한 없이도 수집되고, 시스템이 이벤트를 며칠
     * 보관하므로 앱 설치 전 구간까지 소급된다. 소리·방해 여부의 분류가 아니라
     * "어느 앱이 얼마나 보내는가"가 목적이다 — 해석하는 쪽이 앱 이름을 보고
     * 정기·시스템성 알림과 사람이 응답해야 하는 알림을 구분할 수 있다.
     */
    val notifByAppInterrupt: List<CountStat>,
    /** 화면을 끄기 직전 마지막으로 쓴 앱. [firstApps] 의 반대쪽 끝. */
    val lastApps: List<CountStat>,
    /** 아침(05~09시) 세션에서 처음 연 앱. 하루의 진입점을 보기 위한 것. */
    val morningFirstApps: List<CountStat>,
    val sessionAppCount: List<CountStat>,
    val places: List<PlaceStat>,
    val timeFixed: List<TimeFixedStat>,
    /**
     * 앱별 통신량. 기기가 통신했다는 것이지 사람이 썼다는 뜻이 아니다 —
     * 화면이 꺼진 동안의 백그라운드 트래픽이 섞여 있다.
     * 모바일 비율이 높은 앱은 Wi-Fi 밖(외출 중)에서 쓰는 앱이라는 신호다.
     */
    val netApps: List<NetAppStat>,
    val outing: List<OutingStat>,
    val outingByWeekday: List<WeekdayOuting>,
    /** 시간대별로 모바일이 우세했던 날의 비율. 하루의 출입 경계가 보인다. */
    val outingByHour: List<HourOuting>,
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
    /**
     * 같은 알림의 재게시로 보고 접은 행 수.
     * 리스너가 key 로 걸러내기 전에 쌓인 기록에는 미디어 진행률 갱신 등이
     * 도착으로 남아 있어, 집계 때 (앱, 채널) 5초 창으로 접는다.
     */
    val notifRepostsCollapsed: Int,
    /** 이 기기가 usagestats 로 NOTIFICATION_INTERRUPTION 을 내주는가 (0 이면 안 줌) */
    val systemInterruptEvents: Int,
    /** 알림 리스너가 처음 기록을 남긴 시각. 이 이전은 알림 데이터가 없다. */
    val notifListenerSince: Long?,
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
    /** 지속 알림(ONGOING)을 뺀 전체 알림 */
    val notifs: Int,
    /**
     * 이 날 알림 리스너가 켜져 있었는가.
     * 리스너는 소급되지 않으므로, 켜기 전 날은 알림이 0 으로 보인다.
     * 그 날들을 평균에 넣으면 알림 수가 실제보다 낮게 나온다.
     */
    val notifCovered: Boolean,
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
    /**
     * 온전한 날짜별 사용 시간(분), meta.fullDays 와 같은 순서.
     * 요약 통계만으로는 궤적을 볼 수 없어서 원본 배열을 함께 싣는다 —
     * 해석하는 쪽이 "매일 비슷했는가, 하루가 튀었는가"를 직접 확인할 수 있다.
     */
    val dailyMinutes: List<Double>,
)

/** Health Connect 세션 종류별 요약. kind: "sleep" | "exercise:걷기" */
@Serializable
data class HealthStat(
    val kind: String,
    val sessions: Int,
    val meanMinutes: Double,
    /** 마지막 세션 시작 시각 — 동기화가 살아 있는지의 증거 */
    val lastStart: Long,
)

@Serializable
data class SleepStat(
    val nightOf: String,
    val weekday: String,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val hours: Double,
)

/**
 * 비앱 이벤트(트리거 후보) → 앱 실행(행동) 연쇄.
 *
 * 루틴의 형태가 정확히 "조건 발생 → 행동"이므로 이 방향만 집계한다.
 * 앱→앱 이동은 [Report.transitions] 가 따로 있다.
 * 반복 횟수가 많고 간격이 짧고 여러 날에 걸칠수록 자동화 가치가 높지만,
 * 맥락상 말이 되는지는 해석하는 쪽이 판단한다.
 */
@Serializable
data class ChainStat(
    /** 트리거: "BT연결:Buds3" | "Wi-Fi 연결:Wi-Fi A" | "모바일 전환" | "운동 시작:걷기" */
    val trigger: String,
    /** 행동: 앱 표시 이름 */
    val app: String,
    val count: Int,
    val minGapSeconds: Int,
    val medianGapSeconds: Int,
    val maxGapSeconds: Int,
    val distinctDays: Int,
)

/**
 * 앱 실행 순간의 상태 맥락 프로파일.
 *
 * "이 앱은 어떤 상황에서 열리는가"에 답한다 — 이동통신 중(밖), BT 기기 연결 중,
 * 알림이 직전에 온 뒤(알림 주도), 몰리는 시간대. 여기서 출퇴근·운동 같은
 * 직접 관측 불가한 상태를 해석하는 쪽이 유추한다.
 */
@Serializable
data class AppContextStat(
    val label: String,
    val launches: Int,
    /** 실행 순간 이동통신 상태였던 비율 0~100 (네트워크 기록 없으면 -1) */
    val cellularPct: Double,
    /** 실행 순간 BT 기기가 연결돼 있던 비율 0~100 (BT 기록 없으면 -1) */
    val btConnectedPct: Double,
    val btTopDevice: String?,
    /** 실행 30초 안에 같은 앱 알림이 선행한 비율 0~100 — 알림 주도성 */
    val notifLedPct: Double,
    /** 실행이 몰린 시간대 상위 3 */
    val topHours: List<Int>,
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

/**
 * 앱 페어 후보 — 두 앱의 관계를 한 행으로 본다.
 *
 * [Report.transitions] 는 방향별이라 페어 판단에 불편하다. 여기서는 양방향을
 * 합산하고 분할화면 동시 사용까지 붙인다. 왕복이 잦고 간격이 짧고 홈 경유가
 * 적을수록, 또 이미 분할화면으로 쓰고 있을수록 "한 세트로 쓰는 앱"이다.
 */
@Serializable
data class AppPairStat(
    val a: String,
    val b: String,
    /** 양방향 전환 합 */
    val roundTrips: Int,
    /** a→b 비율 0~100. 50 근처면 왕복, 극단이면 한 방향 흐름 */
    val abPct: Double,
    val medianGapSeconds: Int,
    /** 홈 화면을 거친 비율 0~100. 높을수록 전환 마찰이 크다 */
    val viaLauncherPct: Double,
    /** 분할화면 동시 사용 횟수 */
    val coUseCount: Int,
    val distinctDays: Int,
)

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

/**
 * 외출 지표.
 *
 * 낮 시간대에 이동통신으로 통신한 비율. Wi-Fi 밖에 있었다는 뜻이다.
 * SSID 없이도 계산되므로 앱 설치 전 구간까지 소급된다 —
 * 시스템이 통신량 이력을 몇 달치 들고 있기 때문이다.
 *
 * 주의: 기기가 그 네트워크에 붙어 있었다는 것이지 사람이 어디 있었다는 증거는 아니다.
 */
@Serializable
data class OutingStat(
    val date: String,
    val weekday: String,
    val isWeekend: Boolean,
    /** 낮 버킷 중 모바일이 우세했던 비율 (0~100) */
    val mobileShare: Double,
    val bucketsObserved: Int,
)

@Serializable
data class NetAppStat(
    val label: String,
    val totalMb: Double,
    /** 그중 모바일(이동통신) 비율 0~100 */
    val mobilePct: Double,
)

/** 그 시간대(2시간 버킷 시작 시각)에 모바일이 우세했던 날의 비율 */
@Serializable
data class HourOuting(
    val hour: Int,
    val mobileShare: Double,
    val days: Int,
)

@Serializable
data class WeekdayOuting(
    val weekday: String,
    val isWeekend: Boolean,
    val meanShare: Double,
    val days: Int,
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
