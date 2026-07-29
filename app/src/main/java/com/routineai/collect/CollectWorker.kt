package com.routineai.collect

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 주기 수집. 기본 6시간.
 *
 * 간격을 짧게 잡을 필요는 없다. 시스템이 이벤트를 며칠은 들고 있으므로
 * 하루에 몇 번만 돌아도 유실되지 않는다. 오히려 배터리 쪽이 손해다.
 */
class CollectWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        if (!Permissions.hasUsageAccess(applicationContext)) {
            Log.i(TAG, "사용 정보 접근이 꺼져 있어 수집을 건너뜁니다")
            return Result.success()
        }
        return try {
            val now = System.currentTimeMillis()
            val res = UsageCollector(applicationContext).collect(now)

            val net = NetworkCollector(applicationContext)
            net.recordCurrentNetwork()
            net.collectUsage(res.windowFrom, now)

            Log.i(TAG, "수집 완료: 이번 ${res.scanned}건, 누적 ${res.totalStored}건")
            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "수집 실패", t)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "CollectWorker"
        private const val NAME = "routine-collect"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<CollectWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP, req
            )
        }
    }
}
