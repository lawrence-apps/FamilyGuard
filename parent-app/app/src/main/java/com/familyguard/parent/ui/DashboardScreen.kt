package com.familyguard.parent.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PhotoCameraBack
import androidx.compose.material.icons.outlined.PhotoCameraFront
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.ScreenShare
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.familyguard.parent.MainViewModel
import com.familyguard.parent.data.Child
import com.familyguard.parent.data.StatusUi
import com.familyguard.parent.data.ageYears
import com.google.gson.JsonObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(vm: MainViewModel, nav: NavHostController) {
    val children by vm.children.collectAsState()
    val statusMap by vm.status.collectAsState()
    val activeId by vm.activeChildId.collectAsState()
    val lastResult by vm.lastResult.collectAsState()
    val child = children.firstOrNull { it.id == activeId } ?: children.firstOrNull()

    var showCreate by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf<Child?>(null) }
    var showRelease by remember { mutableStateOf(false) }
    var showLock by remember { mutableStateOf(false) }
    var showMessage by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (children.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "No child paired yet. Create a child profile and enter its code on the child's phone.",
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp
                    )
                    Button(onClick = { showCreate = true }) { Text("Create child") }
                }
            }
        } else {
            ChildSelector(
                children = children,
                selectedId = activeId,
                onSelect = { vm.selectChild(it) }
            )
        }

        if (child != null) {
            StatusCard(
                child = child,
                st = statusMap[child.id],
                onRegenerate = { vm.regenerate(child.id) { } },
                onRepair = { vm.sendCommand("heal") }
            )
            if (child.ageYears() >= 18) {
                ReleaseBanner(onRelease = { showRelease = true })
            }
            QuickActions(
                vm = vm,
                nav = nav,
                onLock = { showLock = true },
                onMessage = { showMessage = true },
                onRelease = { showRelease = true }
            )
        }

        if (lastResult.isNotBlank()) {
            Text(lastResult, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showCreate) {
        CreateChildDialog(
            onCreate = { name, dob ->
                showCreate = false
                vm.createChild(name, dob) { c -> if (c != null) created = c }
            },
            onDismiss = { showCreate = false }
        )
    }
    created?.let { c ->
        PairingCodeDialog(child = c, onClose = { created = null })
    }
    if (showRelease) {
        ConfirmDialog(
            title = "Release & uninstall child app",
            text = "All protections on the child device will be removed automatically: accessibility + device admin disabled, rules cleared, services stopped — then the system uninstall dialog opens there and keeps re-opening until the app is fully uninstalled. Continue?",
            confirmLabel = "Release & uninstall",
            onConfirm = { vm.sendCommand("release_protections") },
            onDismiss = { showRelease = false }
        )
    }
    if (showLock) LockDialog(vm) { showLock = false }
    if (showMessage) MessageDialog(vm) { showMessage = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChildSelector(children: List<Child>, selectedId: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = children.firstOrNull { it.id == selectedId } ?: children.firstOrNull()
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selected?.let { c ->
                if (c.deviceName.isNotBlank()) "${c.name} (${c.deviceName})" else c.name
            } ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text("Child") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            children.forEach { c ->
                DropdownMenuItem(
                    text = {
                        Text(if (c.deviceName.isNotBlank()) "${c.name} (${c.deviceName})" else c.name)
                    },
                    onClick = {
                        onSelect(c.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun StatusCard(child: Child, st: StatusUi?, onRegenerate: () -> Unit, onRepair: () -> Unit) {
    val ui = st ?: StatusUi(
        online = child.online,
        battery = child.battery,
        charging = false,
        currentApp = child.currentApp,
        health = child.health
    )
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(
                            if (ui.online) Color(0xFF16A34A) else Color(0xFF9CA3AF),
                            CircleShape
                        )
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    if (ui.online) "Online" else "Offline",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (ui.charging) Icons.Outlined.BatteryChargingFull else Icons.Outlined.BatteryFull,
                    contentDescription = if (ui.charging) "Charging" else "Battery"
                )
                Text("${ui.battery}%")
            }
            if (ui.currentApp.isNotBlank()) {
                Text("Current app: ${ui.currentApp}", fontSize = 13.sp)
            }
            RepairBanner(ui.health, onRepair)
            if (child.lastSeen > 0) {
                Text(
                    "Last seen: " + DateUtils.getRelativeTimeSpanString(child.lastSeen).toString(),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!child.paired) {
                Text(
                    "Pairing code",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    child.pairingCode.ifBlank { "—" },
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Enter this code in the child app to pair the device.",
                    fontSize = 13.sp
                )
                TextButton(onClick = onRegenerate) { Text("Generate new code") }
            }
        }
    }
}

/** Broken-check keys shown to the parent with human labels. */
private val HEALTH_LABELS = mapOf(
    "service" to "Background service",
    "accessibility" to "Accessibility",
    "admin" to "Device admin",
    "location" to "Location",
    "usageAccess" to "Usage access",
    "overlay" to "Overlay",
    "camera" to "Camera",
    "notifListener" to "Notifications"
)

private fun brokenItems(health: JsonObject?): List<String> {
    if (health == null) return emptyList()
    return HEALTH_LABELS.mapNotNull { (key, label) ->
        val v = health.get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: return@mapNotNull null
        if (!v) label else null
    }
}

@Composable
private fun RepairBanner(health: JsonObject?, onRepair: () -> Unit) {
    val broken = remember(health) { brokenItems(health) }
    if (broken.isEmpty()) return
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2))
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "Needs repair: ${broken.joinToString(", ")}",
                fontSize = 13.sp,
                color = Color(0xFFB91C1C),
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Auto-Repair restarts the child device services and pops a fix prompt on the child's phone — no need to take the phone.",
                fontSize = 12.sp,
                color = Color(0xFF7F1D1D)
            )
            Button(onClick = onRepair) { Text("Auto-Repair") }
        }
    }
}

