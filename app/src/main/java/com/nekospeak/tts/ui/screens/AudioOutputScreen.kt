package com.nekospeak.tts.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nekospeak.tts.audio.NekoAudioStream
import com.nekospeak.tts.data.PrefsManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioOutputScreen() {
    val context = LocalContext.current
    val prefs = remember { PrefsManager(context) }
    var selectedStream by remember { mutableStateOf(NekoAudioStream.fromPref(prefs.audioStream)) }
    var preferSpeakerForCall by remember { mutableStateOf(prefs.preferSpeakerForCallStream) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Audio") })
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            AudioSettingsSection(title = "Default audio stream") {
                Text(
                    text = "Choose which Android volume stream NekoSpeak uses for in-app preview speech.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                NekoAudioStream.values().forEach { stream ->
                    AudioStreamRow(
                        stream = stream,
                        selected = selectedStream == stream,
                        onSelected = {
                            selectedStream = stream
                            prefs.audioStream = stream.prefValue
                        }
                    )
                }
            }

            HorizontalDivider()

            AudioSettingsSection(title = "Speaker routing") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            preferSpeakerForCall = !preferSpeakerForCall
                            prefs.preferSpeakerForCallStream = preferSpeakerForCall
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Checkbox(
                        checked = preferSpeakerForCall,
                        onCheckedChange = { checked ->
                            preferSpeakerForCall = checked
                            prefs.preferSpeakerForCallStream = checked
                        }
                    )
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text("Prefer loudspeaker for call volume", fontWeight = FontWeight.Medium)
                        Text(
                            text = "Only used when Call volume is selected. Android may still route Bluetooth or wired devices first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (preferSpeakerForCall && selectedStream != NekoAudioStream.CALL) {
                    Text(
                        text = "Speaker preference is inactive until Call volume is selected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }

            HorizontalDivider()

            Surface(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                tonalElevation = 1.dp,
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Important", fontWeight = FontWeight.Bold)
                    Text(
                        text = "This controls NekoSpeak's own preview playback. Other apps can still override the stream when they call Android text-to-speech.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioSettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        content()
    }
}

@Composable
private fun AudioStreamRow(
    stream: NekoAudioStream,
    selected: Boolean,
    onSelected: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelected)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        RadioButton(selected = selected, onClick = onSelected)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(stream.title, fontWeight = FontWeight.Medium)
            Text(
                text = stream.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
