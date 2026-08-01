package com.routineai.data

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

/** 마지막 수집 시각 등 내부 상태 */
@Entity(tableName = "kv")
data class KvRow(@PrimaryKey val k: String, val v: String)
