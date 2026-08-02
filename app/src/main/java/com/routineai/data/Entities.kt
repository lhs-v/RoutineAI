package com.routineai.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 시스템에서 읽어온 사용 이벤트 원본.
 *
 * (ts, type, pkg) 를 유니크 인덱스로 두어 중복 수집을 막는다.
 * 수집 창이 겹쳐도 안전하게 여러 번 돌릴 수 있다.
 */
@Entity(
    tableName = "usage_event",
    indices = [
        Index(value = ["ts", "type", "pkg"], unique = true),
        Index(value = ["ts"]),
    ]
)
data class UsageEventRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val type: Int,
    val pkg: String,
    val cls: String? = null,
)

/** 알림 도착 기록. NotificationListenerService 가 실시간으로 채운다. */
@Entity(tableName = "notif_event", indices = [Index(value = ["ts"])])
data class NotifEventRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val pkg: String,
    val channel: String?,
    /** 소리·진동·헤드업으로 사용자를 실제로 방해했는지 */
    val interruptive: Boolean,
    val ongoing: Boolean,
)

/**
 * 앱별 통신량. 시스템은 과거 SSID별 내역을 앱에 주지 않으므로
 * Wi-Fi / 모바일 두 갈래까지만 구분된다. 장소는 [NetworkChangeRow] 로 따로 기록한다.
 */
@Entity(tableName = "net_bucket", indices = [Index(value = ["bucketStart", "uid", "transport"], unique = true)])
data class NetBucketRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bucketStart: Long,
    val uid: Int,
    /** 0 = 모바일, 1 = Wi-Fi */
    val transport: Int,
    val rxBytes: Long,
    val txBytes: Long,
)

/**
 * 네트워크가 바뀐 순간을 앱이 직접 남긴 기록.
 * 설치 이후부터만 쌓이지만, 이게 유일한 장소 축이다.
 * ssid 는 해싱하지 않고 그대로 두되 UI 에서는 별칭(A/B)으로 보여준다.
 */
@Entity(tableName = "net_change", indices = [Index(value = ["ts"])])
data class NetworkChangeRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    /** "wifi" | "cellular" | "none" */
    val kind: String,
    val ssid: String?,
)

/**
 * 블루투스 연결/해제 순간. 리시버가 실시간으로 남긴다.
 *
 * 과거 이력은 시스템이 앱에 주지 않으므로 설치 시점부터만 쌓인다.
 * 기기 이름은 "무엇과 연결됐나"(버즈·워치·차량)가 행동 맥락이므로 그대로 두되,
 * MAC 주소는 저장하지 않는다 — 식별자일 뿐 맥락 정보가 없다.
 */
@Entity(tableName = "bt_event", indices = [Index(value = ["ts"])])
data class BtEventRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    /** "connect" | "disconnect" */
    val action: String,
    val name: String,
    /** BluetoothClass major device class. 이름이 없을 때의 분류 실마리 */
    val majorClass: Int,
)

/**
 * Health Connect 의 세션 기록 (운동·수면).
 *
 * 수면 세션은 화면 공백 기반 수면 추정과 교차 검증할 수 있는 유일한 독립 소스다.
 * (tsStart, kind) 유니크 — 재수집 시 중복을 막는다.
 */
@Entity(
    tableName = "health_session",
    indices = [Index(value = ["tsStart", "kind"], unique = true), Index(value = ["tsStart"])]
)
data class HealthSessionRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tsStart: Long,
    val tsEnd: Long,
    /** "exercise:<타입>" | "sleep" */
    val kind: String,
)

/**
 * LLM 이 만든 루틴 제안의 현재 상태.
 *
 * 기본키는 LLM 이 준 id 가 아니라 (트리거, 행동) 시그니처다 — 분석을 다시 돌려
 * 같은 제안이 또 나오면 증거만 갱신되고 상태(수락·거절)는 유지돼야 한다.
 *
 * state: candidate(감시 중) | accepted(수락) | snoozed(이번엔 아님, 쿨다운)
 *        | dismissed(보지 않기, 재제안 금지) | dormant(반복 무시로 휴면)
 */
