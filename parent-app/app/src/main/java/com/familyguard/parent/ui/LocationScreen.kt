package com.familyguard.parent.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.familyguard.parent.Graph
import com.familyguard.parent.MainViewModel
import com.familyguard.parent.data.LocPoint
import com.familyguard.parent.data.WsEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.Locale

@Composable
fun LocationScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val children by vm.children.collectAsState()
    val activeId by vm.activeChildId.collectAsState()
    val cid = (children.firstOrNull { it.id == activeId } ?: children.firstOrNull())?.id

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
        }
    }
    val marker = remember {
        Marker(mapView).apply {
            title = "Child location"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
    }
    val line = remember { Polyline(mapView) }

    var points by remember(cid) { mutableStateOf<List<LocPoint>>(emptyList()) }
    var last by remember(cid) { mutableStateOf<LocPoint?>(null) }
    var loading by remember(cid) { mutableStateOf(true) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    LaunchedEffect(cid) {
        if (cid == null) return@LaunchedEffect
        val resp = vm.locations(cid)
        points = resp?.points ?: emptyList()
        last = resp?.last ?: resp?.points?.lastOrNull()
        loading = false
    }

    LaunchedEffect(cid) {
        if (cid == null) return@LaunchedEffect
        Graph.ws.events.collect { ev ->
            if (ev is WsEvent.Location && ev.cid == cid) {
                last = ev.point
                points = (points + ev.point).takeLast(500)
            }
        }
    }

    LaunchedEffect(Unit) {
        mapView.overlays.add(line)
        mapView.overlays.add(marker)
    }

    LaunchedEffect(last, points) {
        val p = last
        if (p != null) {
            val geo = GeoPoint(p.lat, p.lng)
            marker.position = geo
            line.setPoints(points.map { GeoPoint(it.lat, it.lng) })
            line.outlinePaint.strokeWidth = 6f
            line.outlinePaint.color = 0xFF2563EB.toInt()
            mapView.controller.animateTo(geo)
            mapView.invalidate()
        }
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
        Card(
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val p = last
                Column(Modifier.weight(1f)) {
                    when {
                        cid == null -> Text(
                            "No child paired yet — create a profile on the Home tab.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        p == null && loading -> Text(
                            "Loading location…",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        p == null -> Text(
                            "No location data yet.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        else -> {
                            Text(
                                String.format(Locale.US, "%.5f, %.5f", p.lat, p.lng),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "±${p.accuracy.toInt()} m · ${timeAgo(p.t)} · ${p.battery}%",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Button(onClick = {
                    last?.let { mapView.controller.animateTo(GeoPoint(it.lat, it.lng)) }
                }) { Text("Center") }
            }
        }
    }
}
