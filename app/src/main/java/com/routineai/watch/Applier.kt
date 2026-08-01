package com.routineai.watch

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.routineai.data.ProposalRow
import kotlinx.serialization.json.Json

/**
 * 제안을 실제로 적용한다.
 *
 * 중요한 사실: 삼성 루틴 앱에 루틴을 등록하는 경로는 없다 — 공유 파일(.rtn)의
 * 본문이 암호화되어 있고 무결성 해시에 비밀 키가 섞여 있어 우리가 만들 수 없다.
 * 그래서 **우리 앱이 자동화 엔진 역할을 한다**. 공개 API 로 되는 것은 진짜로
 * 실행하고, 안 되는 것만 사용자를 설정 화면으로 안내한다.
 *
 * | 종류 | 실제 동작 | 필요 권한 |
 * |---|---|---|
 * | app_pair | 분할화면으로 두 앱 동시 실행 | 없음 |
 * | launch_app | 그 앱 실행 | 없음(백그라운드 실행은 오버레이 권한) |
 * | mode_rotation | accelerometer_rotation 변경, 앱 이탈 시 원복 | WRITE_SETTINGS |
 * | mode_dnd | 방해금지 켜기, 앱 이탈 시 해제 | 알림 정책 접근 |
 * | notif_channel_off | 그 앱의 알림 설정 화면으로 이동 | 없음(사용자가 마무리) |
 * | mode_eye_comfort | 삼성 내부 설정이라 불가 — 안내만 | — |
 */
object Applier {

    private const val TAG = "Applier"
    private val json = Json { ignoreUnknownKeys = true }

    /** 적용 결과. [real] 이 false 면 사용자가 마무리해야 한다는 뜻이다. */
    data class Result(val ok: Boolean, val message: String, val real: Boolean)

    fun params(p: ProposalRow): List<String> =
        runCatching { json.decodeFromString<List<String>>(p.actionParams) }
            .getOrDefault(emptyList())

    fun apply(ctx: Context, p: ProposalRow): Result = when (p.actionType) {
        "app_pair" -> appPair(ctx, params(p))
        "launch_app" -> launch(ctx, params(p).firstOrNull())
        "mode_rotation" -> rotation(ctx, on = true)
        "mode_dnd" -> dnd(ctx, on = true)
        "notif_channel_off" -> notifSettings(ctx, params(p).firstOrNull())
        "mode_eye_comfort" -> Result(
            false,
            "편안하게 보기는 시스템 내부 설정이라 앱이 직접 바꿀 수 없습니다. " +
                "설정 → 디스플레이에서 켜주세요.",
            real = false,
        )
        else -> Result(false, "지원하지 않는 동작입니다: ${p.actionType}", real = false)
    }

    // ------------------------------------------------------------------

    /**
     * 분할화면. 첫 앱을 새 태스크로 띄우고 두 번째를 인접 배치로 붙인다.
     * 폴더블/태블릿에서는 대개 그대로 두 화면이 되고, 안 되면 순차 실행으로 보인다.
     */
    private fun appPair(ctx: Context, pkgs: List<String>): Result {
        if (pkgs.size < 2) return Result(false, "앱 두 개가 필요합니다", real = false)
        val first = intentFor(ctx, pkgs[0]) ?: return Result(false, "${pkgs[0]} 를 찾을 수 없습니다", false)
        val second = intentFor(ctx, pkgs[1]) ?: return Result(false, "${pkgs[1]} 를 찾을 수 없습니다", false)
        return runCatching {
            first.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
            )
            ctx.startActivity(first)
            second.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
            )
            ctx.startActivity(second)
            Result(true, "두 앱을 함께 열었습니다", real = true)
        }.getOrElse { Result(false, "분할화면 실행 실패: ${it.message}", real = false) }
    }

    private fun launch(ctx: Context, pkg: String?): Result {
        val intent = pkg?.let { intentFor(ctx, it) }
            ?: return Result(false, "실행할 앱을 찾을 수 없습니다", real = false)
        return runCatching {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Result(true, "앱을 열었습니다", real = true)
        }.getOrElse { Result(false, "실행 실패: ${it.message}", real = false) }
    }

    /** 자동 회전. 표준 AOSP 세팅 키라 WRITE_SETTINGS 만 있으면 바꿀 수 있다. */
    fun rotation(ctx: Context, on: Boolean): Result {
        if (!Settings.System.canWrite(ctx)) {
            return Result(false, "설정 변경 권한이 필요합니다", real = false)
        }
        return runCatching {
            Settings.System.putInt(
                ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0
            )
            Result(true, if (on) "자동 회전을 켰습니다" else "자동 회전을 되돌렸습니다", real = true)
        }.getOrElse { Result(false, "자동 회전 변경 실패: ${it.message}", real = false) }
    }

    fun dnd(ctx: Context, on: Boolean): Result {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            return Result(false, "방해금지 제어 권한이 필요합니다", real = false)
        }
        return runCatching {
            nm.setInterruptionFilter(
                if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else NotificationManager.INTERRUPTION_FILTER_ALL
            )
            Result(true, if (on) "방해금지를 켰습니다" else "방해금지를 해제했습니다", real = true)
        }.getOrElse { Result(false, "방해금지 변경 실패: ${it.message}", real = false) }
    }

    /**
     * 알림 채널을 코드로 끄는 공개 API 는 없다(다른 앱의 채널).
     * 해당 앱의 알림 설정 화면까지 데려다주는 것이 한계다.
     */
    private fun notifSettings(ctx: Context, pkg: String?): Result {
        if (pkg == null) return Result(false, "대상 앱을 알 수 없습니다", real = false)
        return runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Result(true, "알림 설정을 열었습니다 — 여기서 끄면 됩니다", real = false)
        }.getOrElse { Result(false, "설정 화면 열기 실패: ${it.message}", real = false) }
    }

    private fun intentFor(ctx: Context, pkg: String): Intent? =
        ctx.packageManager.getLaunchIntentForPackage(pkg)

    /** 오버레이 권한 요청 화면 */
    fun overlaySettings(ctx: Context) {
        ctx.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${ctx.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun writeSettings(ctx: Context) {
        ctx.startActivity(
            Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${ctx.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun dndAccessSettings(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun accessibilitySettings(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun hasOverlay(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun hasAccessibility(ctx: Context): Boolean = runCatching {
        val expected = ComponentName(ctx, PatternAccessibilityService::class.java).flattenToString()
        Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.split(':')?.any { it.equals(expected, ignoreCase = true) } == true
    }.getOrDefault(false)

    fun logDeviceState(ctx: Context) {
        Log.i(TAG, "overlay=${hasOverlay(ctx)} a11y=${hasAccessibility(ctx)} " +
            "write=${Settings.System.canWrite(ctx)}")
    }
}
