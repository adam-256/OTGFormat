package dev.otgformat.app

import android.Manifest
import android.content.BroadcastReceiver
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.otgformat.core.PartitionScheme
import dev.otgformat.usb.Confirmation

class MainActivity : ComponentActivity() {

    private val viewModel: FormatViewModel by viewModels()
    private var permissionReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionReceiver = UsbAccess(this).registerPermissionReceiver { _, granted ->
            viewModel.onPermissionResult(granted)
        }

        // The format runs in a foreground service, which needs somewhere to
        // show its progress. Asking here keeps the request away from the moment
        // the user is trying to start a format.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FormatScreen(viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // A device may have been plugged in or pulled while we were away.
        if (!FormatController.isRunning) viewModel.refresh()
    }

    override fun onDestroy() {
        permissionReceiver?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }
}

@Composable
private fun FormatScreen(viewModel: FormatViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val formatState by viewModel.formatState.collectAsStateWithLifecycle()

    when (val current = formatState) {
        is FormatState.Running -> RunningScreen(current, viewModel::cancelFormat)
        is FormatState.Done -> ResultScreen("Format complete", current.summary, viewModel::dismissResult)
        is FormatState.Failed -> ResultScreen(
            "Format failed",
            listOfNotNull(current.message, current.advice).joinToString("\n\n"),
            viewModel::dismissResult,
        )
        is FormatState.Cancelled -> ResultScreen("Format cancelled", current.message, viewModel::dismissResult)
        FormatState.Idle -> SetupScreen(state, viewModel)
    }
}

@Composable
private fun SetupScreen(state: UiState, viewModel: FormatViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("OTGFormat", style = MaterialTheme.typography.headlineSmall)

        // ---- device picker ------------------------------------------------
        Text("Device", style = MaterialTheme.typography.titleMedium)
        if (state.candidates.isEmpty()) {
            Text(
                "No USB mass-storage device found.\n\n" +
                    "Plug one in through an OTG adapter. If it is already plugged in and Android has " +
                    "mounted it, eject it in Files first — another app holding the interface stops this " +
                    "one from claiming it.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            state.candidates.forEach { candidate ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = candidate.key == state.selectedKey,
                            onClick = { viewModel.select(candidate) },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = candidate.key == state.selectedKey,
                        onClick = { viewModel.select(candidate) },
                    )
                    Text(
                        candidate.device.productName
                            ?: "USB %04x:%04x".format(candidate.device.vendorId, candidate.device.productId),
                    )
                }
            }
        }
        TextButton(onClick = viewModel::refresh) { Text("Rescan") }

        if (state.needsPermission) {
            Text(
                "Waiting for USB permission. Accept the system prompt to continue.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        // ---- identity, shown before anything can be written ---------------
        state.target?.let { target ->
            HorizontalDivider()
            Text("Check this against the device in your hand", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    target.describe(),
                    modifier = Modifier.padding(12.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // ---- options --------------------------------------------------
            HorizontalDivider()
            Text("Options", style = MaterialTheme.typography.titleMedium)

            Dropdown("Filesystem", "FAT32", listOf("FAT32")) { }

            Dropdown(
                label = "Cluster size",
                selected = if (state.clusterBytes == 0) "Automatic" else formatClusterLabel(state.clusterBytes),
                options = listOf("Automatic") + CLUSTER_CHOICES.map { formatClusterLabel(it) },
            ) { choice ->
                viewModel.setClusterBytes(
                    if (choice == "Automatic") 0 else CLUSTER_CHOICES.first { formatClusterLabel(it) == choice },
                )
            }

            OutlinedTextField(
                value = state.label,
                onValueChange = viewModel::setLabel,
                label = { Text("Volume label (up to 11 characters)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Dropdown(
                label = "Partition scheme",
                selected = state.scheme.name,
                options = PartitionScheme.entries.map { it.name },
            ) { choice -> viewModel.setScheme(PartitionScheme.valueOf(choice)) }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = state.bootable, onCheckedChange = viewModel::setBootable)
                Spacer(Modifier.width(12.dp))
                Text("Mark the partition bootable")
            }

            // ---- the plan -------------------------------------------------
            HorizontalDivider()
            state.planError?.let {
                Text("Cannot format with these options", style = MaterialTheme.typography.titleMedium)
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            state.layout?.let { plan ->
                Text("What will happen", style = MaterialTheme.typography.titleMedium)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        plan.describe(),
                        modifier = Modifier.padding(12.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // ---- confirmation ---------------------------------------------
            when (val confirmation = state.confirmation) {
                is Confirmation.TypeToConfirm -> {
                    Text(confirmation.reason, color = MaterialTheme.colorScheme.error)
                    OutlinedTextField(
                        value = state.typed,
                        onValueChange = viewModel::setTyped,
                        label = { Text("Type ${confirmation.phrase}") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> Unit
            }

            Button(
                onClick = viewModel::startFormat,
                enabled = state.canFormat,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Erase and format this device")
            }
            Text(
                "Everything on the device will be destroyed.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun RunningScreen(state: FormatState.Running, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Formatting ${state.deviceName}", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        Text(state.phaseLabel, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        // Real progress, not indeterminate: clearing the FATs on a large stick
        // is minutes of work and a spinner tells the user nothing.
        LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text("${state.sectorsDone} of ${state.sectorsTotal} sectors")
        Spacer(Modifier.height(24.dp))
        Text(
            "Do not unplug the device.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
        Text(
            "Cancelling leaves the device unusable until it is formatted again.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ResultScreen(title: String, body: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                body,
                modifier = Modifier.padding(12.dp),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

@Composable
private fun Dropdown(label: String, selected: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/** Cluster sizes FAT32 accepts, from the minimum sector to the practical maximum. */
private val CLUSTER_CHOICES = listOf(512, 1024, 2048, 4096, 8192, 16384, 32768)

private fun formatClusterLabel(bytes: Int): String =
    if (bytes >= 1024) "${bytes / 1024} KiB" else "$bytes B"
