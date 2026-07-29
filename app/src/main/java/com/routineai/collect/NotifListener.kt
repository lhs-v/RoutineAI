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

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        val n = sbn.notification ?: return
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
}
