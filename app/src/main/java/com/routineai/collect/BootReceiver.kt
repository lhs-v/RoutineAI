package com.routineai.collect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.routineai.watch.WatchService

/**
 * 감시를 되살리는 유일한 자동 경로.
 *
 * [WatchService] 는 START_STICKY 지만 절전이 거둬 가면 스스로 돌아오지
 * 않고, 시작 지점이 `MainActivity.onStart` 하나뿐이라 **앱을 열기 전까지
 * 감지가 죽어 있다**(실측: 서비스 목록에 NotifListener 만 남고 하루치
 * 트리거가 통째로 유실). 재부팅과 앱 교체 직후에 다시 세운다.
 *
 * 포그라운드 서비스를 브로드캐스트에서 시작하는 것은 Android 12+ 에서
 * 원칙적으로 금지지만 BOOT_COMPLETED 는 명시된 예외이고, 이 앱의 타입
 * (specialUse)은 Android 15 의 부팅 시작 제한 목록에도 없다. 그래도
 * 제조사가 거부할 수 있으므로 실패는 로그로만 남기고 조용히 넘어간다 —
 * 그때는 사용자가 앱을 한 번 열면 살아난다.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                Log.i(TAG, "감시 재시작 시도: ${intent.action}")
                // 사용 정보 접근이 없으면 start 가 조용히 아무것도 안 한다.
                WatchService.start(ctx.applicationContext)
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
