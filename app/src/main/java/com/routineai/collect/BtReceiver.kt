package com.routineai.collect

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.routineai.data.BtEventRow
import com.routineai.data.Db
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 블루투스 연결/해제를 기록한다.
 *
 * ACL 브로드캐스트는 암시적 브로드캐스트 제한의 예외 목록에 있어
 * 매니페스트 선언 리시버로 앱이 안 떠 있어도 받는다.
 *
 * "버즈 연결 → 음악 앱" 같은 이벤트 연쇄와, "이 앱은 차량 연결 중에 쓴다" 같은
 * 상태 맥락의 재료다. 이름·기기 종류만 남기고 MAC 은 저장하지 않는다.
 */
class BtReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val action = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> "connect"
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> "disconnect"
            else -> return
        }
        val device: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            ?: return

        // 이름 조회는 BLUETOOTH_CONNECT 런타임 권한이 필요하다. 없으면
        // SecurityException 이 나므로, 연결 사실 자체는 이름 없이라도 남긴다.
        val name = runCatching { device.name }.getOrNull() ?: "이름 모름"
        val major = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull() ?: 0

        val row = BtEventRow(
            ts = System.currentTimeMillis(),
            action = action,
            name = name,
            majorClass = major,
        )
        // 리시버는 반환 즉시 프로세스가 죽을 수 있다. goAsync 로 붙잡고 쓴다.
        val pending = goAsync()
        scope.launch {
            try {
                Db.get(ctx).dao().insertBt(row)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
