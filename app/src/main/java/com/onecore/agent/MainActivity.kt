package com.onecore.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

val OneUiBlack = Color(0xFF000000)
val OneUiSurface = Color(0xFF17171A)
val OneUiCardSubtle = Color(0xFF222227)
val OneUiBlue = Color(0xFF0381FE)
val OneUiGreen = Color(0xFF2ECC71)
val OneUiOrange = Color(0xFFF39C12)
val OneUiTextPrimary = Color(0xFFFAFAFA)
val OneUiTextSecondary = Color(0xFF9E9EA6)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bridge = GeminiSystemBridge(this)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = OneUiBlack,
                    surface = OneUiSurface,
                    primary = OneUiBlue
                )
            ) {
                OneUiAgentScreen(context = this, bridge = bridge)
            }
        }
    }
}

@Composable
fun OneUiAgentScreen(context: Context, bridge: GeminiSystemBridge) {
    val prefs = remember { context.getSharedPreferences("onecore_prefs", Context.MODE_PRIVATE) }
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var showKeyDialog by remember { mutableStateOf(apiKey.isEmpty()) }
    var prompt by remember { mutableStateOf("") }
    var isRunning by remember { mutableStateOf(false) }
    val logs = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()

    val isAccessibilityActive = AgentAccessibilityService.instance != null
    val hasSecureSettings = context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OneUiBlack)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "OneCore AI",
                    color = OneUiTextPrimary,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Passerelle Système • One UI",
                    color = OneUiTextSecondary,
                    fontSize = 14.sp
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(OneUiSurface)
                .padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Pont Accessibilité UI", color = OneUiTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                StatusPill(
                    active = isAccessibilityActive,
                    activeText = "Actif",
                    inactiveText = "Activer",
                    onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                )
            }
            Divider(color = OneUiCardSubtle, modifier = Modifier.padding(vertical = 12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Tables Secure / Global", color = OneUiTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                StatusPill(
                    active = hasSecureSettings,
                    activeText = "Débloqué",
                    inactiveText = "Via ADB/Root",
                    onClick = {
                        logs.add("💡 Pour débloquer sans Root, tape dans Termux/ADB :\npm grant com.onecore.agent android.permission.WRITE_SECURE_SETTINGS")
                    }
                )
            }
            Divider(color = OneUiCardSubtle, modifier = Modifier.padding(vertical = 12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Clé API Gemini", color = OneUiTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                StatusPill(
                    active = apiKey.isNotEmpty(),
                    activeText = "Configurée",
                    inactiveText = "Ajouter",
                    onClick = { showKeyDialog = true }
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(OneUiSurface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (logs.isEmpty()) {
                item {
                    Text(
                        "En attente d'instructions système...\nExemples :\n• \"Passe les animations à 0.5x et active le confort visuel\"\n• \"Active le mode traitement amélioré de Samsung\"\n• \"Ouvre les paramètres et va cliquer sur Batterie\"",
                        color = OneUiTextSecondary,
                        fontSize = 14.sp
                    )
                }
            }
            items(logs) { log ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(OneUiCardSubtle)
                        .padding(12.dp)
                ) {
                    Text(text = log, color = OneUiTextPrimary, fontSize = 14.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(OneUiSurface)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = prompt,
                onValueChange = { prompt = it },
                placeholder = { Text("Donne un ordre système...", color = OneUiTextSecondary) },
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedTextColor = OneUiTextPrimary,
                    unfocusedTextColor = OneUiTextPrimary,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
            Button(
                onClick = {
                    if (prompt.isNotBlank() && !isRunning) {
                        val currentPrompt = prompt
                        prompt = ""
                        logs.add("🗣️ Toi : $currentPrompt")
                        isRunning = true
                        scope.launch {
                            val reply = bridge.processCommand(apiKey, currentPrompt) { stepLog ->
                                logs.add(stepLog)
                            }
                            logs.add("🤖 OneCore : $reply")
                            isRunning = false
                        }
                    }
                },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = OneUiBlue)
            ) {
                Text(if (isRunning) "..." else "Go", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showKeyDialog) {
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            containerColor = OneUiSurface,
            title = { Text("Clé API Google AI Studio", color = OneUiTextPrimary) },
            text = {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    placeholder = { Text("AIzaSy...", color = OneUiTextSecondary) },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        prefs.edit().putString("api_key", apiKey.trim()).apply()
                        showKeyDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = OneUiBlue)
                ) {
                    Text("Enregistrer")
                }
            }
        )
    }
}

@Composable
fun StatusPill(active: Boolean, activeText: String, inactiveText: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) OneUiGreen.copy(alpha = 0.2f) else OneUiOrange.copy(alpha = 0.2f))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = if (active) activeText else inactiveText,
            color = if (active) OneUiGreen else OneUiOrange,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