@Composable
private fun ReleaseBanner(onRelease: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB))
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "Child is 18+. Protections can be released — the child app will uninstall itself.",
                fontSize = 13.sp,
                color = Color(0xFF92400E)
            )
            TextButton(onClick = onRelease) { Text("Release now") }
        }
    }
}

private data class QuickAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
private fun QuickActions(
    vm: MainViewModel,
    nav: NavHostController,
    onLock: () -> Unit,
    onMessage: () -> Unit,
    onRelease: () -> Unit
) {
    val actions = listOf(
        QuickAction("Locate", Icons.Outlined.LocationOn) { vm.sendCommand("locate_now") },
        QuickAction("Ring", Icons.Outlined.NotificationsActive) { vm.sendCommand("ring") },
        QuickAction("Lock", Icons.Outlined.Lock) { onLock() },
        QuickAction("Unlock", Icons.Outlined.LockOpen) { vm.sendCommand("unlock") },
        QuickAction("Screenshot", Icons.Outlined.Screenshot) { vm.sendCommand("screenshot") },
        QuickAction("Mirror", Icons.Outlined.ScreenShare) { nav.navigate("mirror") },
        QuickAction("Cam front", Icons.Outlined.PhotoCameraFront) {
            vm.sendCommand("camera", JsonObject().apply { addProperty("facing", "front") })
        },
        QuickAction("Cam back", Icons.Outlined.PhotoCameraBack) {
            vm.sendCommand("camera", JsonObject().apply { addProperty("facing", "back") })
        },
        QuickAction("Message", Icons.Outlined.Chat) { onMessage() },
        QuickAction("Geofence", Icons.Outlined.AddLocationAlt) { nav.navigate("geofence") },
        QuickAction("Auto-Repair", Icons.Outlined.Build) { vm.sendCommand("heal") },
        QuickAction("Uninstall", Icons.Outlined.DeleteForever) { onRelease() },
        QuickAction("More tools", Icons.Outlined.Tune) { nav.navigate("remote") }
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { a ->
                    FilledTonalButton(
                        onClick = a.onClick,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(a.icon, contentDescription = a.label)
                            Spacer(Modifier.height(4.dp))
                            Text(a.label, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private fun formatDobInput(input: String): String {
    val digits = input.filter { it.isDigit() }.take(8)
    val sb = StringBuilder()
    digits.forEachIndexed { i, c ->
        sb.append(c)
        if ((i == 3 && digits.length > 4) || (i == 5 && digits.length > 6)) sb.append('-')
    }
    return sb.toString()
}

@Composable
private fun CreateChildDialog(onCreate: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var dob by remember { mutableStateOf("") }
    val dobValid = dob.length == 10
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create child profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Child name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dob,
                    onValueChange = { dob = formatDobInput(it) },
                    label = { Text("Date of birth (2008-06-15)") },
                    supportingText = {
                        Text("Child's date of birth YYYY-MM-DD — used for automatic release at 18")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = dob.isNotEmpty() && !dobValid,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && dobValid,
                onClick = { onCreate(name.trim(), dob) }
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PairingCodeDialog(child: Child, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Child created") },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(child.name, fontWeight = FontWeight.SemiBold)
                Text(
                    child.pairingCode.ifBlank { "—" },
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 6.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Enter this code in the child app (one-time setup). Code also visible here anytime.",
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } }
    )
}
