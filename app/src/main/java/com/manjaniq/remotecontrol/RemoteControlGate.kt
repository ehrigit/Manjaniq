package com.manjaniq.remotecontrol

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.manjaniq.R
import com.manjaniq.RemoteControlHost

internal object RemoteControlTags {
    const val CHECKING = "remote_control_checking"
    const val RESTRICTED = "remote_control_restricted"
    const val UPDATE = "remote_control_update"
    const val PASSWORD = "remote_control_password"
    const val PASSWORD_INPUT = "remote_control_password_input"
    const val PASSWORD_SUBMIT = "remote_control_password_submit"
    const val ANNOUNCEMENT = "remote_control_announcement"
    const val ANNOUNCEMENT_ACK = "remote_control_announcement_ack"
    const val RETRY = "remote_control_retry"
    const val OPEN_UPDATE = "remote_control_open_update"
}

/**
 * The only UI integration point. Existing navigation/screens are not composed
 * until this gate allows access, so a dialog cannot uncover a live Start button.
 * The application-owned session survives rotation and language recreation.
 */
@Composable
fun RemoteControlGate(content: @Composable () -> Unit) {
    val application = LocalContext.current.applicationContext as RemoteControlHost
    val session = application.remoteControl
    LaunchedEffect(session) { session.start() }
    val state by session.state.collectAsStateWithLifecycle()

    when (val current = state) {
        RemoteControlState.Checking -> CheckingScreen()
        RemoteControlState.Allowed -> content()
        else -> {
            // A neutral background, not the main UI, sits behind the dialog.
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
            Dialog(
                onDismissRequest = {},
                properties = DialogProperties(
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                    usePlatformDefaultWidth = false,
                ),
            ) {
                GateSurface {
                    when (current) {
                        RemoteControlState.Restricted -> RestrictedContent(session)
                        is RemoteControlState.UpdateRequired -> UpdateContent(current, session)
                        is RemoteControlState.PasswordRequired -> PasswordContent(current, session)
                        is RemoteControlState.Announcement -> AnnouncementContent(current, session)
                        RemoteControlState.Checking, RemoteControlState.Allowed -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckingScreen() {
    GateSurface {
        Column(
            modifier = Modifier.fillMaxWidth().testTag(RemoteControlTags.CHECKING),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.remote_control_checking),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun GateSurface(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_manjaniq_logo),
                    contentDescription = stringResource(R.string.cd_manjaniq_logo),
                    modifier = Modifier.size(72.dp),
                )
                content()
            }
        }
    }
}

@Composable
private fun RestrictedContent(session: RemoteControlSession) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(RemoteControlTags.RESTRICTED),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        GateText(R.string.remote_control_restricted_title, R.string.remote_control_restricted_message)
        TextButton(onClick = session::retry, modifier = Modifier.testTag(RemoteControlTags.RETRY)) {
            Text(stringResource(R.string.remote_control_retry))
        }
    }
}

@Composable
private fun UpdateContent(state: RemoteControlState.UpdateRequired, session: RemoteControlSession) {
    val context = LocalContext.current
    var cannotOpenUpdate by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().testTag(RemoteControlTags.UPDATE),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        GateText(
            R.string.remote_control_update_title,
            R.string.remote_control_update_message,
            state.latestVersionCode.toString(),
        )
        Button(
            onClick = {
                // This repository distributes its APK through GitHub Releases;
                // do not assume an unpublished Play Store listing exists. The
                // URL is fixed, never supplied by the remote JSON response.
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UPDATE_DOWNLOAD_URL)))
                    cannotOpenUpdate = false
                } catch (_: ActivityNotFoundException) {
                    cannotOpenUpdate = true
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag(RemoteControlTags.OPEN_UPDATE),
        ) {
            Text(stringResource(R.string.remote_control_open_update))
        }
        if (cannotOpenUpdate) {
            Text(
                text = stringResource(R.string.remote_control_update_unavailable),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        TextButton(onClick = session::retry, modifier = Modifier.testTag(RemoteControlTags.RETRY)) {
            Text(stringResource(R.string.remote_control_retry))
        }
    }
}

@Composable
private fun PasswordContent(state: RemoteControlState.PasswordRequired, session: RemoteControlSession) {
    // Deliberately remember, not rememberSaveable: never put the password in
    // Activity saved state. A verified session survives ordinary recreation.
    var password by remember { mutableStateOf("") }
    val submit = {
        if (password.isNotEmpty() && session.verifyPassword(password)) password = ""
    }
    Column(
        modifier = Modifier.fillMaxWidth().testTag(RemoteControlTags.PASSWORD),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        GateText(R.string.remote_control_password_title, R.string.remote_control_password_message)
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.remote_control_password_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            isError = state.incorrectAttempt,
            supportingText = if (state.incorrectAttempt) {
                { Text(stringResource(R.string.remote_control_password_incorrect)) }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth().testTag(RemoteControlTags.PASSWORD_INPUT),
        )
        Button(
            onClick = submit,
            enabled = password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag(RemoteControlTags.PASSWORD_SUBMIT),
        ) {
            Text(stringResource(R.string.remote_control_unlock))
        }
    }
}

@Composable
private fun AnnouncementContent(state: RemoteControlState.Announcement, session: RemoteControlSession) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(RemoteControlTags.ANNOUNCEMENT),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.remote_control_announcement_title), style = MaterialTheme.typography.headlineSmall)
        Text(state.message, style = MaterialTheme.typography.bodyLarge)
        Button(
            onClick = session::acknowledgeAnnouncement,
            modifier = Modifier.fillMaxWidth().testTag(RemoteControlTags.ANNOUNCEMENT_ACK),
        ) {
            Text(stringResource(R.string.remote_control_continue))
        }
    }
}

@Composable
private fun GateText(title: Int, message: Int, vararg args: String) {
    Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(message, *args), style = MaterialTheme.typography.bodyLarge)
}

private const val UPDATE_DOWNLOAD_URL = "https://github.com/ehsanbagherigit/Manjaniq/releases/latest"
