package app.gamenative.ui.s26

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gamenative.data.SteamApp
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage

/**
 * AdrenoNative's home: Steam library -> game page -> play, plus a small settings page.
 * Launching goes through the caller's [onPlay], which runs the existing pre-launch pipeline.
 */
@Composable
fun S26Home(
    isOffline: Boolean,
    onPlay: (appId: String) -> Unit,
    onSignOut: () -> Unit,
    onExit: () -> Unit,
    viewModel: S26LibraryViewModel = hiltViewModel(),
) {
    var openGameId by rememberSaveable { mutableStateOf<Int?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val games by viewModel.games.collectAsStateWithLifecycle()

    val openGame = openGameId?.let { id -> games?.firstOrNull { it.id == id } }
    when {
        showSettings -> {
            BackHandler { showSettings = false }
            S26SettingsScreen(onBack = { showSettings = false }, onSignOut = onSignOut)
        }
        openGame != null -> {
            BackHandler { openGameId = null }
            S26GameScreen(
                game = openGame,
                isOffline = isOffline,
                onBack = { openGameId = null },
                onPlay = { onPlay("STEAM_${openGame.id}") },
            )
        }
        else -> {
            BackHandler { onExit() }
            LibraryScreen(
                games = games,
                isOffline = isOffline,
                onOpenGame = { openGameId = it.id },
                onOpenSettings = { showSettings = true },
            )
        }
    }
}

private enum class LibraryFilter(val label: String) { ALL("All"), INSTALLED("Installed") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    games: List<SteamApp>?,
    isOffline: Boolean,
    onOpenGame: (SteamApp) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
    var searching by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            placeholder = { Text("Search your games") },
                            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                        )
                    } else {
                        Column {
                            Text("Library")
                            if (isOffline) {
                                Text("Offline", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibraryFilter.entries.forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                }
            }
            when {
                games == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> {
                    val shown = remember(games, query, filter) {
                        games.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
                            .filter { filter == LibraryFilter.ALL || SteamInstall.read(it.id).let { s -> s.installed || s.downloading || s.paused } }
                    }
                    if (shown.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                if (games.isEmpty()) "Your Steam library is syncing…" else "No games match",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 220.dp),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(shown, key = { it.id }) { game -> GameTile(game, onClick = { onOpenGame(game) }) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GameTile(game: SteamApp, onClick: () -> Unit) {
    val state = rememberInstallState(game.id)
    Column(
        Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
    ) {
        Box {
            GameHeader(game, Modifier.fillMaxWidth().aspectRatio(460f / 215f))
            val badge = when {
                state.downloading -> "${(state.progress * 100).toInt()}%"
                state.paused -> "Paused"
                state.installed -> "Installed"
                else -> null
            }
            if (badge != null) {
                Surface(
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                ) {
                    Text(badge, style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }
        if (state.downloading || state.paused) {
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().height(3.dp))
        }
        Text(
            game.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        )
    }
}

@Composable
internal fun GameHeader(game: SteamApp, modifier: Modifier = Modifier) {
    CoilImage(
        imageModel = { game.getHeaderImageUrl() ?: game.headerUrl },
        imageOptions = ImageOptions(contentScale = ContentScale.Crop),
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        failure = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(game.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(8.dp))
            }
        },
    )
}

@Composable
internal fun Gap(dp: Int) = Spacer(Modifier.height(dp.dp).width(dp.dp))
