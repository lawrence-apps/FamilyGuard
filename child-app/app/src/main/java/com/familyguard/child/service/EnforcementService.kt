package com.familyguard.child.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.familyguard.child.BlockAction
import com.familyguard.child.RulesEngine
import com.familyguard.child.data.Store
import com.familyguard.child.ui.BlockOverlay
import java.util.concurrent.ConcurrentHashMap

class EnforcementService : AccessibilityService() {

    private var prevPkg: String? = null
    private var prevTs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Store.accessibilityLostAt = 0L
        prevTs = System.currentTimeMillis()
        if (Store.usageDate == Store.today()) {
            minutesToday.putAll(Store.loadUsage())
        } else {
            Store.saveUsage(emptyMap(), Store.today())
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        if (ev.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = ev.packageName?.toString() ?: return
        if (pkg == packageName) return

        // Reboot recovery: the system MediaProjection consent dialog just opened after
        // WE requested it (ConsentActivity arms the timestamp). Click "Start now"
        // automatically so mirroring works without touching the child phone.
        if (pkg == "com.android.systemui" && isProjectionDialog(ev.className?.toString())) {
            maybeAutoConfirmProjection()
            return
        }

        val now = System.currentTimeMillis()
        prevPkg?.let { prev ->
            if (prev != pkg) {
                val mins = ((now - prevTs) / 60000L).toInt()
                if (mins > 0) minutesToday.merge(prev, mins, Int::plus)
            }
        }
        prevPkg = pkg
        prevTs = now
        lastApp = pkg

        if (Store.today() != Store.usageDate) {
            minutesToday.clear()
            Store.saveUsage(emptyMap(), Store.today())
        }

        if (!Store.released) {
            when (val d = RulesEngine.decide(pkg)) {
                is BlockAction.APP_BLOCK ->
                    BlockOverlay.show(applicationContext, BlockOverlay.Reason.APP_BLOCK, "App blocked", d.label)
                is BlockAction.TIME_OVER ->
                    BlockOverlay.show(applicationContext, BlockOverlay.Reason.TIME_OVER, "Screen time over", "Daily limit reached")
                is BlockAction.BEDTIME ->
                    BlockOverlay.show(applicationContext, BlockOverlay.Reason.BEDTIME, "Bedtime", "Phone is locked until morning")
                is BlockAction.SCHEDULE ->
                    BlockOverlay.show(applicationContext, BlockOverlay.Reason.SCHEDULE, "Scheduled block", d.name)
                BlockAction.NONE -> BlockOverlay.hideIfAppBlock()
            }
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        // Accessibility was turned off — record it so the SelfHeal watchdog can
        // raise the alarm (event to the parent + full-screen fix notification).
        Store.accessibilityLostAt = System.currentTimeMillis()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var lastApp = ""

        val minutesToday = ConcurrentHashMap<String, Int>()

        @Volatile
        var instance: EnforcementService? = null

        fun disableSelfSafe() {
            runCatching { instance?.disableSelf() }
        }

        fun persist(ctx: Context) {
            Store.saveUsage(minutesToday.toMap(), Store.today())
        }

        // ---- projection consent auto-confirm ----

        private val confirmHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var confirmAttempts = 0

        /** Positive buttons we are allowed to click, exact lowercase match. */
        private val POSITIVE_ACTIONS = setOf(
            "start now", "start", "start recording", "start casting",
            "start screen capture", "start screen sharing", "start sharing",
            "share screen", "share entire screen", "share this screen", "share",
            "अभी शुरू करें", "शुरू करें", "अभी प्रारंभ करें", "प्रारंभ करें",
            "स्क्रीन साझा करें", "साझा करें", "शेयर करें"
        )

        /** Android 14+ chooser: make sure the whole screen is the selected option. */
        private val ENTIRE_SCREEN_LABELS = listOf("entire screen", "पूरी स्क्रीन", "पूरा स्क्रीन")

        private fun isProjectionDialog(className: String?): Boolean {
            if (className == null) return false
            val c = className.lowercase()
            return c.contains("mediaprojection") || c.contains("screencapture") || c.contains("screenrecord")
        }
    }

    private fun maybeAutoConfirmProjection() {
        val requestedAt = Store.projectionRequestedAt
        if (requestedAt <= 0L || !Store.paired || Store.released) return
        val dt = SystemClock.elapsedRealtime() - requestedAt
        if (dt < 0L || dt > 60_000L) return
        if (confirmAttempts > 0) return // retry loop already running
        confirmAttempts = 1
        confirmHandler.postDelayed({ attemptConfirm(1) }, 350L)
    }

    private fun attemptConfirm(n: Int) {
        confirmAttempts = n
        val clicked = runCatching { projectionClickPass() }.getOrDefault(false)
        if (clicked) {
            Store.projectionRequestedAt = 0L
            confirmAttempts = 0
            return
        }
        val requestedAt = Store.projectionRequestedAt
        val stillFresh = requestedAt > 0L && SystemClock.elapsedRealtime() - requestedAt <= 60_000L
        if (n < 15 && stillFresh) {
            confirmHandler.postDelayed({ attemptConfirm(n + 1) }, 400L)
        } else {
            confirmAttempts = 0
        }
    }

    /** One pass over the dialog: select "entire screen" if needed, then click the
     *  positive action button. Returns true once a button was clicked. */
    private fun projectionClickPass(): Boolean {
        val root = rootInActiveWindow ?: return false
        for (label in ENTIRE_SCREEN_LABELS) {
            for (n in root.findAccessibilityNodeInfosByText(label)) {
                if (n.isCheckable && !n.isSelected) clickDeep(n)
            }
        }
        val matches = mutableSetOf<AccessibilityNodeInfo>()
        for (needle in listOf("start", "share", "शुरू", "प्रारंभ", "साझा", "शेयर")) {
            matches.addAll(root.findAccessibilityNodeInfosByText(needle))
        }
        for (n in matches) {
            val text = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase() ?: continue
            if (text in POSITIVE_ACTIONS && !n.isCheckable && clickDeep(n)) return true
        }
        return false
    }

    private fun clickDeep(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 4) {
            if (n.isClickable) {
                return runCatching { n.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
            }
            n = n.parent
            hops++
        }
        return false
    }
}
