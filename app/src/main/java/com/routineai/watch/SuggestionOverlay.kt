package com.routineai.watch

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.routineai.data.Db
import com.routineai.data.ProposalRow
import com.routineai.interpret.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 제안의 등장 연출.
 *
 * 단순 팝업은 다른 앱 광고나 스팸과 구분되지 않는다. 그래서 3단으로 나눈다:
 *
 *  1) 화면 테두리가 한 번 숨을 쉰다 (0.6초, 아무것도 가리지 않음)
 *     — "무언가 알아챘다"를 먼저 느끼게 한다. 애플이 노치에서 하는 것과 같은
 *       원리인데 안드로이드엔 노치 API 가 없으니 화면 가장자리 전체를 쓴다.
 *  2) 유리 카드가 아래에서 떠오른다 (뒤 화면이 실제로 비침)
 *     — 정보를 가리지 않으려고 blurBehind 를 쓴다. Android 12+ 에서만 되고,
 *       안 되면 반투명으로 떨어진다.
 *  3) 이미 수락한 루틴은 알약 하나로만 — 매번 큰 카드면 그게 새 소음이 된다.
 */
object SuggestionOverlay {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private var card: View? = null
    private var glow: View? = null

    private const val AUTO_HIDE_MS = 12_000L
    private const val GLOW_MS = 600L

    fun show(ctx: Context, p: ProposalRow, shortcut: Boolean, anchorPkg: String?) {
        if (!Applier.hasOverlay(ctx)) return
        val app = ctx.applicationContext
        main.post {
            dismiss(app)
            if (shortcut) {
                // 이미 동의한 루틴 — 발광 없이 조용히.
                showCard(app, p, shortcut = true, anchorPkg = anchorPkg)
            } else {
                showGlow(app)
                main.postDelayed({ showCard(app, p, false, anchorPkg) }, GLOW_MS)
            }
        }
    }

    // ------------------------------------------------------------------

    /** 1단계: 테두리 발광. 터치를 통과시켜 아래 앱을 계속 쓸 수 있다. */
    private fun showGlow(ctx: Context) {
        val accent = Settings(ctx).accentColor()
        val view = View(ctx).apply {
            background = GradientDrawable().apply {
                setStroke(dp(ctx, 3), accent)
                cornerRadius = dp(ctx, 28).toFloat()
                setColor(Color.TRANSPARENT)
            }
            alpha = 0f
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        )
        runCatching { wm(ctx).addView(view, lp) }.onSuccess {
            glow = view
            ValueAnimator.ofFloat(0f, 1f, 0.35f).apply {
                duration = GLOW_MS
                addUpdateListener { view.alpha = it.animatedValue as Float }
                start()
            }
        }
    }

