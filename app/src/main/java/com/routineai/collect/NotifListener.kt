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

    companion object {
        private const val REPOST_WINDOW_MS = 5_000L
    }
}
