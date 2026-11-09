package com.familyguard.parent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CallMade
import androidx.compose.material.icons.outlined.CallMissed
import androidx.compose.material.icons.outlined.CallReceived
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sms
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familyguard.parent.MainViewModel

/**
 * Call log + SMS viewer — Home ke "Call log" / "SMS" quick actions se seedha
 * yahan aata hai (pehle ye data sirf Alerts tab ke 3rd sub-tab me chhupa tha,
 * isliye parents ko "option nahi aa raha" lagta tha).
 *
 * Data child phone ke UsageWorker se aata hai (READ_CALL_LOG / READ_SMS runtime
 * permission chahiye — na mile to empty hint exact fix batata hai). WS connect
 * hote hi child turant sync kick karta hai, isliye connect ke ~1 min me data
 * aa jata hai.
 */
@Composable
fun ReportsScreen(vm: MainViewModel, initialTab: Int = 0) {
    val children by vm.children.collectAsState()
    val activeId by vm.activeChildId.collectAsState()
    val cid = (children.firstOrNull { it.id == activeId } ?: children.firstOrNull())?.id

    var tab by remember { mutableStateOf(initialTab.coerceIn(0, 1)) }
    val tabs = listOf("Call log", "SMS")

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        when (tab) {
            0 -> CallLogTab(vm, cid)
            else -> SmsTab(vm, cid)
        }
    }
}

@Composable
private fun CallLogTab(vm: MainViewModel, cid: String?) {
    val calls by vm.calls.collectAsState()
    val loading by vm.callsLoading.collectAsState()

    LaunchedEffect(cid) { if (cid != null) vm.loadCalls(cid) }

    Column(Modifier.fillMaxSize()) {
        ListHeader(
            count = calls.size,
            label = "Calls synced",
            onRefresh = { if (cid != null) vm.loadCalls(cid) }
        )
        if (loading) {
            LoadingRow("Loading…")
            return@Column
        }
        if (calls.isEmpty()) {
            EmptyHint(
                "Abhi koi call sync nahi hui.\n\n" +
                    "Bachche ke phone me Setup → Step 7 \"Calls & SMS\" → Grant karo " +
                    "(permission milte hi agle sync par purana log bhi aa jayega)."
            )
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(calls) { c -> CallRow(c) }
        }
    }
}

@Composable
private fun SmsTab(vm: MainViewModel, cid: String?) {
    val sms by vm.sms.collectAsState()
    val loading by vm.smsLoading.collectAsState()

    LaunchedEffect(cid) { if (cid != null) vm.loadSms(cid) }

    Column(Modifier.fillMaxSize()) {
        ListHeader(
            count = sms.size,
            label = "SMS synced",
            onRefresh = { if (cid != null) vm.loadSms(cid) }
        )
        if (loading) {
            LoadingRow("Loading…")
            return@Column
        }
        if (sms.isEmpty()) {
            EmptyHint(
                "Abhi koi SMS sync nahi hua.\n\n" +
                    "Bachche ke phone me Setup → Step 7 \"Calls & SMS\" → Grant karo " +
                    "(permission milte hi agle sync par purane message bhi aa jayenge)."
            )
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(sms) { s -> SmsRow(s) }
        }
    }
}

@Composable
private fun ListHeader(count: Int, label: String, onRefresh: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$label ($count)",
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        IconButton(onClick = onRefresh) {
            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
        }
    }
}

@Composable
private fun LoadingRow(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CallRow(c: com.familyguard.parent.data.CallItem) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(callIcon(c.type), contentDescription = c.type, tint = callColor(c.type))
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 10.dp)
            ) {
                Text(
                    if (c.name.isNotBlank()) c.name else c.number.ifBlank { "Unknown" },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    c.number.ifBlank { "—" } + " · " + prettyType(c.type) +
                        " · " + (if (c.duration > 0) "${c.duration / 60} min " else "") + timeAgo(c.date),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SmsRow(s: com.familyguard.parent.data.SmsItem) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Sms,
                    contentDescription = "sms",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    if (s.name.isNotBlank() && s.name != s.number) s.name else s.number.ifBlank { "Unknown" },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(
                        if (s.type == "out") "Sent" else "Recv",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            if (s.body.isNotBlank()) {
                Text(
                    s.body,
                    fontSize = 13.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                timeAgo(s.date),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

private fun prettyType(type: String) = when (type) {
    "in" -> "Incoming"
    "out" -> "Outgoing"
    "missed" -> "Missed"
    else -> "Call"
}
