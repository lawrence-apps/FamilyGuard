package com.familyguard.parent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familyguard.parent.data.Api
import com.familyguard.parent.data.ApkInfo
import kotlinx.coroutines.launch

/**
 * Child app APK upload + share link panel.
 * Upload once (from GitHub Actions artifact or file manager), then share the
 * token-protected install link — opening it on the child phone downloads the APK.
 * Used on the More tab and inside the Dashboard quick-action dialog.
 */
@Composable
fun ApkShareContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apk by remember { mutableStateOf<ApkInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    fun link(): String = Api.baseUrl() + (apk?.url ?: "")

    fun copyLink(url: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("FamilyGuard child app link", url))
        msg = "Link copied ✓"
    }

    fun shareLink(url: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "FamilyGuard — Child App")
            putExtra(
                Intent.EXTRA_TEXT,
                "FamilyGuard child app install karne ke liye ye link child phone par kholo:\n$url"
            )
        }
        context.startActivity(Intent.createChooser(send, "Share install link"))
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            msg = ""
            try {
                val name = (uri.lastPathSegment ?: "child.apk").substringAfterLast('/')
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                when {
                    bytes == null -> msg = "File read nahi ho payi"
                    !name.endsWith(".apk", ignoreCase = true) -> msg = "Sirf .apk file select karo (selected: $name)"
                    else -> {
                        apk = Api.uploadApk(bytes, name)
                        msg = "APK uploaded ✓ — ab link share karo"
                    }
                }
            } catch (e: Exception) {
                msg = "Upload failed: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { apk = Api.apkInfo() }
    }

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val info = apk
        if (info == null || !info.uploaded) {
            Text(
                "Child APK ek baar upload karo (GitHub Actions ka familyguard-child-debug-apk ya file manager se). " +
                    "Uske baad link share karo — child phone par kholte hi download + install ho jayega.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                link(),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "${info.fileName} • ${info.size / (1024 * 1024)} MB",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (info == null || !info.uploaded) {
                Button(onClick = { picker.launch("*/*") }, enabled = !busy) {
                    Text(if (busy) "Uploading…" else "Upload APK")
                }
            } else {
                OutlinedButton(onClick = { copyLink(link()) }) { Text("Copy") }
                Button(onClick = { shareLink(link()) }) { Text("Share") }
                OutlinedButton(onClick = { picker.launch("*/*") }, enabled = !busy) { Text("New") }
            }
        }
        if (msg.isNotBlank()) {
            Text(
                msg,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
