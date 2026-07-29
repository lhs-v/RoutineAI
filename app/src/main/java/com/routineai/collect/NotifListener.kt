package com.routineai.collect

import android.app.Notification
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
 * 사용 이벤트 스트림에도 알림 관련 항목이 섞여 오지만 그건 시스템 전용으로 표시된
 * 타입이라 기기마다 넘어오는지가 다르다. 여기서 직접 받는 편이 확실하고,
 * 채널·지속성 같은 정보까지 얻을 수 있다.
 *
 * 본문(제목·내용)은 통계에 필요하지 않으므로 읽지도, 저장하지도 않는다.
 */
class NotifListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0

        // 소리·진동·헤드업 중 하나라도 있으면 사용자를 실제로 방해한 알림으로 본다.
        val interruptive = !ongoing && (
            n.sound != null || n.vibrate != null ||
                (n.flags and Notification.FLAG_INSISTENT) != 0 ||
                n.fullScreenIntent != null
            )

        val row = NotifEventRow(
            ts = sbn.postTime,
            pkg = sbn.packageName,
            channel = n.channelId,
            interruptive = interruptive,
            ongoing = ongoing,
        )
        scope.launch { Db.get(applicationContext).dao().insertNotif(row) }
    }
}
