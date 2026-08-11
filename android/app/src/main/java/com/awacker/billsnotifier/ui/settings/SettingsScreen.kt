package com.awacker.billsnotifier.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.awacker.billsnotifier.ui.BillsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: BillsViewModel,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    var hourText by remember { mutableStateOf("") }
    var minuteText by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var recipients by remember { mutableStateOf("") }
    var confirmMirror by remember { mutableStateOf(false) }

    // Seed the fields once settings have loaded from DataStore.
    LaunchedEffect(settings) {
        if (hourText.isEmpty()) hourText = settings.digestHour.toString()
        if (minuteText.isEmpty()) minuteText = "%02d".format(settings.digestMinute)
        if (url.isEmpty()) url = settings.webAppUrl
        if (secret.isEmpty()) secret = settings.sharedSecret
        if (recipients.isEmpty()) recipients = settings.emailRecipients
    }

    if (confirmMirror) {
        MirrorConfirmation(
            onConfirm = {
                confirmMirror = false
                viewModel.setMirrorDevice(true)
            },
            onDismiss = { confirmMirror = false },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("Morning notification") {
                Text(
                    "Fires around this time each day, listing anything due. Days with nothing " +
                        "due stay silent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = hourText,
                        onValueChange = { hourText = it.filter(Char::isDigit).take(2) },
                        label = { Text("Hour") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = minuteText,
                        onValueChange = { minuteText = it.filter(Char::isDigit).take(2) },
                        label = { Text("Minute") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val hour = hourText.toIntOrNull() ?: 7
                            val minute = minuteText.toIntOrNull() ?: 0
                            viewModel.setDigestTime(hour, minute)
                        },
                    ) { Text("Save time") }
                    OutlinedButton(onClick = viewModel::testNotification) {
                        Text("Test notification")
                    }
                }
                Text(
                    "\"Test notification\" only runs the on-device notification — it does not " +
                        "send an email. Use \"Send test email\" below for that.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "The exact minute isn't guaranteed — Android batches background work to " +
                        "save battery. If it's consistently late, exempt the app from battery " +
                        "optimisation in system settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            Section("This device") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Read-only mirror")
                        Text(
                            if (settings.isMirrorDevice) {
                                "Downloads from the sheet. Editing happens on the other phone."
                            } else {
                                "This device owns the plans and uploads them to the sheet."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.isMirrorDevice,
                        onCheckedChange = { wantsMirror ->
                            // Turning it on replaces everything local, so confirm first.
                            // Turning it off is harmless — it just resumes uploading.
                            if (wantsMirror) confirmMirror = true else viewModel.setMirrorDevice(false)
                        },
                    )
                }
                Text(
                    "Only one device should own the plans. A sync replaces the whole sheet, so " +
                        "two uploading devices would overwrite each other.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            Section("Google Sheet sync") {
                Text(
                    "Mirrors your plans to the sheet, which is what lets the Apps Script email " +
                        "a matching reminder to someone without the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Web app URL (ends in /exec)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text("Shared secret") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.setConnection(url, secret) },
                        enabled = !isBusy,
                    ) { Text("Save") }
                    OutlinedButton(onClick = viewModel::testConnection, enabled = !isBusy) {
                        Text("Test connection")
                    }
                    OutlinedButton(onClick = viewModel::requestSync, enabled = !isBusy) {
                        Text(if (settings.isMirrorDevice) "Download now" else "Sync now")
                    }
                }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Last sync: " + settings.lastSyncAt.ifBlank { "never" },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (settings.isDirty) {
                            Text(
                                "Local changes not yet on the sheet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (settings.lastSyncError.isNotBlank()) {
                            Text(
                                "Last error: ${settings.lastSyncError}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            Section("Email reminders") {
                Text(
                    "Who the Apps Script emails each morning. Stored on the sheet, so this " +
                        "takes effect after the next sync.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = recipients,
                    onValueChange = { recipients = it },
                    label = { Text("Email addresses, comma separated") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.setEmailRecipients(recipients) },
                        enabled = !isBusy && !settings.isMirrorDevice,
                    ) { Text("Save recipients") }
                    OutlinedButton(onClick = viewModel::sendTestEmail, enabled = !isBusy) {
                        Text("Send test email")
                    }
                }
                Text(
                    "The test runs the real digest on the script, so it obeys the same " +
                        "once-a-day rule and will tell you if it found nothing due, or nobody " +
                        "to email.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MirrorConfirmation(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Make this a read-only mirror?") },
        text = {
            Text(
                "Any plans stored on this device will be replaced by the copy on the sheet, " +
                    "and this device will stop uploading. Do this on the second phone, not " +
                    "the one you enter plans on.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Replace and mirror") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
    }
}
