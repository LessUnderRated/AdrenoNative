package app.gamenative.ui.s26

import android.os.Environment
import android.os.storage.StorageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import app.gamenative.profile.S26Profile
import app.gamenative.utils.StorageUtils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S26SettingsScreen(onBack: () -> Unit, onSignOut: () -> Unit) {
    var confirmSignOut by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Section("Steam")
            ListItem(
                headlineContent = { Text(PrefManager.username.ifBlank { "Signed in" }) },
                supportingContent = { Text("Sign out of Steam on this device") },
                modifier = Modifier.clickable { confirmSignOut = true },
            )
            HorizontalDivider()

            Section("Downloads")
            StorageSetting()
            var wifiOnly by remember { mutableStateOf(PrefManager.downloadOnWifiOnly) }
            ListItem(
                headlineContent = { Text("Download on Wi-Fi only") },
                trailingContent = {
                    Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it; PrefManager.downloadOnWifiOnly = it })
                },
            )
            HorizontalDivider()

            Section("Compatibility stack")
            Info("GPU driver", S26Profile.DRIVER)
            Info("Proton", S26Profile.PROTON)
            Info("DXVK / VKD3D-Proton", "${S26Profile.DXVK} / ${S26Profile.VKD3D}")
            Info("FEX", "${S26Profile.FEXCORE_VERSION} (${S26Profile.FEXCORE_PRESET.lowercase()})")
            Info("Display", "${S26Profile.REFRESH_RATE_HZ.toInt()} Hz")
            HorizontalDivider()

            Section("About")
            Info("AdrenoNative", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            Info("License", "GPL-3.0 · based on GameNative")
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("Installed games stay on the device.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; onSignOut() }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun Info(label: String, value: String) {
    ListItem(headlineContent = { Text(label) }, supportingContent = { Text(value) })
}

/** Internal storage or one of the mounted SD/USB volumes; applies to new installs. */
@Composable
private fun StorageSetting() {
    val context = LocalContext.current
    val sm = remember { context.getSystemService(StorageManager::class.java) }
    val volumes by produceState(initialValue = emptyList<File>(), context) {
        value = withContext(Dispatchers.IO) {
            StorageUtils.getAllExternalFilesDirs(context)
                .filter { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
                .filter { StorageUtils.isExternalInstallTarget(sm, it) }
        }
    }
    var external by remember { mutableStateOf(PrefManager.useExternalStorage) }
    var path by remember { mutableStateOf(PrefManager.externalStoragePath) }

    fun choose(dir: File?) {
        external = dir != null
        PrefManager.useExternalStorage = external
        if (dir != null) {
            path = StorageUtils.preferredInstallRoot(dir)
            PrefManager.externalStoragePath = path
        }
    }

    ListItem(
        headlineContent = { Text("Internal storage") },
        leadingContent = { RadioButton(selected = !external, onClick = { choose(null) }) },
        modifier = Modifier.clickable { choose(null) },
    )
    volumes.forEach { dir ->
        val root = StorageUtils.preferredInstallRoot(dir)
        val label = sm?.getStorageVolume(dir)?.getDescription(context) ?: "External storage"
        ListItem(
            headlineContent = { Text(label) },
            supportingContent = { Text(root) },
            leadingContent = { RadioButton(selected = external && path == root, onClick = { choose(dir) }) },
            modifier = Modifier.clickable { choose(dir) },
        )
    }
}
