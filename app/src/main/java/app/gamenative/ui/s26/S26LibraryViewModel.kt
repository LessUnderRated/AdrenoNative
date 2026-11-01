package app.gamenative.ui.s26

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.data.SteamApp
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.enums.AppType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The signed-in account's Steam games (no DLC, tools or VR-only titles), sorted by name. */
@HiltViewModel
class S26LibraryViewModel @Inject constructor(
    steamAppDao: SteamAppDao,
) : ViewModel() {
    val games: StateFlow<List<SteamApp>?> = steamAppDao.getAllOwnedApps()
        .map { apps -> apps.filter { it.type == AppType.game && !it.isVrOnly } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
