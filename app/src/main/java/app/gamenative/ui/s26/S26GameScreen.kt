package app.gamenative.ui.s26

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.gamenative.PluviaApp
import app.gamenative.data.GameSource
import app.gamenative.data.SteamApp
import app.gamenative.events.AndroidEvent
import app.gamenative.profile.S26Quirk
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import app.gamenative.utils.ContainerUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S26GameScreen(
    game: SteamApp,
    isOffline: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberInstallState(game.id)
    var confirmUninstall by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(game.name, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GameHeader(game, Modifier.widthIn(max = 720.dp).fillMaxWidth().aspectRatio(460f / 215f))
            Gap(16)

            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                when {
                    state.installed -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onPlay, modifier = Modifier.weight(1f)) { Text("Play") }
                        OutlinedButton(onClick = { confirmUninstall = true }, enabled = !busy) { Text("Uninstall") }
                    }
                    state.downloading || state.paused -> {
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                        Gap(6)
                        Text(
                            buildString {
                                append(if (state.paused) "Paused" else "Downloading")
                                append(" · ${(state.progress * 100).toInt()}%")
                                if (state.bytesTotal > 0) {
                                    append(" · ${Formatter.formatShortFileSize(context, state.bytesDone)} / ")
                                    append(Formatter.formatShortFileSize(context, state.bytesTotal))
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Gap(8)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (state.downloading) {
                                OutlinedButton(onClick = { SteamInstall.pause(game.id) }) { Text("Pause") }
                            } else {
                                Button(onClick = { scope.launch(Dispatchers.IO) { SteamInstall.resume(game.id) } }, enabled = !isOffline) {
                                    Text("Resume")
                                }
                            }
                            OutlinedButton(onClick = { confirmUninstall = true }, enabled = !busy) { Text("Cancel") }
                        }
                    }
                    else -> Button(
                        onClick = { scope.launch(Dispatchers.IO) { SteamInstall.install(game.id) } },
                        enabled = !isOffline,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (isOffline) "Connect to Steam to install" else "Install") }
                }

                if (state.installed) {
                    Gap(24)
                    Text("Troubleshoot", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Only if this game misbehaves. Everything else is tuned for the S26.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    QuirkSwitch(game, S26Quirk.SYSMEM, "Fix graphics glitches", "Slower, but avoids tiling artifacts")
                    QuirkSwitch(game, S26Quirk.DECK_EMU, "Game won't start", "Report the GPU as a Steam Deck")
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("Check for update") },
                        supportingContent = { Text("Download the latest build of this game") },
                        trailingContent = {
                            TextButton(onClick = { scope.launch(Dispatchers.IO) { SteamInstall.update(game.id) } }, enabled = !isOffline) {
                                Text("Update")
                            }
                        },
                    )
                }
            }
        }
    }

    if (confirmUninstall) {
        val removingDownload = !state.installed
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = { Text(if (removingDownload) "Cancel download?" else "Uninstall ${game.name}?") },
            text = { Text(if (removingDownload) "Downloaded files are deleted." else "Game files are deleted. Steam Cloud saves are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUninstall = false
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            SteamService.getAppDownloadInfo(game.id)?.cancel()
                            SteamService.deleteApp(game.id)
                            DownloadService.invalidateCache()
                        }
                        ContainerUtils.deleteContainer(context, "STEAM_${game.id}")
                        PluviaApp.events.emit(AndroidEvent.LibraryInstallStatusChanged(game.id, GameSource.STEAM))
                        busy = false
                    }
                }) { Text(if (removingDownload) "Delete" else "Uninstall") }
            },
            dismissButton = { TextButton(onClick = { confirmUninstall = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun QuirkSwitch(game: SteamApp, quirk: S26Quirk, title: String, subtitle: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember(game.id, quirk) { mutableStateOf(false) }
    LaunchedEffect(game.id, quirk) {
        enabled = withContext(Dispatchers.IO) {
            S26Quirk.isEnabled(ContainerUtils.getOrCreateContainer(context, "STEAM_${game.id}"), quirk)
        }
    }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Switch(checked = enabled, onCheckedChange = { on ->
                enabled = on
                scope.launch(Dispatchers.IO) {
                    S26Quirk.set(ContainerUtils.getOrCreateContainer(context, "STEAM_${game.id}"), quirk, on)
                }
            })
        },
    )
}
