package app.gamenative.ui.s26

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.gamenative.service.SteamService
import app.gamenative.service.download.SteamDownloadMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class InstallState(
    val installed: Boolean = false,
    val downloading: Boolean = false,
    /** Download progress 0..1 while [downloading] or paused. */
    val progress: Float = 0f,
    val paused: Boolean = false,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
)

object SteamInstall {
    fun read(appId: Int): InstallState {
        val info = SteamService.getAppDownloadInfo(appId)
        val active = info?.isActive() == true
        val partial = !active && SteamService.hasPartialDownload(appId)
        val (done, total) = info?.getBytesProgress() ?: (0L to 0L)
        return InstallState(
            installed = SteamService.isAppInstalled(appId) && !active && !partial,
            downloading = active,
            progress = info?.getProgress() ?: 0f,
            paused = partial,
            bytesDone = done,
            bytesTotal = total,
        )
    }

    /** Fresh install of the public branch with every owned DLC that has depots. */
    fun install(appId: Int) {
        val dlc = SteamService.getDownloadableDlcAppsOf(appId).orEmpty().map { it.id }
        SteamService.downloadApp(appId, dlc, branch = "public", mode = SteamDownloadMode.INSTALL)
    }

    fun pause(appId: Int) {
        SteamService.getAppDownloadInfo(appId)?.cancel()
    }

    fun resume(appId: Int) {
        SteamService.downloadApp(appId, SteamService.resumeModeFor(appId))
    }

    fun update(appId: Int) {
        SteamService.downloadApp(appId, SteamDownloadMode.UPDATE)
    }
}

/** Polls the download/install state of [appId] twice a second while composed. */
@Composable
fun rememberInstallState(appId: Int): InstallState {
    var state by remember(appId) { mutableStateOf(SteamInstall.read(appId)) }
    LaunchedEffect(appId) {
        while (true) {
            state = withContext(Dispatchers.IO) { SteamInstall.read(appId) }
            delay(500)
        }
    }
    return state
}
