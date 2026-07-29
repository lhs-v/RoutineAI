package com.rountineai.collect

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.rountineai.data.Db
import com.rountineai.data.KvRow
import com.rountineai.data.UsageEventRow

/**
 * 시스템의 사용 이벤트를 읽어 로컬 DB에 누적한다.
 *
 * 시스템은 이벤트를 며칠치만 들고 있다가 지운다. 이 클래스가 주기적으로 돌면서
 * 자기 DB에 쌓기 때문에, 앱을 오래 쓸수록 분석 가능한 기간이 길어진다.
 * 이게 이 앱의 존재 이유다.
 */
class UsageCollector(private val ctx: Context) {

    private val dao = Db.get(ctx).dao()

    suspend fun collect(now: Long = System.currentTimeMillis()): Result {
        val last = dao.get(KEY_LAST_SYNC)?.toLongOrNull()

        // 첫 실행이면 시스템이 들고 있을 법한 최대 범위를 긁는다.
        // 실제로 얼마나 남아 있는지는 기기마다 달라서, 넉넉히 요청하고 받은 만큼 쓴다.
        // 이후에는 겹치는 구간을 두고 읽는다 — 유니크 인덱스가 중복을 걸러준다.
        val from = last?.minus(OVERLAP_MS) ?: (now - FIRST_RUN_LOOKBACK_MS)

        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val stream = usm.queryEvents(from, now)

        val batch = ArrayList<UsageEventRow>(4096)
        var scanned = 0
        val e = UsageEvents.Event()
        while (stream.hasNextEvent()) {
            stream.getNextEvent(e)
            scanned++
            batch.add(
                UsageEventRow(
                    ts = e.timeStamp,
                    type = e.eventType,
                    pkg = e.packageName ?: continue,
                    cls = e.className,
                )
            )
            if (batch.size >= 2000) {
                dao.insertEvents(batch); batch.clear()
            }
        }
        if (batch.isNotEmpty()) dao.insertEvents(batch)

        dao.put(KvRow(KEY_LAST_SYNC, now.toString()))
        if (dao.get(KEY_FIRST_SYNC) == null) dao.put(KvRow(KEY_FIRST_SYNC, now.toString()))

        return Result(
            scanned = scanned,
            windowFrom = from,
            windowTo = now,
            totalStored = dao.eventCount(),
            oldestStored = dao.firstEventTs(),
        )
    }

    data class Result(
        val scanned: Int,
        val windowFrom: Long,
        val windowTo: Long,
        val totalStored: Int,
        val oldestStored: Long?,
    )

    companion object {
        const val KEY_LAST_SYNC = "last_sync"
        const val KEY_FIRST_SYNC = "first_sync"

        /** 수집 창을 이만큼 겹쳐서 읽는다. 경계에서 이벤트를 흘리지 않기 위해. */
        private const val OVERLAP_MS = 6L * 60 * 60 * 1000

        /** 첫 실행 때 얼마나 과거까지 요청할지. 시스템이 안 갖고 있으면 그냥 적게 온다. */
        private const val FIRST_RUN_LOOKBACK_MS = 30L * 24 * 60 * 60 * 1000
    }
}
