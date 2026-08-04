package com.routineai.collect

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import com.routineai.data.Db
import com.routineai.data.NetBucketRow
import com.routineai.data.NetworkChangeRow

/**
 * 통신량과 접속 네트워크를 기록한다.
 *
 * 한계를 분명히 해둔다:
 * 시스템은 앱에게 "과거에 어느 Wi-Fi에 붙어 있었는지"를 주지 않는다.
 * NetworkStatsManager 로 얻는 건 Wi-Fi / 모바일 두 갈래까지다.
 * 그래서 장소 축은 [recordCurrentNetwork] 가 설치 시점부터 직접 쌓는다.
 */
class NetworkCollector(private val ctx: Context) {

    private val dao = Db.get(ctx).dao()

    /**
     * 앱별 통신량을 Wi-Fi/모바일로 나눠, **시간 버킷을 유지한 채** 저장한다.
     *
     * querySummary() 를 쓰면 안 된다 — 기간 전체를 하나로 뭉개서 시계열이 사라진다.
     * queryDetails() 는 시스템이 보관 중인 버킷 경계를 그대로 돌려주므로
     * 설치 직후에도 과거 수십 일치 "Wi-Fi 밖에 있었는가" 를 재구성할 수 있다.
     *
     * SSID 는 어느 쪽으로도 얻을 수 없다(시스템 전용). 장소 별칭은
     * [recordCurrentNetwork] 가 설치 시점부터 따로 쌓는다.
     */
    suspend fun collectUsage(from: Long, to: Long) {
        val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager

        // (버킷 시작, uid, 전송수단) -> [rx, tx]
        //
        // 합산이 필요한 이유: queryDetails() 는 한 버킷을 uid 로만 나누지 않고
        // foreground/background 상태와 tag 로도 쪼개 돌려준다. 그대로 행으로 만들면
        // 같은 키가 여러 번 나오고, 유니크 인덱스가 뒤엣것을 버려 통신량이 실제보다
        // 작아진다. 외출 지표는 모바일과 Wi-Fi 의 크기를 견주는 것이라
        // 한쪽만 잘리면 판정이 뒤집힌다.
        val merged = HashMap<Triple<Long, Int, Int>, LongArray>()

        listOf(
            ConnectivityManager.TYPE_WIFI to TRANSPORT_WIFI,
            ConnectivityManager.TYPE_MOBILE to TRANSPORT_MOBILE,
        ).forEach { (type, transport) ->
            try {
                val stats = nsm.queryDetails(type, null, from, to)
                val bucket = NetworkStats.Bucket()
                var scanned = 0
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    if (bucket.rxBytes == 0L && bucket.txBytes == 0L) continue
                    if (++scanned > MAX_BUCKETS_PER_RUN) break
                    val acc = merged.getOrPut(
                        Triple(bucket.startTimeStamp, bucket.uid, transport)
                    ) { LongArray(2) }
                    acc[0] += bucket.rxBytes
                    acc[1] += bucket.txBytes
                }
                stats.close()
            } catch (t: Throwable) {
                // 모바일 쪽은 기기·통신사에 따라 SecurityException 이 날 수 있다.
                // 실패해도 Wi-Fi 통계와 나머지 분석은 그대로 굴러가야 한다.
                Log.w(TAG, "netstats 조회 실패 type=$type", t)
            }
        }
        if (merged.isEmpty()) return

        // 이미 저장된 값과 큰 쪽을 남긴다.
        //
        // 진행 중인 버킷은 다음 수집 때 값이 커져 있으므로 덮어써야 최신이 된다.
        // 반대로 조회 구간이 버킷을 반만 걸치면 시스템이 잘린 값을 줄 수 있는데,
        // 그때 그냥 덮어쓰면 이미 온전히 받아둔 값이 줄어든다. 그래서 최댓값을 쓴다.
        val minStart = merged.keys.minOf { it.first }
        val maxStart = merged.keys.maxOf { it.first }
        val stored = dao.net(minStart, maxStart + 1)
            .associateBy { Triple(it.bucketStart, it.uid, it.transport) }

        dao.upsertNet(
            merged.map { (k, v) ->
                val old = stored[k]
                NetBucketRow(
                    bucketStart = k.first,
                    uid = k.second,
                    transport = k.third,
                    rxBytes = maxOf(v[0], old?.rxBytes ?: 0L),
                    txBytes = maxOf(v[1], old?.txBytes ?: 0L),
                )
            }
        )
    }

    /**
     * 지금 붙어 있는 네트워크를 기록한다. 이전 기록과 같으면 쓰지 않는다.
     * SSID 를 읽으려면 위치 권한이 필요하고, 없으면 "wifi" 라고만 남는다.
     */
    suspend fun recordCurrentNetwork() {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active: Network? = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }

        val kind = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
        val ssid = if (kind == "wifi") currentSsid() else null

        val prev = dao.lastNetChange()
        if (prev != null && prev.kind == kind && prev.ssid == ssid) return
        // wifi 인 채 SSID 만 null 로 바뀌는 것은 다른 네트워크가 아니라 재협상
        // (밴드 전환 등) 순간의 판독 실패다. 기록하면 "LHS→?→LHS" 왕복이
        // 분 단위로 쌓인다(실측 8/2: 29행 중 대부분). 직전이 wifi 일 때만
        // 거른다 — cellular→wifi(null) 는 이름 모르는 진짜 연결이라 남긴다.
        if (kind == "wifi" && ssid == null && prev?.kind == "wifi") return

        dao.insertNetChange(
            NetworkChangeRow(ts = System.currentTimeMillis(), kind = kind, ssid = ssid)
        )
    }

    /** 감시 서비스도 같은 방식으로 SSID 를 읽어야 별칭이 일치한다. */
    fun currentSsid(): String? = try {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wm.connectionInfo.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    } catch (t: Throwable) {
        null
    }

    /**
     * 첫 실행 때 과거 구간을 한 번 긁는다.
     * 시스템이 얼마나 들고 있는지는 기기마다 다르므로 넉넉히 요청하고 오는 만큼 쓴다.
     */
    suspend fun backfill(now: Long = System.currentTimeMillis()) {
        collectUsage(now - BACKFILL_MS, now)
    }

    companion object {
        const val TRANSPORT_MOBILE = 0
        const val TRANSPORT_WIFI = 1
        private const val TAG = "NetworkCollector"

        /** 첫 실행 때 요청할 과거 범위 */
        private const val BACKFILL_MS = 180L * 24 * 60 * 60 * 1000

        /** 한 전송수단당 훑을 최대 버킷 수 (오래된 기기에서 폭주 방지) */
        private const val MAX_BUCKETS_PER_RUN = 200_000
    }
}
