package com.familyguard.child.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

object BlockOverlay {

    enum class Reason { APP_BLOCK, TIME_OVER, BEDTIME, SCHEDULE, LOCKED, MESSAGE }

    private val views = HashMap<Reason, View>()
    private val texts = HashMap<Reason, Pair<TextView, TextView>>()
    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null

    fun show(context: Context, reason: Reason, title: String, subtitle: String) {
        if (!Settings.canDrawOverlays(context)) return
        main.post {
            try {
                val ctx = context.applicationContext
                val w = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm = w
                val existing = views[reason]
                if (existing != null) {
                    texts[reason]?.let { (t, s) ->
                        t.text = title
                        s.text = subtitle
                    }
                    return@post
                }
                val root = LinearLayout(ctx)
                root.orientation = LinearLayout.VERTICAL
                root.gravity = Gravity.CENTER
                root.setPadding(dp(ctx, 64), dp(ctx, 64), dp(ctx, 64), dp(ctx, 64))
                root.setBackgroundColor(0xE6000000.toInt())

                val dot = View(ctx)
                dot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x33FFFFFF.toInt())
                }
                dot.layoutParams = LinearLayout.LayoutParams(dp(ctx, 96), dp(ctx, 96))

                val tv = TextView(ctx)
                tv.text = title
                tv.textSize = 26f
                tv.setTypeface(null, Typeface.BOLD)
                tv.setTextColor(Color.WHITE)
                tv.gravity = Gravity.CENTER

                val st = TextView(ctx)
                st.text = subtitle
                st.textSize = 15f
                st.setTextColor(0xFFBBBBBB.toInt())
                st.gravity = Gravity.CENTER

                val ft = TextView(ctx)
                ft.text = "Managed remotely"
                ft.textSize = 11f
                ft.setTextColor(0xFF777777.toInt())
                ft.gravity = Gravity.CENTER

                root.addView(dot)
                root.addView(tv)
                root.addView(st)
                root.addView(ft)

                val p = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                )
                w.addView(root, p)
                views[reason] = root
                texts[reason] = Pair(tv, st)
            } catch (_: Exception) {
            }
        }
    }

    fun hide(reason: Reason) {
        main.post {
            views.remove(reason)?.let { v ->
                runCatching { wm?.removeView(v) }
            }
            texts.remove(reason)
        }
    }

    fun hideAll() {
        Reason.values().forEach { hide(it) }
    }

    fun hideIfAppBlock() {
        hide(Reason.APP_BLOCK)
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
