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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CallMade
import androidx.compose.material.icons.outlined.CallMissed
import androidx.compose.material.icons.outlined.CallReceived
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familyguard.parent.MainViewModel
import com.familyguard.parent.data.Api
import com.familyguard.parent.data.CallItem
import com.familyguard.parent.data.EventItem
import com.familyguard.parent.data.SmsItem
import kotlinx.coroutines.launch

@Composable
fun AlertsScreen(vm: MainViewModel) {
    val children by vm.children.collectAsState()
    val activeId by vm.activeChildId.collectAsState()
    val cid = (children.firstOrNull { it.id == activeId } ?: children.firstOrNull())?.id

    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("Events", "Notifications", "Calls & SMS")

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        when (tab) {
            0 -> EventsTab(vm, cid)
            1 -> NotificationsTab(vm, cid)
            else -> CallsSmsTab(vm, cid)
        }
    }
}

private fun eventColor(type: String): Color = when (type) {
    "geofence_enter" -> Color(0xFF16A34A)
    "geofence_exit" -> Color(0xFFF59E0B)
    "screentime_over", "app_blocked" -> Color(0xFFDC2626)
    "released" -> Color(0xFF2563EB)
    else -> Color(0xFF9CA3AF)
}

@Composable
private fun EventsTab(vm: MainViewModel, cid: String?) {
    val events by vm.events.collectAsState()
    LaunchedEffect(cid) {
        if (cid != null) vm.loadEvents(cid)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Events (${events.size})",
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        IconButton(onClick = { if (cid != null) vm.loadEvents(cid) }) {
            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh events")
        }
    }
    if (events.isEmpty()) {
        EmptyHint("No events yet. Geofence and screen-time alerts will show up here.")
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(events) { e ->
            EventRow(e)
        }
    }
}

@Composable
private fun EventRow(e: EventItem) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .size(10.dp)
                    .background(eventColor(e.type), CircleShape)
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 10.dp)
            ) {
                Text(
                    prettyType(e.type),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                if (e.data != null) {
                    Text(
                        e.data.toString().take(120),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    timeAgo(e.t),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun NotificationsTab(vm: MainViewModel, cid: String?) {
    val notifs by vm.notifs.collectAsState()
    LaunchedEffect(cid) {
        if (cid != null) vm.loadNotifs(cid)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Notifications (${notifs.size})",
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        IconButton(onClick = { if (cid != null) vm.loadNotifs(cid) }) {
            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh notifications")
        }
    }
    if (notifs.isEmpty()) {
        EmptyHint("No notifications captured yet.")
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(notifs) { n ->
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            n.pkg.ifBlank { "app" },
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        n.title.ifBlank { "(no title)" },
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    if (n.text.isNotBlank()) {
                        Text(n.text, fontSize = 13.sp)
                    }
                    Text(
                        timeAgo(n.date),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun CallsSmsTab(vm: MainViewModel, cid: String?) {
    var calls by remember(cid) { mutableStateOf<List<CallItem>>(emptyList()) }
    var sms by remember(cid) { mutableStateOf<List<SmsItem>>(emptyList()) }
    var loading by remember(cid) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun load() {
        val c = cid ?: return
        loading = true
        scope.launch {
            val callsResp = runCatching { Api.calls(c) }.getOrNull()
            val smsResp = runCatching { Api.sms(c) }.getOrNull()
            calls = callsResp?.calls ?: emptyList()
            sms = smsResp?.sms ?: emptyList()
            loading = false
        }
    }

    LaunchedEffect(cid) { load() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Call log & messages",
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(onClick = { load() }) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Refresh calls and sms")
            }
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Loading…",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (calls.isEmpty() && sms.isEmpty()) {
            EmptyHint("No call or SMS data synced yet.")
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { SectionHeader("Calls") }
                if (calls.isEmpty()) {
                    item {
                        Text(
                            "No calls synced.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(calls) { c ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                callIcon(c.type),
                                contentDescription = c.type,
                                tint = callColor(c.type)
                            )
                            Column(
                                Modifier
                                    .weight(1f)
                                    .padding(start = 10.dp)
                            ) {
                                Text(
                                    if (c.name.isNotBlank()) c.name else c.number,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    "${c.number} · ${c.duration / 60} min · ${timeAgo(c.date)}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                item { SectionHeader("SMS") }
                if (sms.isEmpty()) {
                    item {
                        Text(
                            "No SMS synced.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(sms) { s ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                if (s.name.isNotBlank()) s.name else s.number,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                s.body,
                                fontSize = 13.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${if (s.type == "out") "Sent" else "Received"} · ${timeAgo(s.date)}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun callIcon(type: String) = when (type) {
    "out" -> Icons.Outlined.CallMade
    "missed" -> Icons.Outlined.CallMissed
    else -> Icons.Outlined.CallReceived
}

private fun callColor(type: String): Color = when (type) {
    "out" -> Color(0xFF2563EB)
    "missed" -> Color(0xFFDC2626)
    else -> Color(0xFF16A34A)
}
