package com.familyguard.child.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
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
    }
}
