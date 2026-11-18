package io.agents.pokeclaw.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.agents.pokeclaw.R

@Composable
fun AccessibilityWelcomeDialog(
    colors: PokeclawColors,
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(shape = RoundedCornerShape(28.dp), color = colors.surface) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier = Modifier.size(72.dp).background(colors.accent.copy(alpha = 0.14f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.AccessibilityNew, contentDescription = null,
                        modifier = Modifier.size(36.dp), tint = colors.accent)
                }
                Text(stringResource(R.string.accessibility_welcome_badge),
                    color = colors.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.accessibility_welcome_title),
                    color = colors.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center)
                Text(stringResource(R.string.accessibility_welcome_body),
                    color = colors.textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
                Surface(shape = RoundedCornerShape(16.dp), color = colors.background) {
                    Text(stringResource(R.string.accessibility_welcome_steps),
                        modifier = Modifier.padding(16.dp), color = colors.textSecondary, fontSize = 14.sp)
                }
                Button(
                    onClick = onAllow,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.userBubble, contentColor = colors.userText),
                ) {
                    Text(stringResource(R.string.accessibility_welcome_allow), textAlign = TextAlign.Center)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.accessibility_welcome_later), color = colors.textSecondary)
                }
            }
        }
    }
}
