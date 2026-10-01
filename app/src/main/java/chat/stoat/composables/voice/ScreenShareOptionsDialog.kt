package chat.stoat.composables.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import chat.stoat.R
import chat.stoat.voice.ScreenShareQuality
import chat.stoat.voice.ScreenShareSettings

/** Lets the user pick screen-share quality and whether to include phone audio. */
@Composable
fun ScreenShareOptionsDialog(
    onDismiss: () -> Unit,
    onStart: (quality: ScreenShareQuality, shareAudio: Boolean) -> Unit,
) {
    val context = LocalContext.current
    var quality by remember { mutableStateOf(ScreenShareSettings.quality(context)) }
    val audioAvailable = remember { ScreenShareSettings.canShareAudio(context) }
    var shareAudio by remember { mutableStateOf(audioAvailable && ScreenShareSettings.shareAudio(context)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.screenshare_options_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.selectableGroup()) {
                    ScreenShareQuality.entries.forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = option == quality,
                                    onClick = { quality = option },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 6.dp),
                        ) {
                            RadioButton(selected = option == quality, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(stringResource(option.label), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(option.description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.screenshare_share_audio), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(
                                if (audioAvailable) R.string.screenshare_share_audio_desc
                                else R.string.screenshare_share_audio_unavailable,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = shareAudio,
                        onCheckedChange = { shareAudio = it },
                        enabled = audioAvailable,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                ScreenShareSettings.save(context, quality, shareAudio)
                onStart(quality, shareAudio)
            }) {
                Text(stringResource(R.string.screenshare_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
