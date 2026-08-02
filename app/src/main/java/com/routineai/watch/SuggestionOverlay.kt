package com.routineai.watch

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
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

    /** 일렁임 전체 길이. 두 번의 숨 뒤 옅은 상시 발광으로 가라앉는다. */
    private const val GLOW_TOTAL_MS = 1_400L

    /** 카드는 첫 숨이 지나 "알아챘다"가 전달된 뒤에 나온다. */
    private const val CARD_DELAY_MS = 950L

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
                main.postDelayed({ showCard(app, p, false, anchorPkg) }, CARD_DELAY_MS)
            }
        }
    }

    // ------------------------------------------------------------------

    /**
     * 1단계: 가장자리 광원. 터치를 통과시켜 아래 앱을 계속 쓸 수 있다.
     *
     * 선(스트로크)이 아니라 네 변에서 안쪽으로 번지는 빛이다 — 얇은 테두리는
     * 실측에서 인지되지 못했다. 숨 쉬듯 두 번 일렁였다가 옅게 가라앉아,
     * 카드가 떠 있는 동안 "지금 화면이 그 대상"임을 계속 알린다.
     */
    private fun showGlow(ctx: Context) {
        val accent = Settings(ctx).accentColor()
        val view = EdgeGlowView(ctx, accent)
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
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = GLOW_TOTAL_MS
                addUpdateListener { view.phase = it.animatedValue as Float }
                start()
            }
        }
    }

    /**
     * 화면 가장자리에서 안쪽으로 번지는 빛.
     *
     * phase 0→1 동안 사인 파동 두 번을 타서 폭·밝기가 함께 오르내리고
     * (일렁임), 끝에서는 옅은 상시 발광(alpha ≈ 0.12)으로 남는다.
     * 단색 액센트만 쓴다 — 여러 색을 돌리면 알림이 아니라 광고처럼 읽힌다.
     */
    private class EdgeGlowView(ctx: Context, color: Int) : View(ctx) {
        var phase = 0f
            set(v) { field = v; invalidate() }

        private val paint = Paint()
        private val rgb = color and 0x00FFFFFF

        /** 액센트를 흰색 쪽으로 끌어올린 심(core) 색 — 같은 색 계열 배경에서도
         *  가장자리가 빛나는 선으로 읽히게 한다(실측: 틸 벽지에 틸 발광이 묻혔다). */
        private val coreRgb = run {
            val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
            Color.rgb(
                r + ((255 - r) * 0.65f).toInt(),
                g + ((255 - g) * 0.65f).toInt(),
                b + ((255 - b) * 0.65f).toInt(),
            ) and 0x00FFFFFF
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            // 숨 한 번: 0→1→0. 두 번은 반짝임(경고등)으로 읽혔다(실측 피드백).
            // 크게 한 번 차올랐다가 옅은 상시 발광으로 가라앉는다.
            val breath = 0.5f - 0.5f * kotlin.math.cos(phase * 2f * Math.PI.toFloat())
            val strength = breath + 0.2f * phase
            // 폭은 가장자리 띠에 머문다 — 안쪽까지 차오르면 알림이 아니라
            // 화면 전체가 물드는 느낌이 된다(실측 피드백).
            val glowW = (0.045f + 0.03f * breath) * minOf(w, h)

            // 3단 그라데이션: 밝은 심 → 액센트 번짐 → 투명.
            val core = ((strength * 230).toInt().coerceIn(0, 255) shl 24) or coreRgb
            val bloom = ((strength * 160).toInt().coerceIn(0, 255) shl 24) or rgb
            val colors = intArrayOf(core, bloom, Color.TRANSPARENT)
            val stops = floatArrayOf(0f, 0.28f, 1f)

            fun edge(x0: Float, y0: Float, x1: Float, y1: Float, l: Float, t: Float, r: Float, b: Float) {
                paint.shader =
                    LinearGradient(x0, y0, x1, y1, colors, stops, Shader.TileMode.CLAMP)
                canvas.drawRect(l, t, r, b, paint)
            }
            edge(0f, 0f, 0f, glowW, 0f, 0f, w, glowW)               // 위
            edge(0f, h, 0f, h - glowW, 0f, h - glowW, w, h)          // 아래
            edge(0f, 0f, glowW, 0f, 0f, 0f, glowW, h)               // 왼쪽
            edge(w, 0f, w - glowW, 0f, w - glowW, 0f, w, h)          // 오른쪽
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

        fun acceptAndApply(chosen: String?) {
            scope.launch {
                DecisionContext.log(ctx, p.signature, "accepted", anchorPkg, choice = chosen)
            }
            if (!shortcut) markAccepted(ctx, p)
            val r = Applier.apply(ctx, p, anchor = anchorPkg, chosen = chosen)
            // 자동 실행이 없어진 뒤로 모드 원복을 걸 곳은 적용 지점뿐이다.
            if (r.ok && p.category == "app_mode") {
                scope.launch { PatternWatcher.onModeApplied(ctx.applicationContext, p) }
            }
            // 실행 결과(유지/이탈) 판정 — 수락률만으로는 잘못된 발화를 못 본다.
            if (r.ok && p.actionType == "launch_app") {
                PatternWatcher.noteApplied(p.signature, chosen ?: Applier.params(p).firstOrNull())
            }
            dismiss(ctx)
            if (!r.ok) toast(ctx, r.message)
        }

        // 선택지 숏컷: launch_app 에 후보가 둘이면 실행 버튼을 두 개로.
        // "습관은 Music, 가끔은 YouTube" 같은 갈래를 하나로 뭉개지 않는다 —
        // 어느 쪽을 골랐는지가 기록되어 심층 분석의 정제 재료가 된다.
        val choices = if (p.actionType == "launch_app") Applier.params(p) else emptyList()
        if (choices.size >= 2) {
            row.addView(button(ctx, "${appLabel(ctx, choices[0])} 열기", accent, primary = true, weight = 1.3f) {
                acceptAndApply(choices[0])
            })
            row.addView(button(ctx, appLabel(ctx, choices[1]), accent, primary = false, weight = 1f) {
                acceptAndApply(choices[1])
            })
        } else {
            row.addView(
                button(ctx, if (shortcut) "열기" else primaryLabel(p), accent, primary = true, weight = 2f) {
                    acceptAndApply(null)
                }
            )
        }
        if (!shortcut) {
            row.addView(button(ctx, "나중에", accent, primary = false, weight = 1f) {
                scope.launch { DecisionContext.log(ctx, p.signature, "not_now", anchorPkg) }
                // 수락된 루틴의 '나중에'는 강등이 아니다 — 허브에서 조용히
                // 사라지면 그게 더 놀랍다. 기록만 남긴다.
                if (p.state != "accepted") setState(ctx, p, "snoozed")
                // 버킷 거절 학습이 보류 중이라 "덜 여쭤본다"고 하면 거짓말이 된다.
                toast(ctx, "다음에 다시 여쭤볼게요")
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
            // 스르륵 나타나는 게 아니라 "톡 튀어나온다" — 오버슈트가 잠깐
            // 커졌다 제자리를 찾는 그 반동이 등장을 사건으로 만든다.
            // 미세 햅틱을 함께 줘서 눈이 다른 곳에 있어도 인지되게 한다.
            root.alpha = 0f
            root.translationY = dp(ctx, 34).toFloat()
            root.scaleX = 0.92f
            root.scaleY = 0.92f
            root.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(340)
                .setInterpolator(OvershootInterpolator(1.15f))
                .withStartAction {
                    root.performHapticFeedback(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                            HapticFeedbackConstants.CONFIRM
                        else HapticFeedbackConstants.VIRTUAL_KEY
                    )
                }
                .start()
            main.postDelayed({
                // 아직 붙어 있으면 무반응 소멸이다 — 직후에 사용자가 스스로
                // 여는 첫 앱이 "정말 원했던 것"의 단서가 된다.
                if (card === root) {
                    if (p.actionType == "launch_app") PatternWatcher.noteIgnored(p.signature)
                    dismiss(ctx)
                }
            }, AUTO_HIDE_MS)
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
        // 삼성이 분할 토글을 막는 것을 실측으로 확인 — 약속을 지킬 수 있는
        // 문구로 쓴다. 분할이 되면 결과 메시지가 알려준다.
        "app_pair" -> "두 앱 함께 열기"
        "launch_app" -> "지금 열기"
        "notif_channel_off" -> "알림 정리하기"
        else -> "적용"
    }

    private fun appLabel(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg.substringAfterLast('.'))

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
