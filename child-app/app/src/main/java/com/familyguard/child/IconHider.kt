package com.familyguard.child

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object IconHider {

    private fun alias(ctx: Context): ComponentName = ComponentName(ctx, "com.familyguard.child.MainAlias")

    fun hide(ctx: Context) {
        runCatching {
            ctx.packageManager.setComponentEnabledSetting(
                alias(ctx),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    fun show(ctx: Context) {
        runCatching {
            ctx.packageManager.setComponentEnabledSetting(
                alias(ctx),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    fun hidden(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getComponentEnabledSetting(alias(ctx)) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }.getOrDefault(false)
}
