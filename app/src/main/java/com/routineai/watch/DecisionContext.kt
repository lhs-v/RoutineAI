package com.routineai.watch

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.routineai.data.Db
import com.routineai.data.ProposalEventRow
import java.time.Instant
import java.time.ZoneId

/**
 * 결정 순간의 맥락을 있는 그대로 수집한다.
 *
 * 여기서 의미를 붙이지 않는 것이 핵심이다. "22시라서 장 마감이라 거절했다"
 * 같은 판단은 세계지식이 필요하고, 그건 심층 분석(P3)의 LLM 몫이다.
 * 앱은 나중에 그 판단을 할 수 있도록 원시 값을 남기기만 한다 —
 * 지나간 결정의 맥락은 복원할 수 없으므로 기록은 지금부터 해야 한다.
 */
object DecisionContext {

    private val zone: ZoneId = ZoneId.systemDefault()

    fun capture(
        ctx: Context,
        signature: String,
        kind: String,
        foregroundPkg: String? = null,
        ts: Long = System.currentTimeMillis(),
    ): ProposalEventRow {
        val t = Instant.ofEpochMilli(ts).atZone(zone)
        return ProposalEventRow(
            ts = ts,
            proposalSignature = signature,
            kind = kind,
            hour = t.hour,
            weekday = t.dayOfWeek.value,
            foregroundPkg = foregroundPkg,
            network = network(ctx),
            btDevice = BtState.connectedDevice(),
        )
    }

    suspend fun log(
        ctx: Context,
        signature: String,
        kind: String,
        foregroundPkg: String? = null,
    ) {
        Db.get(ctx).dao().logProposalEvent(capture(ctx, signature, kind, foregroundPkg))
    }

    /**
     * "이 맥락을 이미 봤는가" 를 판정하는 거친 버킷.
     *
     * 이건 의미 판단이 아니라 중복 제거다 — 같은 상황에서 반복해 귀찮게 하지
     * 않는 것이 목적이고, 버킷 경계가 진짜 의미의 경계인지는 따지지 않는다.
     * (그 판단은 P3 가 결정 이력 전체를 읽고 한다.)
     */
    fun bucket(row: ProposalEventRow): String {
        val slot = when (row.hour) {
            in 5..8 -> "새벽아침"
            in 9..11 -> "오전"
            in 12..13 -> "점심"
            in 14..17 -> "오후"
            in 18..21 -> "저녁"
            else -> "심야"
        }
        val day = if (row.weekday >= 6) "주말" else "평일"
        val net = row.network?.substringBefore(':') ?: "-"
        return "$day/$slot/$net"
    }

    private fun network(ctx: Context): String? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return null
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> null
        }
    }
}

/** BT 연결 상태를 리시버가 갱신해 두는 자리. 시스템에 물을 공개 경로가 없다. */
object BtState {
    @Volatile private var connected: String? = null

    fun update(name: String, isConnected: Boolean) {
        connected = if (isConnected) name else null
    }

    fun connectedDevice(): String? = connected
}
