package com.familyguard.parent.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familyguard.parent.R
import com.familyguard.parent.data.Api
import com.familyguard.parent.data.Session
import com.familyguard.parent.data.Store
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(notice: String = "", onLoggedIn: () -> Unit) {
    var server by remember { mutableStateOf(Store.serverUrl) }
    var user by remember { mutableStateOf(Store.username) }
    var pass by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun doAuth(register: Boolean) {
        if (loading) return
        scope.launch {
            loading = true
            error = ""
            try {
                Store.serverUrl = server.trim()
                val resp = if (register) Api.register(user.trim(), pass) else Api.login(user.trim(), pass)
                Store.token = resp.token
                Store.username = user.trim()
                Session.armed()
                runCatching { Api.children() }
                onLoggedIn()
            } catch (e: Exception) {
                error = e.message ?: "Something went wrong"
            } finally {
                loading = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_app),
            contentDescription = "FamilyGuard",
            modifier = Modifier.size(96.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text("FamilyGuard", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Self-hosted parental control", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            label = { Text("Server URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        if (notice.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(notice, color = MaterialTheme.colorScheme.tertiary, fontSize = 13.sp)
        }
        if (error.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { doAuth(false) },
                enabled = !loading && user.isNotBlank() && pass.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) { Text("Login") }
            OutlinedButton(
                onClick = { doAuth(true) },
                enabled = !loading && user.isNotBlank() && pass.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) { Text("Register") }
        }
        if (loading) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator()
        }
    }
}
