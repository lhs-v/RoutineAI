package com.routineai.collect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import android.util.Log
import com.routineai.data.Db
import com.routineai.data.HealthSessionRow
import java.time.Instant

/**
 * Health Connect 에서 운동·수면 세션을 읽어 로컬 DB 에 누적한다.
 *
 * 워치가 삼성 헬스에 쓴 기록이 Health Connect 로 동기화되므로,
 * 공개 API 만으로 워치 데이터까지 닿는 경로다.
 *
 * 왜 세션만 읽는가: 이 앱의 질문은 "무엇을 얼마나 했나"(걸음 수)가 아니라
 * "행동이 어떤 상태에서 일어나는가"다. 세션은 시작·끝이 있는 상태라
 * 이벤트 연쇄와 상태 맥락에 바로 들어간다. 수면 세션은 화면 공백 기반
 * 수면 추정과 교차 검증할 수 있는 유일한 독립 소스이기도 하다.
 */
class HealthCollector(private val ctx: Context) {

    private val dao = Db.get(ctx).dao()

    suspend fun collect(from: Long, to: Long) {
        if (!isAvailable(ctx)) return
        val client = HealthConnectClient.getOrCreate(ctx)
        val granted = runCatching {
            client.permissionController.getGrantedPermissions()
        }.getOrDefault(emptySet())

        val rows = ArrayList<HealthSessionRow>()
        val filter = TimeRangeFilter.between(
            Instant.ofEpochMilli(from), Instant.ofEpochMilli(to)
        )

        if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) in granted) {
            runCatching {
                var token: String? = null
                do {
                    val resp = client.readRecords(
                        ReadRecordsRequest(
                            ExerciseSessionRecord::class,
                            timeRangeFilter = filter,
                            pageToken = token,
                        )
                    )
                    resp.records.mapTo(rows) {
                        HealthSessionRow(
                            tsStart = it.startTime.toEpochMilli(),
                            tsEnd = it.endTime.toEpochMilli(),
                            kind = "exercise:" + exerciseName(it.exerciseType),
                        )
                    }
                    token = resp.pageToken
                } while (token != null)
            }.onFailure { Log.w(TAG, "운동 세션 읽기 실패", it) }
        }

        if (HealthPermission.getReadPermission(SleepSessionRecord::class) in granted) {
            runCatching {
                var token: String? = null
                do {
                    val resp = client.readRecords(
                        ReadRecordsRequest(
                            SleepSessionRecord::class,
                            timeRangeFilter = filter,
                            pageToken = token,
                        )
                    )
                    resp.records.mapTo(rows) {
                        HealthSessionRow(
                            tsStart = it.startTime.toEpochMilli(),
                            tsEnd = it.endTime.toEpochMilli(),
                            kind = "sleep",
                        )
                    }
                    token = resp.pageToken
                } while (token != null)
            }.onFailure { Log.w(TAG, "수면 세션 읽기 실패", it) }
        }

        if (rows.isNotEmpty()) dao.insertHealth(rows)
    }

    private fun exerciseName(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "걷기"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "달리기"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "자전거"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "근력"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "등산"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "수영"
        else -> "운동($type)"
    }

    companion object {
        private const val TAG = "HealthCollector"

        fun isAvailable(ctx: Context): Boolean =
            HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

        suspend fun grantedAll(ctx: Context): Boolean = isAvailable(ctx) && runCatching {
            HealthConnectClient.getOrCreate(ctx)
                .permissionController.getGrantedPermissions()
                .containsAll(PERMISSIONS)
        }.getOrDefault(false)

        /** 설정 탭의 권한 요청 계약에 쓸 권한 셋 */
        val PERMISSIONS = setOf(
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
        )
    }
}