@Entity(tableName = "proposal")
data class ProposalRow(
    @PrimaryKey val signature: String,
    val category: String,
    val oneLine: String,
    /** 왜 이 패턴이 이 목적으로 읽히는가 — 맥락 판정의 한 문장 서사 */
    val narrative: String,
    val triggerType: String,
    val triggerParam: String?,
    val actionType: String,
    /** JSON 배열 문자열 */
    val actionParams: String,
    val samsungCondition: String?,
    val samsungAction: String?,
    /** JSON 배열 문자열 */
    val evidenceJson: String,
    val confidence: String,
    val state: String,
    val createdAt: Long,
    val updatedAt: Long,
    val surfacedCount: Int = 0,
    val lastSurfacedAt: Long? = null,

    /**
     * 수락 뒤에도 매번 물어볼지(false) 말없이 실행할지(true).
     * 기본은 물어보기다 — 앱이 저절로 열리면 "왜 열렸지"를 겪게 되고
     * 그게 신뢰를 깎는다. 승격은 사용자가 카드에서 직접 하거나,
     * P3 심층 분석이 "12번 중 12번 누르셨다"는 근거로 제안한다.
     */
    // Kotlin 기본값은 SQL 기본값이 아니다. 자동 마이그레이션이 기존 행을 채우려면
    // defaultValue 를 명시해야 한다.
    @ColumnInfo(defaultValue = "0")
    val autoRun: Boolean = false,

    /**
     * 조건 정제 결과. 1차 LLM 은 통계만 보고 조건을 만들고, 2차(P3)가
     * 수락·거절 맥락을 읽어 좁힌다. 비어 있으면 아직 정제 전이다.
     * 예: hours="8-16", weekdays="1-5"
     */
    val conditionHours: String? = null,
    val conditionWeekdays: String? = null,

    /**
     * 심층 분석이 결정 이력을 읽고 남긴 한 문장 — 무엇을 근거로 무엇을
     * 바꿨는지. 사용자에게 그대로 보여준다. "내 반응이 뭔가를 바꿨다"를
     * 문장으로 증명하는 자리다.
     */
    val insight: String? = null,

    /**
     * 심층 분석의 자동 실행 승격 추천. 추천만 한다 — 실제 승격은 언제나
     * 사용자가 스위치로 한다. 앱이 저절로 켜면 "왜 열렸지"를 겪게 된다.
     */
    @ColumnInfo(defaultValue = "0")
    val suggestAutoRun: Boolean = false,

    /** 마지막 심층 분석 시각. null 이면 아직 정제 전 */
    val refinedAt: Long? = null,
)

/**
 * 제안 이력 — append only.
 *
 * 상태 컬럼만으로는 "세 번 무시하고 네 번째에 수락" 같은 궤적을 잃는다.
 * 수락·거절의 궤적이 곧 의도의 ground truth 라 심층 분석(P3)의 입력이 된다.
 * kind: generated | updated | surfaced | accepted | not_now | dismissed | revived
 */
@Entity(tableName = "proposal_event", indices = [Index(value = ["ts"])])
data class ProposalEventRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val proposalSignature: String,
    val kind: String,

    // ---- 결정 순간의 맥락 ----
    //
    // 해석하지 않고 원시 값 그대로 남긴다. "22시와 23시가 같은 의미인가",
    // "장 마감이라 거절했나" 같은 판단은 앱이 하면 안 된다 — 경계를 어디에
    // 긋든 자의적이고(실측: 장중 정의에 따라 45~70% 로 흔들림), '장 마감'
    // 이라는 개념은 데이터 어디에도 없다. 그건 세계지식을 가진 LLM 의 몫이라
    // 심층 분석(P3)이 이 기록을 읽고 조건을 좁힌다.
    @ColumnInfo(defaultValue = "-1")
    val hour: Int = -1,
    /** 1=월 … 7=일 */
    @ColumnInfo(defaultValue = "-1")
    val weekday: Int = -1,
    /** 결정 당시 화면에 있던 앱 */
    val foregroundPkg: String? = null,
    /** "wifi:Wi-Fi A" | "cellular" | null */
    val network: String? = null,
    val btDevice: String? = null,

    /**
     * 선택지 숏컷에서 무엇을 골랐는가(패키지명). 단일 액션이면 null.
     * "이어버즈 → Music 또는 YouTube" 같은 제안에서 선택이 갈리는 축
     * (시간대·요일)을 심층 분석이 읽는 재료다.
     */
    val choice: String? = null,
)

/** 마지막 수집 시각 등 내부 상태 */
@Entity(tableName = "kv")
data class KvRow(@PrimaryKey val k: String, val v: String)
