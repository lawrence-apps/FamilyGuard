package com.familyguard.parent.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familyguard.parent.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MirrorScreen(vm: MainViewModel) {
    val frame by vm.mirrorFrame.collectAsState()
    val running by vm.mirrorRunning.collectAsState()
    val lastFrameAt by vm.mirrorLastFrameAt.collectAsState()
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var now by remember { mutableStateOf(0L) }

    // Leaving this screen automatically stops mirroring AND ends the child's
    // projection session — the cast icon on the child phone disappears the moment
    // you navigate away (or close the app) instead of staying on forever.
    DisposableEffect(Unit) {
        onDispose { vm.stopMirror() }
    }

    // 1 s ticker so the stale-frames hint reacts without a new frame arriving.
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }

    LaunchedEffect(frame) {
        val bytes = frame ?: return@LaunchedEffect
        val bmp = withContext(Dispatchers.Default) {
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        }
        if (bmp != null) bitmap = bmp.asImageBitmap()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!running) {
                Button(onClick = { vm.startMirror() }) { Text("Start mirroring") }
                Text("Not streaming", color = Color(0xFF9CA3AF), fontSize = 13.sp)
            } else {
                Button(
                    onClick = { vm.stopMirror() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Stop") }
                Text(
                    "LIVE",
                    color = Color(0xFFF87171),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        // Honest status line: the child phone is the truth source. While the consent
        // dialog auto-confirms (or the child screen is off) frames legitimately take
        // a few seconds — say so instead of leaving the parent guessing.
        if (running && now - lastFrameAt > 5_000L) {
            Text(
                "Child par consent dialog auto-confirm ho raha hai… (screen off ho to frames nahi aate — child phone ka screen on karna padega)",
                color = Color(0xFFFBBF24),
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
        val bmp = bitmap
        if (bmp == null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Waiting for frames…",
                    color = Color(0xFF9CA3AF),
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Image(
                bitmap = bmp,
                contentDescription = "Mirrored screen",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
        Text(
            "Ek baar setup me consent khud confirm ho gaya tha — ab Start dabate hi screen turant aati hai, KOI POPUP NAHI (reboot ke baad bhi session khud wapas aa jata hai). Screen se nikalte hi streaming khud ruk jati hai. Cast sign child phone par ready-state me dikhta hai — ye Android ka privacy rule hai.",
            color = Color(0xFF9CA3AF),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        )
    }
}
