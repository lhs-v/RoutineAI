package com.routineai.watch

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
import com.routineai.data.ProposalEventRow
import com.routineai.data.ProposalRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 다른 앱 위에 뜨는 제안 카드.
 *
 * Compose 가 아니라 View 로 만든 이유: 오버레이는 Activity 밖에서 뜨는데
 * Compose 는 lifecycle·savedState owner 를 요구해서 배선이 늘어난다.
 * 카드 하나에는 과하다.
 *
 * 버튼 셋은 수명주기의 세 갈래 그대로다 — 수락(의도 확인), 이번엔 아님(쿨다운),
 * 그만 보기(영구 제외). 셋 다 기록에 남아 P3 의 심층 분석 입력이 된다.
 */
object SuggestionOverlay {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private var shown: View? = null

    /** 사용자가 반응하지 않으면 스스로 사라진다. 화면을 가로막지 않기 위해. */
    private const val AUTO_HIDE_MS = 12_000L

    fun show(ctx: Context, p: ProposalRow) {
        if (!Applier.hasOverlay(ctx)) return
        main.post { showOnMain(ctx.applicationContext, p) }
    }

    private fun showOnMain(ctx: Context, p: ProposalRow) {
        dismiss(ctx)
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dp = { v: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
            ).toInt()
        }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#F2EEF7FF"))
                setStroke(dp(1), Color.parseColor("#D8CCEA"))
            }
            elevation = dp(8).toFloat()
        }

        card.addView(TextView(ctx).apply {
            text = "RoutineAI 제안"
            setTextColor(Color.parseColor("#6B4FA8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        card.addView(TextView(ctx).apply {
            text = p.oneLine
            setTextColor(Color.parseColor("#141019"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(4), 0, dp(2))
        })
        card.addView(TextView(ctx).apply {
            text = p.narrative
            setTextColor(Color.parseColor("#4A4453"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            maxLines = 3
        })

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        row.addView(button(ctx, "적용", primary = true) {
            decide(ctx, p, "accepted", "accepted")
            val r = Applier.apply(ctx, p)
            toast(ctx, r.message)
            dismiss(ctx)
        })
        row.addView(button(ctx, "이번엔 아님") {
            decide(ctx, p, "snoozed", "not_now"); dismiss(ctx)
        })
        row.addView(button(ctx, "그만 보기") {
            decide(ctx, p, "dismissed", "dismissed"); dismiss(ctx)
        })
        card.addView(row)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // 포커스를 뺏지 않는다 — 아래 앱을 계속 쓸 수 있어야 한다.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            y = dp(28)
            x = dp(12)
            width = ctx.resources.displayMetrics.widthPixels - dp(24)
        }

        runCatching { wm.addView(card, lp) }.onSuccess {
            shown = card
            main.postDelayed({ dismiss(ctx) }, AUTO_HIDE_MS)
        }
    }

    private fun button(ctx: Context, label: String, primary: Boolean = false, onClick: () -> Unit) =
        Button(ctx).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(if (primary) Color.WHITE else Color.parseColor("#4A4453"))
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(if (primary) Color.parseColor("#6B4FA8") else Color.parseColor("#00000000"))
                if (!primary) setStroke(2, Color.parseColor("#C9C2D4"))
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = 8 }
            setOnClickListener { onClick() }
        }

    fun dismiss(ctx: Context) {
        shown?.let { v ->
            runCatching {
                (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
            }
        }
        shown = null
    }

    private fun decide(ctx: Context, p: ProposalRow, state: String, kind: String) {
        scope.launch {
            val now = System.currentTimeMillis()
            val dao = Db.get(ctx).dao()
            dao.setProposalState(p.signature, state, now)
            dao.logProposalEvent(
                ProposalEventRow(ts = now, proposalSignature = p.signature, kind = kind)
            )
        }
    }

    private fun toast(ctx: Context, msg: String) {
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
