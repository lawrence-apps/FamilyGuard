package com.familyguard.parent.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familyguard.parent.MainViewModel
import com.familyguard.parent.data.AppLimit
import com.familyguard.parent.data.Bedtime
import com.familyguard.parent.data.Rules
import com.familyguard.parent.data.Schedule
import com.familyguard.parent.data.UsageResp
import kotlinx.coroutines.launch

private val TIME_REGEX = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

@Composable
fun ScreenTimeScreen(vm: MainViewModel) {
    val children by vm.children.collectAsState()
    val activeId by vm.activeChildId.collectAsState()
    val cid = (children.firstOrNull { it.id == activeId } ?: children.firstOrNull())?.id

    var rules by remember(cid) { mutableStateOf<Rules?>(null) }
    var usage by remember(cid) { mutableStateOf<UsageResp?>(null) }
    var loaded by remember(cid) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(cid) {
        if (cid == null) return@LaunchedEffect
        rules = vm.rules(cid)
        usage = vm.usage(cid)
        loaded = true
    }

    if (cid == null) {
        EmptyHint("No child paired yet. Create a child profile on the Home tab first.")
        return
    }
    if (!loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val r = rules
    if (r == null) {
        EmptyHint("Could not load rules. Check the server connection, then reopen this tab.")
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        UsageCard(usage, r.dailyScreenTimeMin)
        LimitCard(r.dailyScreenTimeMin) { v -> rules = r.copy(dailyScreenTimeMin = v) }
        BedtimeCard(r.bedtime) { b -> rules = r.copy(bedtime = b) }
        SchedulesCard(
            schedules = r.schedules,
            onAdd = { s -> rules = r.copy(schedules = r.schedules + s) },
            onDelete = { s -> rules = r.copy(schedules = r.schedules - s) }
        )
        BlockedAppsCard(
            blocked = r.blockedApps,
            onAdd = { p -> rules = r.copy(blockedApps = (r.blockedApps + p).distinct()) },
            onDelete = { p -> rules = r.copy(blockedApps = r.blockedApps - p) }
        )
        AppLimitsCard(
            limits = r.appLimits,
            onAdd = { pkg, mins ->
                rules = r.copy(appLimits = r.appLimits + (pkg to AppLimit(mins)))
            },
            onDelete = { pkg -> rules = r.copy(appLimits = r.appLimits - pkg) }
        )
        AlwaysAllowedCard(
            allowed = r.alwaysAllowed,
            onAdd = { p -> rules = r.copy(alwaysAllowed = (r.alwaysAllowed + p).distinct()) },
            onDelete = { p -> rules = r.copy(alwaysAllowed = r.alwaysAllowed - p) }
        )
        Button(
            onClick = {
                val current = rules
                if (current != null) {
                    saving = true
                    scope.launch {
                        val ok = vm.putRules(cid, current)
                        saving = false
                        Toast.makeText(
                            context,
                            if (ok) "Saved — pushed to child device" else "Save failed — check server",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (saving) "Saving…" else "Save rules") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun UsageCard(usage: UsageResp?, limitMin: Int) {
    val used = usage?.totalMin ?: 0
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Screen time today", style = MaterialTheme.typography.titleSmall)
            if (limitMin > 0) {
                val frac = (used.toFloat() / limitMin).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Used $used min today / Limit $limitMin min", fontSize = 13.sp)
            } else {
                LinearProgressIndicator(
                    progress = { 0f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Used $used min today — No daily limit", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun LimitCard(current: Int, onChange: (Int) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Daily screen time limit", style = MaterialTheme.typography.titleSmall)
            Text(
                if (current == 0) "Unlimited" else "$current min per day",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = current.toFloat(),
                onValueChange = { onChange(((it / 15).toInt() * 15)) },
                valueRange = 0f..480f,
                steps = 31
            )
            Text(
                "0 = Unlimited · 15 min steps · max 8 h",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BedtimeCard(bedtime: Bedtime, onChange: (Bedtime) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Bedtime blocking",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = bedtime.enabled,
                    onCheckedChange = { onChange(bedtime.copy(enabled = it)) }
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = bedtime.start,
                    onValueChange = { onChange(bedtime.copy(start = it.trim())) },
                    label = { Text("From (HH:mm)") },
                    isError = !TIME_REGEX.matches(bedtime.start),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = bedtime.end,
                    onValueChange = { onChange(bedtime.copy(end = it.trim())) },
                    label = { Text("To (HH:mm)") },
                    isError = !TIME_REGEX.matches(bedtime.end),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                "Format HH:mm, e.g. 21:30",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SchedulesCard(
    schedules: List<Schedule>,
    onAdd: (Schedule) -> Unit,
    onDelete: (Schedule) -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Schedules",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showAdd = true }) { Text("Add") }
            }
            if (schedules.isEmpty()) {
                Text(
                    "No schedules yet.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            schedules.forEach { s ->
                val daysText = if (s.days.isEmpty()) {
                    "Every day"
                } else {
                    s.days.sorted().distinct()
                        .joinToString(" ") { DAY_NAMES[it.coerceIn(0, 6)] }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            s.name.ifBlank { "Schedule" },
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            "${s.start}–${s.end} · $daysText",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (s.blockAll) "Blocks everything" else "Allowed apps only",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { onDelete(s) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete ${s.name}")
                    }
                }
            }
        }
    }
    if (showAdd) {
        ScheduleDialog(
            onAdd = {
                onAdd(it)
                showAdd = false
            },
            onDismiss = { showAdd = false }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleDialog(onAdd: (Schedule) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("08:00") }
    var end by remember { mutableStateOf("15:00") }
    var blockAll by remember { mutableStateOf(true) }
    var days by remember { mutableStateOf(setOf<Int>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add schedule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (e.g. School)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = start,
                        onValueChange = { start = it.trim() },
                        label = { Text("Start") },
                        isError = !TIME_REGEX.matches(start),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = end,
                        onValueChange = { end = it.trim() },
                        label = { Text("End") },
                        isError = !TIME_REGEX.matches(end),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Block all apps", modifier = Modifier.weight(1f), fontSize = 13.sp)
                    Switch(checked = blockAll, onCheckedChange = { blockAll = it })
                }
                Text(
                    "Active days (none = every day)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DAY_NAMES.forEachIndexed { i, d ->
                        FilterChip(
                            selected = days.contains(i),
                            onClick = { days = if (days.contains(i)) days - i else days + i },
                            label = { Text(d, fontSize = 11.sp) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && TIME_REGEX.matches(start) && TIME_REGEX.matches(end),
                onClick = {
                    onAdd(
                        Schedule(
                            name = name.trim(),
                            days = days.toList().sorted(),
                            start = start,
                            end = end,
                            blockAll = blockAll
                        )
                    )
                }
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun BlockedAppsCard(
    blocked: List<String>,
    onAdd: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    var pkg by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Blocked apps", style = MaterialTheme.typography.titleSmall)
            if (blocked.isEmpty()) {
                Text(
                    "None blocked.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            blocked.forEach { p ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(p, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onDelete(p) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Remove $p")
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = pkg,
                    onValueChange = { pkg = it },
                    label = { Text("Package name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    enabled = pkg.isNotBlank(),
                    onClick = {
                        onAdd(pkg.trim())
                        pkg = ""
                    }
                ) { Text("Add") }
            }
        }
    }
}

@Composable
private fun AppLimitsCard(
    limits: Map<String, AppLimit>,
    onAdd: (String, Int) -> Unit,
    onDelete: (String) -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
    var addPkg by remember { mutableStateOf("") }
    var addMin by remember { mutableStateOf("60") }
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Per-app daily limits",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showAdd = true }) { Text("Add") }
            }
            if (limits.isEmpty()) {
                Text(
                    "No app limits set.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            limits.forEach { (pkg, limit) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(pkg, fontSize = 13.sp)
                        Text(
                            "${limit.dailyMin} min/day",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { onDelete(pkg) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Remove limit for $pkg")
                    }
                }
            }
        }
    }
    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add app limit") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = addPkg,
                        onValueChange = { addPkg = it },
                        label = { Text("Package name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = addMin,
                        onValueChange = { addMin = it.filter { c -> c.isDigit() } },
                        label = { Text("Minutes per day") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = addPkg.isNotBlank() && addMin.toIntOrNull() != null,
                    onClick = {
                        onAdd(addPkg.trim(), addMin.toIntOrNull() ?: 0)
                        addPkg = ""
                        addMin = "60"
                        showAdd = false
                    }
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun AlwaysAllowedCard(
    allowed: List<String>,
    onAdd: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    var pkg by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Always allowed", style = MaterialTheme.typography.titleSmall)
            if (allowed.isEmpty()) {
                Text(
                    "Nothing marked always allowed.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            allowed.forEach { p ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(p, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onDelete(p) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Remove $p")
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = pkg,
                    onValueChange = { pkg = it },
                    label = { Text("Package name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    enabled = pkg.isNotBlank(),
                    onClick = {
                        onAdd(pkg.trim())
                        pkg = ""
                    }
                ) { Text("Add") }
            }
        }
    }
}
