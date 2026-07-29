package com.rountineai.collect

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import com.rountineai.data.Db
import com.rountineai.data.NetBucketRow
import com.rountineai.data.NetworkChangeRow

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

    /** 앱별 통신량을 Wi-Fi/모바일로 나눠 저장 */
    suspend fun collectUsage(from: Long, to: Long) {
        val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        listOf(
            ConnectivityManager.TYPE_WIFI to TRANSPORT_WIFI,
            ConnectivityManager.TYPE_MOBILE to TRANSPORT_MOBILE,
        ).forEach { (type, transport) ->
            try {
                val stats = nsm.querySummary(type, null, from, to)
                val bucket = NetworkStats.Bucket()
                val rows = ArrayList<NetBucketRow>()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    if (bucket.rxBytes == 0L && bucket.txBytes == 0L) continue
                    rows.add(
                        NetBucketRow(
                            bucketStart = bucket.startTimeStamp,
                            uid = bucket.uid,
                            transport = transport,
                            rxBytes = bucket.rxBytes,
                            txBytes = bucket.txBytes,
                        )
                    )
                }
                stats.close()
                if (rows.isNotEmpty()) dao.insertNet(rows)
            } catch (t: Throwable) {
                // 모바일 쪽은 기기·통신사에 따라 SecurityException 이 날 수 있다.
                // 실패해도 Wi-Fi 통계와 나머지 분석은 그대로 굴러가야 한다.
                Log.w(TAG, "netstats 조회 실패 type=$type", t)
            }
        }
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

        dao.insertNetChange(
            NetworkChangeRow(ts = System.currentTimeMillis(), kind = kind, ssid = ssid)
        )
    }

    private fun currentSsid(): String? = try {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wm.connectionInfo.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    } catch (t: Throwable) {
        null
    }

    companion object {
        const val TRANSPORT_MOBILE = 0
        const val TRANSPORT_WIFI = 1
        private const val TAG = "NetworkCollector"
    }
}
