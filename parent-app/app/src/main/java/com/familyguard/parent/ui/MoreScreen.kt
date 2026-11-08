package com.familyguard.parent.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familyguard.parent.AlertService
import com.familyguard.parent.Graph
import com.familyguard.parent.MainViewModel
import com.familyguard.parent.data.Store
import kotlinx.coroutines.launch

@Composable
fun MoreScreen(vm: MainViewModel, onLoggedOut: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf(Store.serverUrl) }
    var showLogout by remember { mutableStateOf(false) }
    var showRelease by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Account", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (Store.username.isNotBlank()) "Signed in as ${Store.username}" else "Signed in",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { showLogout = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Logout") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Server", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Server URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Button(onClick = {
                        Store.serverUrl = serverUrl.trim()
                        msg = "Server URL saved"
                    }) { Text("Save") }
                    if (msg.isNotBlank()) {
                        Text(
                            msg,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Child device controls", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { vm.sendCommand("show_app_icon") },
                        modifier = Modifier.weight(1f)
                    ) { Text("Show child app icon") }
                    OutlinedButton(
                        onClick = { vm.sendCommand("hide_app_icon") },
                        modifier = Modifier.weight(1f)
                    ) { Text("Hide child app icon") }
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val cid = vm.activeChild()?.id
                            if (cid == null) {
                                msg = "No child paired"
                            } else {
                                val r = vm.rules(cid)
                                msg = if (r != null && vm.putRules(cid, r)) {
                                    "Rules pushed to child"
                                } else {
                                    "Could not push rules"
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Push rules now") }
                HorizontalDivider()
                Text(
                    "Commands apply the next time the child device is connected.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2))
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Release protections & uninstall",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFFB91C1C)
                )
                Text(
                    "18+ hone par ya zarurat khatam hone par: child app apne aap permissions release karke uninstall dialog dikhayega.",
                    fontSize = 13.sp,
                    color = Color(0xFF7F1D1D)
                )
                Button(
                    onClick = { showRelease = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) { Text("Release now") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text("About", style = MaterialTheme.typography.titleSmall)
                Text(
                    "FamilyGuard v1.0 — private self-hosted build. Personal use only.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showLogout) {
        ConfirmDialog(
            title = "Logout",
            text = "Log out from FamilyGuard on this device?",
            confirmLabel = "Logout",
            onConfirm = {
                Graph.ws.disconnect()
                Store.clearAll()
                context.stopService(Intent(context, AlertService::class.java))
                onLoggedOut()
            },
            onDismiss = { showLogout = false }
        )
    }
    if (showRelease) {
        ConfirmDialog(
            title = "Release protections",
            text = "Protections on the child device will be disabled and the uninstall dialog will appear there. Continue?",
            confirmLabel = "Release",
            onConfirm = { vm.sendCommand("release_protections") },
            onDismiss = { showRelease = false }
        )
    }
}
