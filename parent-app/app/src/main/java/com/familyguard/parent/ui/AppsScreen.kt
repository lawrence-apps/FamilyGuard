package com.familyguard.parent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familyguard.parent.MainViewModel
import com.familyguard.parent.data.AppLimit
import com.familyguard.parent.data.Rules
import com.familyguard.parent.data.UsageResp
import kotlinx.coroutines.launch

private data class AppRow(val pkg: String, val label: String, val minToday: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(vm: MainViewModel) {
    val installed by vm.apps.collectAsState()
    val children by vm.children.collectAsState()
    val activeId by vm.activeChildId.collectAsState()
    val cid = (children.firstOrNull { it.id == activeId } ?: children.firstOrNull())?.id

    var usage by remember(cid) { mutableStateOf<UsageResp?>(null) }
    var rules by remember(cid) { mutableStateOf<Rules?>(null) }
    var limitPkg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(cid) {
        if (cid == null) return@LaunchedEffect
        usage = vm.usage(cid)
        rules = vm.rules(cid)
    }

    if (cid == null) {
        EmptyHint("No child paired yet. Create a child profile on the Home tab first.")
        return
    }

    val usageApps = usage?.apps ?: emptyMap()
    val rows = buildList {
        installed.forEach { a ->
            add(AppRow(a.pkg, a.name.ifBlank { a.pkg }, usageApps[a.pkg]?.min ?: 0))
        }
        usageApps.forEach { (pkg, ua) ->
            if (installed.none { it.pkg == pkg }) {
                add(AppRow(pkg, ua.label.ifBlank { pkg }, ua.min))
            }
        }
    }.sortedByDescending { it.minToday }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (installed.isEmpty()) {
            item {
                Text(
                    "App list syncs when child device connects",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
            }
        }
        if (rows.isEmpty()) {
            item { EmptyHint("No app data yet. It will appear once the child device syncs.") }
        }
        items(rows) { row ->
            val blocked = rules?.blockedApps?.contains(row.pkg) == true
            Card(onClick = { limitPkg = row.pkg }, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            row.label.take(1).uppercase(),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(start = 12.dp)
                    ) {
                        Text(
                            row.label,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        Text(
                            row.pkg,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Text(
                        "${row.minToday} min",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Switch(
                        checked = blocked,
                        onCheckedChange = { check ->
                            val current = rules
                            if (current != null) {
                                val newBlocked =
                                    if (check) current.blockedApps + row.pkg else current.blockedApps - row.pkg
                                val updated = current.copy(blockedApps = newBlocked.distinct())
                                rules = updated
                                scope.launch { vm.putRules(cid, updated) }
                            }
                        },
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }

    limitPkg?.let { pkg ->
        var minText by remember(pkg) {
            mutableStateOf((rules?.appLimits?.get(pkg)?.dailyMin ?: 0).toString())
        }
        AlertDialog(
            onDismissRequest = { limitPkg = null },
            title = { Text("Daily limit") },
            text = {
                Column {
                    Text(
                        pkg,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = minText,
                        onValueChange = { minText = it.filter { c -> c.isDigit() } },
                        label = { Text("Minutes per day (0 = none)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val current = rules
                    if (current != null) {
                        val mins = minText.toIntOrNull() ?: 0
                        val newLimits =
                            if (mins > 0) current.appLimits + (pkg to AppLimit(mins))
                            else current.appLimits - pkg
                        val updated = current.copy(appLimits = newLimits)
                        rules = updated
                        scope.launch { vm.putRules(cid, updated) }
                    }
                    limitPkg = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { limitPkg = null }) { Text("Cancel") } }
        )
    }
}
