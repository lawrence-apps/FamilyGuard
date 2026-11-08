package com.familyguard.child

import com.familyguard.child.data.Rules
import com.familyguard.child.data.Store
import com.familyguard.child.service.EnforcementService
import java.util.Calendar

sealed class BlockAction {
    data class APP_BLOCK(val label: String) : BlockAction()
    object TIME_OVER : BlockAction()
    object BEDTIME : BlockAction()
    data class SCHEDULE(val name: String) : BlockAction()
    object NONE : BlockAction()
}

object RulesEngine {

    @Volatile
    private var rules: Rules = Rules()

    fun update(r: Rules) {
        rules = r
    }

    fun decide(fgPkg: String): BlockAction {
        if (Store.released) return BlockAction.NONE

        val ownPkg = runCatching { Graph.app.packageName }.getOrDefault("com.familyguard.child")
        val allowed = rules.alwaysAllowed.contains(fgPkg) || fgPkg == ownPkg

        val cal = Calendar.getInstance()
        val dow = (cal.get(Calendar.DAY_OF_WEEK) + 6) % 7
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        val bt = rules.bedtime
        if (bt.enabled && isNowBetween(bt.start, bt.end, nowMin) && !allowed) {
            return BlockAction.BEDTIME
        }

        for (s in rules.schedules) {
            if (s.blockAll && s.days.contains(dow) && isNowBetween(s.start, s.end, nowMin) &&
                !allowed && !s.allowedApps.contains(fgPkg)
            ) {
                return BlockAction.SCHEDULE(s.name)
            }
        }

        if (rules.blockedApps.contains(fgPkg) && !allowed) {
            return BlockAction.APP_BLOCK(labelOf(fgPkg))
        }

        val total = EnforcementService.minutesToday.values.sum()
        if (rules.dailyScreenTimeMin > 0 && total >= rules.dailyScreenTimeMin && !allowed) {
            return BlockAction.TIME_OVER
        }

        rules.appLimits[fgPkg]?.let { lim ->
            if (lim.dailyMin > 0 && (EnforcementService.minutesToday[fgPkg] ?: 0) >= lim.dailyMin && !allowed) {
                return BlockAction.APP_BLOCK(labelOf(fgPkg))
            }
        }

        return BlockAction.NONE
    }

    fun isNowBetween(start: String, end: String, nowMin: Int): Boolean {
        val s = toMinutes(start)
        val e = toMinutes(end)
        if (s < 0 || e < 0) return false
        return if (s <= e) nowMin >= s && nowMin < e else nowMin >= s || nowMin < e
    }

    private fun toMinutes(hhmm: String): Int = runCatching {
        val parts = hhmm.split(":")
        parts[0].trim().toInt() * 60 + parts[1].trim().toInt()
    }.getOrDefault(-1)

    fun labelOf(pkg: String): String = runCatching {
        Graph.app.packageManager.getApplicationInfo(pkg, 0).loadLabel(Graph.app.packageManager).toString()
    }.getOrDefault(pkg)
}
