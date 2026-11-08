package com.familyguard.parent.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familyguard.parent.MainViewModel
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun RemoteScreen(vm: MainViewModel) {
    val lastResult by vm.lastResult.collectAsState()
    val preview by vm.previewBytes.collectAsState()
    var showLock by remember { mutableStateOf(false) }
    var showMessage by remember { mutableStateOf(false) }
    var previewBmp by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(preview) {
        val bytes = preview ?: return@LaunchedEffect
        val bmp = withContext(Dispatchers.Default) {
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        }
        if (bmp != null) previewBmp = bmp.asImageBitmap()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Capture & control", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    vm.sendCommand(
                        "camera",
                        JsonObject().apply { addProperty("facing", "front") }
                    )
                },
                modifier = Modifier.weight(1f)
            ) { Text("Capture front") }
            Button(
                onClick = {
                    vm.sendCommand(
                        "camera",
                        JsonObject().apply { addProperty("facing", "back") }
                    )
                },
                modifier = Modifier.weight(1f)
            ) { Text("Capture back") }
        }
        Button(
            onClick = { vm.sendCommand("screenshot") },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Screenshot") }
        OutlinedButton(
            onClick = { showLock = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Lock device") }
        OutlinedButton(
            onClick = { showMessage = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Send message") }

        if (lastResult.isNotBlank()) {
            Text(
                "Last result: $lastResult",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        previewBmp?.let { bmp ->
            Text("Last captured media", style = MaterialTheme.typography.titleSmall)
            Image(
                bitmap = bmp,
                contentDescription = "Captured media",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            )
        }
    }

    if (showLock) LockDialog(vm) { showLock = false }
    if (showMessage) MessageDialog(vm) { showMessage = false }
}