    /** 2·3단계: 유리 카드 또는 알약 */
    private fun showCard(ctx: Context, p: ProposalRow, shortcut: Boolean, anchorPkg: String?) {
        val accent = Settings(ctx).accentColor()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, if (shortcut) 10 else 15), dp(ctx, 16), dp(ctx, 12))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, if (shortcut) 32 else 24).toFloat()
                // 블러가 되면 반투명하게, 안 되면 불투명하게 — 글자가 안 보이면 안 된다.
                setColor(if (canBlur()) 0xB2201C2A.toInt() else 0xF01E1A26.toInt())
                setStroke(dp(ctx, 1), withAlpha(accent, 0.45f))
            }
        }

        if (shortcut) {
            root.addView(TextView(ctx).apply {
                text = p.oneLine
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            })
            root.addView(TextView(ctx).apply {
                text = triggerLabel(p)
                setTextColor(0x80FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            })
        } else {
            root.addView(TextView(ctx).apply {
                text = leadLine(p)
                setTextColor(accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, 0, 0, dp(ctx, 6))
            })
            root.addView(TextView(ctx).apply {
                text = p.oneLine
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
            root.addView(TextView(ctx).apply {
                text = p.narrative
                setTextColor(0x9EFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                maxLines = 3
                setPadding(0, dp(ctx, 3), 0, 0)
            })
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 10), 0, 0)
        }
        row.addView(
            button(ctx, if (shortcut) "열기" else primaryLabel(p), accent, primary = true, weight = 2f) {
                scope.launch { DecisionContext.log(ctx, p.signature, "accepted", anchorPkg) }
                if (!shortcut) markAccepted(ctx, p)
                val r = Applier.apply(ctx, p, anchor = anchorPkg)
                dismiss(ctx)
                if (!r.ok) toast(ctx, r.message)
            }
        )
        if (!shortcut) {
            row.addView(button(ctx, "나중에", accent, primary = false, weight = 1f) {
                scope.launch { DecisionContext.log(ctx, p.signature, "not_now", anchorPkg) }
                setState(ctx, p, "snoozed")
                // 거절이 결과를 바꾼다는 걸 그 자리에서 보여준다 — 스팸과의 차이는
                // 문구가 아니라 "내 반응이 뭔가를 바꿨다"는 체감에서 온다.
                toast(ctx, "이런 상황에선 덜 여쭤볼게요")
                dismiss(ctx)
            })
            row.addView(button(ctx, "안 할래요", accent, primary = false, weight = 1f) {
                scope.launch { DecisionContext.log(ctx, p.signature, "dismissed", anchorPkg) }
                setState(ctx, p, "dismissed")
                toast(ctx, "이 제안은 그만 보여드릴게요")
                dismiss(ctx)
            })
        } else {
            row.addView(button(ctx, "닫기", accent, primary = false, weight = 1f) {
                scope.launch { DecisionContext.log(ctx, p.signature, "not_now", anchorPkg) }
                dismiss(ctx)
            })
        }
        root.addView(row)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            y = dp(ctx, 26)
            width = ctx.resources.displayMetrics.widthPixels - dp(ctx, 24)
            if (canBlur()) {
                blurBehindRadius = dp(ctx, 20)
                flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            }
        }

        runCatching { wm(ctx).addView(root, lp) }.onSuccess {
            card = root
            root.alpha = 0f
            root.translationY = dp(ctx, 24).toFloat()
            root.animate().alpha(1f).translationY(0f).setDuration(220).start()
            main.postDelayed({ dismiss(ctx) }, AUTO_HIDE_MS)
        }
    }

    // ------------------------------------------------------------------

    /** 왜 지금 떴는지를 카드보다 먼저 읽히게 한다 */
    private fun leadLine(p: ProposalRow): String = when (p.category) {
        "app_pair" -> "방금 이 흐름, 반복되고 있어요"
        "trigger_routine" -> "이 순간마다 하시던 행동이에요"
        "notif_cleanup" -> "한 번도 열지 않은 알림이에요"
        "app_mode" -> "이 앱을 볼 때마다 하시던 설정이에요"
        else -> "패턴을 찾았어요"
    }

    private fun primaryLabel(p: ProposalRow): String = when (p.actionType) {
        "app_pair" -> "분할화면으로 열기"
        "launch_app" -> "지금 열기"
        "notif_channel_off" -> "알림 정리하기"
        else -> "적용"
    }

    private fun triggerLabel(p: ProposalRow): String = when (p.triggerType) {
        "bt_connect" -> "${p.triggerParam ?: "기기"} 연결됨"
        "wifi_connect" -> "${p.triggerParam ?: "Wi-Fi"} 연결됨"
        else -> "패턴 감지됨"
    }

    private fun markAccepted(ctx: Context, p: ProposalRow) = setState(ctx, p, "accepted")

    private fun setState(ctx: Context, p: ProposalRow, state: String) {
        scope.launch {
            Db.get(ctx).dao().setProposalState(p.signature, state, System.currentTimeMillis())
        }
    }

    fun dismiss(ctx: Context) {
        card?.let { v -> runCatching { wm(ctx).removeView(v) } }
        glow?.let { v -> runCatching { wm(ctx).removeView(v) } }
        card = null
        glow = null
    }

    private fun button(
        ctx: Context, label: String, accent: Int,
        primary: Boolean, weight: Float, onClick: () -> Unit,
    ) = Button(ctx).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        setTextColor(if (primary) Color.WHITE else 0xCCFFFFFF.toInt())
        background = GradientDrawable().apply {
            cornerRadius = 999f
            setColor(if (primary) accent else Color.TRANSPARENT)
            if (!primary) setStroke(dp(ctx, 1), 0x40FFFFFF)
        }
        minHeight = dp(ctx, 42)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
            .apply { marginEnd = dp(ctx, 7) }
        setOnClickListener { onClick() }
    }

    private fun wm(ctx: Context) =
        ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun canBlur() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private fun withAlpha(color: Int, a: Float) =
        Color.argb((a * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(ctx: Context, v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
    ).toInt()

    private fun toast(ctx: Context, msg: String) {
        main.post {
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
