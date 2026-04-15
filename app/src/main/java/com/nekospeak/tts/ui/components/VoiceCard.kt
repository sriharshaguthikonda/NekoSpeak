package com.nekospeak.tts.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekospeak.tts.ui.viewmodel.Voice
import java.util.Locale

@Composable
fun VoiceCard(
    voice: Voice,
    isSelected: Boolean,
    onVoiceSelected: () -> Unit,
    onDownload: () -> Unit,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isDownloaded = voice.downloadState == com.nekospeak.tts.data.DownloadState.Downloaded
    val isDownloading = voice.downloadState == com.nekospeak.tts.data.DownloadState.Downloading
    val canDelete = voice.isCloned && onDelete != null
    val isCloned = voice.isCloned

    // Visual badge for voice source
    val sourceBadge = when {
        isCloned -> "🧬"
        voice.modelType == "omnivoice" -> "✨"  // Voice design
        voice.modelType.startsWith("piper") -> "🔊"
        voice.modelType == "pocket_v1" -> "🎤"
        else -> ""
    }
    
    // Engine label
    val engineLabel = when {
        isCloned -> "Cloned"
        voice.modelType == "omnivoice" -> "Voice Design"
        voice.modelType == "pocket_v1" -> "Pocket"
        voice.modelType == "kokoro_v1.0" -> "Kokoro"
        voice.modelType == "kitten_nano" -> "Kitten"
        voice.modelType.startsWith("piper") -> "Piper"
        else -> ""
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onVoiceSelected() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) 
                MaterialTheme.colorScheme.primaryContainer 
            else if (isCloned)
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)  // Subtle tint for cloned
            else 
                MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Voice Icon / Initials
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isSelected -> MaterialTheme.colorScheme.primary
                                isCloned -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.secondaryContainer
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = sourceBadge.ifEmpty { voice.name.first().toString() },
                        style = MaterialTheme.typography.titleLarge,
                        color = when {
                            isSelected -> MaterialTheme.colorScheme.onPrimary
                            isCloned -> MaterialTheme.colorScheme.onTertiary
                            else -> MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                // Voice Details
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = voice.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Engine badge
                        if (engineLabel.isNotEmpty()) {
                            SuggestionChip(
                                onClick = { },
                                label = { Text(engineLabel, style = MaterialTheme.typography.labelSmall) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = when {
                                        isCloned -> MaterialTheme.colorScheme.tertiaryContainer
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    }
                                ),
                                modifier = Modifier.height(24.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        // Region Tag
                        SuggestionChip(
                            onClick = { },
                            label = { Text(voice.region) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.height(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when {
                                voice.gender == "Cloned" -> "Cloned Voice"
                                voice.gender.isEmpty() -> voice.language
                                else -> "${voice.gender} · ${voice.language}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                
                // Download Action (show for Piper voices with metadata or celebrity voices without downloads)
                if (!isDownloaded && !isDownloading) {
                     IconButton(onClick = onDownload) {
                         Icon(
                             imageVector = androidx.compose.material.icons.Icons.Default.KeyboardArrowDown,
                             contentDescription = "Download Voice",
                             tint = MaterialTheme.colorScheme.primary
                         )
                     }
                }
                
                // Delete button for cloned voices
                if (canDelete) {
                    IconButton(onClick = { onDelete?.invoke() }) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.Close,
                            contentDescription = "Delete Voice",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            
            if (isDownloading) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Downloading...", 
                        style = MaterialTheme.typography.labelSmall, 
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "${(voice.downloadProgress * 100).toInt()}%", 
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { voice.downloadProgress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
    }
}
