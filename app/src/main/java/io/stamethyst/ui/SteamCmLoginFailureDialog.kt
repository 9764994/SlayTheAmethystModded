package io.stamethyst.ui

import io.stamethyst.ui.LauncherAlertDialog as AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.stamethyst.R
import io.stamethyst.backend.steam.SteamCmLoginFailureNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SteamCmLoginFailureDialog(onLogin: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val notices = remember(context) { SteamCmLoginFailureNotice.observe(context) }
    val notice by notices.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    notice?.let { failure ->
        var dismissing by remember(failure) { mutableStateOf(false) }
        fun dismiss(afterDismiss: () -> Unit = {}) {
            if (dismissing) return
            dismissing = true
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching { SteamCmLoginFailureNotice.dismiss(context, failure) }
                }
                afterDismiss()
            }
        }
        if (!dismissing) AlertDialog(
            onDismissRequest = { dismiss() },
            title = { Text(stringResource(R.string.steam_cm_login_failed_title)) },
            text = { Text(stringResource(R.string.steam_cm_login_failed_message)) },
            confirmButton = {
                TextButton(onClick = { dismiss(onLogin) }) {
                    Text(stringResource(R.string.settings_steam_cloud_login_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { dismiss() }) {
                    Text(stringResource(R.string.update_dialog_action_later))
                }
            },
        )
    }
}
