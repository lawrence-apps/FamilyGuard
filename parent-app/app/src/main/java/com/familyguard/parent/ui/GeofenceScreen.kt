package com.familyguard.parent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.familyguard.parent.MainViewModel
import com.familyguard.parent.data.Geofence
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Polygon
import kotlinx.coroutines.launch

@Composable
fun GeofenceScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val children by vm.children.collectAsState()
    val activeId by vm.activeChildId.collectAsState()
    val cid = (children.firstOrNull { it.id == activeId } ?: children.firstOrNull())?.id

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(14.0)
        }
    }

    var fences by remember { mutableStateOf<List<Geofence>>(emptyList()) }
    var tapPoint by remember { mutableStateOf<GeoPoint?>(null) }
    val drawn = remember { mutableListOf<Polygon>() }
    val scope = rememberCoroutineScope()

    val receiver = remember {
        object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                if (p != null) tapPoint = p
                return true
            }

            override fun longPressHelper(p: GeoPoint?): Boolean = false
        }
    }

    LaunchedEffect(Unit) {
        mapView.overlays.add(MapEventsOverlay(receiver))
    }

    LaunchedEffect(cid) {
        if (cid == null) return@LaunchedEffect
        fences = vm.fences(cid)
    }

    LaunchedEffect(fences) {
        drawn.forEach { mapView.overlays.remove(it) }
        drawn.clear()
        fences.forEach { f ->
            val poly = Polygon(mapView)
            poly.points = Polygon.pointsAsCircle(GeoPoint(f.lat, f.lng), f.radius)
            poly.fillPaint.color = 0x3332563E
            poly.outlinePaint.color = 0xFF32563E.toInt()
            poly.outlinePaint.strokeWidth = 3f
            poly.title = f.name
            mapView.overlays.add(poly)
            drawn.add(poly)
        }
        mapView.invalidate()
    }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    Column(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )
        Text(
            "Tap the map to place a new fence at that point.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .height(190.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (fences.isEmpty()) {
                item {
                    Text(
                        "No geofences yet.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(fences, key = { it.id }) { f ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                f.name,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                "${f.radius.toInt()} m",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (f.type == "in") {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.errorContainer
                            }
                        ) {
                            Text(
                                if (f.type == "in") "Entry alert" else "Exit alert",
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                color = if (f.type == "in") {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onErrorContainer
                                }
                            )
                        }
                        IconButton(onClick = {
                            val c = cid
                            if (c != null) {
                                scope.launch {
                                    vm.deleteFence(c, f.id)
                                    fences = vm.fences(c)
                                }
                            }
                        }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete ${f.name}")
                        }
                    }
                }
            }
        }
    }

    tapPoint?.let { pt ->
        FenceDialog(
            onSave = { name, radius, type ->
                val c = cid
                if (c != null) {
                    scope.launch {
                        val fence = Geofence(
                            name = name,
                            lat = pt.latitude,
                            lng = pt.longitude,
                            radius = radius,
                            type = type
                        )
                        vm.addFence(c, fence)
                        fences = vm.fences(c)
                    }
                }
                tapPoint = null
            },
            onDismiss = { tapPoint = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FenceDialog(
    onSave: (name: String, radius: Double, type: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf(150f) }
    var type by remember { mutableStateOf("in") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add geofence") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Center is set from the tapped map point.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (e.g. Home)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Radius: ${radius.toInt()} m", fontSize = 13.sp)
                Slider(
                    value = radius,
                    onValueChange = { radius = it },
                    valueRange = 50f..1000f
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == "in",
                        onClick = { type = "in" },
                        label = { Text("Entry alert") }
                    )
                    FilterChip(
                        selected = type == "out",
                        onClick = { type = "out" },
                        label = { Text("Exit alert") }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(name.trim(), radius.toDouble(), type) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
