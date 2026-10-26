package dev.heikki.translator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

const val LOW_RAM_WARNING = "This model is recommended for phones with 8 GB RAM or more."

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TranslatorScreen(viewModel: TranslatorViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { LargeFlexibleTopAppBar(title = { Text("Translate") }) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state.modelStatus) {
                ModelStatus.Ready -> TranslatorContent(state)
                ModelStatus.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Busy("Loading model…")
                }
                else -> DownloadContent(state, viewModel)
            }
        }
    }
}

// A3 replaces this with the translator UI.
@Composable
private fun TranslatorContent(state: UiState) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Model ready.", style = MaterialTheme.typography.titleMedium)
        if (state.lowRam) Text(LOW_RAM_WARNING, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DownloadContent(state: UiState, viewModel: TranslatorViewModel) {
    val status = state.modelStatus
    if (status is ModelStatus.Downloading || status is ModelStatus.Verifying) KeepScreenOn()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Download the language model", style = MaterialTheme.typography.headlineSmall)
        Text(
            "To translate offline, this app needs to download a language model once " +
                "(${formatGb(MODEL_SIZE_BYTES)}). After that it works without internet.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Wi-Fi recommended. Free space needed: about ${formatGb(requiredFreeBytes(0))}. " +
                "Downloaded from Hugging Face.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.lowRam) {
            Text(LOW_RAM_WARNING, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        when (status) {
            is ModelStatus.Missing, ModelStatus.NeedsCellularConsent -> {
                val partial = (status as? ModelStatus.Missing)?.partialBytes ?: 0L
                val label = if (partial > 0) {
                    "Resume (${formatGb(partial)} of ${formatGb(MODEL_SIZE_BYTES)})"
                } else {
                    "Download"
                }
                Button(onClick = viewModel::download, Modifier.fillMaxWidth()) { Text(label) }
            }
            is ModelStatus.Downloading -> {
                LinearWavyProgressIndicator(
                    progress = { status.bytes.toFloat() / status.total },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${formatGb(status.bytes)} of ${formatGb(status.total)}")
                Text("Keep the app open until the download finishes.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = viewModel::cancelDownload, Modifier.fillMaxWidth()) { Text("Cancel") }
            }
            ModelStatus.Verifying -> Busy("Verifying download…")
            is ModelStatus.Error -> {
                Text(status.message, color = MaterialTheme.colorScheme.error)
                if (status.loadFailed) {
                    Button(onClick = viewModel::deleteModelAndDownloadAgain, Modifier.fillMaxWidth()) {
                        Text("Delete and download again")
                    }
                } else {
                    Button(onClick = viewModel::download, Modifier.fillMaxWidth()) { Text("Retry") }
                }
            }
            ModelStatus.Loading, ModelStatus.Ready -> Unit
        }
    }

    if (status == ModelStatus.NeedsCellularConsent) {
        AlertDialog(
            onDismissRequest = viewModel::waitForWifi,
            title = { Text("Use mobile data?") },
            text = { Text("This is a ${formatGb(MODEL_SIZE_BYTES)} download. Use mobile data?") },
            confirmButton = { TextButton(onClick = viewModel::acceptCellular) { Text("Download anyway") } },
            dismissButton = { TextButton(onClick = viewModel::waitForWifi) { Text("Wait for Wi-Fi") } },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Busy(label: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        LoadingIndicator()
        Text(label)
    }
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
