package com.routineai.collect

import android.app.Notification
import android.app.NotificationManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.routineai.data.Db
import com.routineai.data.NotifEventRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 알림 통계를 위한 리스너.
 *
 * 본문(제목·내용)은 통계에 필요하지 않으므로 읽지도, 저장하지도 않는다.
 * 기록하는 것은 시각·발신 앱·채널·중요도뿐이다.
 *
 * 방해성 판정은 [Ranking.getImportance] 로 한다.
 * Notification.sound / vibrate 로 판정하면 안 된다 — 요즘 안드로이드는 소리·진동이
 * 채널 설정에 있어서 그 필드가 거의 항상 null 이고, 그러면 방해성 알림이 0건으로 나온다.
 */
class NotifListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 같은 알림(key)이 마지막으로 기록된 시각.
     *
     * onNotificationPosted 는 새 알림뿐 아니라 기존 알림의 갱신에도 불린다.
     * 미디어 재생 위치나 진행률이 바뀔 때마다 다시 게시되므로, 그대로 넣으면
     * 알림 하나가 하루 수천 행이 된다(실측: 한 앱이 26분에 1,559행).
     */
    private val recentByKey = HashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        val n = sbn.notification ?: return

        // 미디어 재생 컨트롤은 알림이 아니라 조작 패널이다. 통째로 제외한다.
        if (n.category == Notification.CATEGORY_TRANSPORT) return

        // 같은 key 의 잦은 재게시(진행률 갱신 등)는 도착 1건으로 접는다.
        // 창을 짧게 두는 이유: 메신저는 새 메시지마다 같은 key 를 갱신하는데,
        // 그건 실제 도착이므로 몇 초 이상 벌어지면 각각 세어야 한다.
        val now = sbn.postTime
        synchronized(recentByKey) {
            val last = recentByKey[sbn.key]
            recentByKey[sbn.key] = now
            if (last != null && now - last < REPOST_WINDOW_MS) return
            if (recentByKey.size > 512) {
                recentByKey.entries.removeIf { now - it.value > 60_000 }
            }
        }

        val ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0

        val importance = runCatching {
            val r = Ranking()
            if (rankingMap?.getRanking(sbn.key, r) == true) r.importance
            else NotificationManager.IMPORTANCE_DEFAULT
        }.getOrDefault(NotificationManager.IMPORTANCE_DEFAULT)

        // 소리나 헤드업으로 실제로 주의를 끌었는지
        val interruptive = !ongoing && importance >= NotificationManager.IMPORTANCE_DEFAULT

        val row = NotifEventRow(
            ts = sbn.postTime,
            pkg = sbn.packageName,
            channel = n.channelId,
            interruptive = interruptive,
            ongoing = ongoing,
        )
        scope.launch { Db.get(applicationContext).dao().insertNotif(row) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        onNotificationPosted(sbn, null)
    }

    /**
     * 어떻게 사라졌는가 — 도착(양)이 아니라 여기(행동)에 응답 가치가 있다.
     * 사용자 행동(클릭·스와이프·모두 지우기)과 앱 자체 취소만 남기고,
     * 그룹 요약 정리·타임아웃 같은 시스템 사유는 버린다.
     * 방치 시간은 sbn.postTime 이 그대로 있어 그 자리에서 계산한다 —
     * key 를 저장해 짝지을 필요가 없다.
     */
    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap?,
        reason: Int,
    ) {
        val n = sbn.notification ?: return
        if (n.category == Notification.CATEGORY_TRANSPORT) return
        if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        if (reason !in RECORDED_REASONS) return

        val now = System.currentTimeMillis()
        val row = NotifEventRow(
            ts = now,
            pkg = sbn.packageName,
            channel = n.channelId,
            interruptive = false,
            ongoing = false,
            kind = "removed",
            reason = reason,
            dwellMs = (now - sbn.postTime).coerceAtLeast(0),
        )
        scope.launch {
            Db.get(applicationContext).dao().insertNotif(row)
            // 스와이프·모두 지우기는 트리거이기도 하다 — 알림을 지우는 행동이
            // 일어난 그 순간이 "이 알림, 끌까요?"의 증명 순간이다. 모두
            // 지우기는 알림마다 이 콜백이 따로 오지만, 같은 앱은 신호 관문
            // (5초)이 접고 발화 자체는 기존 쿨다운이 관리한다.
            if (reason == REASON_CANCEL || reason == REASON_CANCEL_ALL) {
                com.routineai.watch.PatternWatcher
                    .onNotifDismissed(applicationContext, sbn.packageName)
            }
        }
    }

    companion object {
        private const val REPOST_WINDOW_MS = 5_000L

        /** 클릭 · 스와이프 · 모두 지우기 · 앱 자체 취소(단건/전체) */
        private val RECORDED_REASONS = setOf(
            REASON_CLICK, REASON_CANCEL, REASON_CANCEL_ALL,
            REASON_APP_CANCEL, REASON_APP_CANCEL_ALL,
        )
    }
}
