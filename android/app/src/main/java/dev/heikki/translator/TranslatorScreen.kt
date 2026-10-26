package dev.heikki.translator

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.errorShown()
        }
    }
    Scaffold(
        topBar = { LargeFlexibleTopAppBar(title = { Text("Translate") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state.modelStatus) {
                ModelStatus.Ready -> TranslatorContent(state, viewModel)
                ModelStatus.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Busy("Loading model…")
                }
                else -> DownloadContent(state, viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TranslatorContent(state: UiState, viewModel: TranslatorViewModel) {
    var pickingLanguage by rememberSaveable { mutableStateOf(false) }
    val tooLong = state.input.length > MAX_INPUT_CHARS

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.lowRam) {
            Text(LOW_RAM_WARNING, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        OutlinedTextField(
            value = state.input,
            onValueChange = viewModel::onInputChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Text to translate") },
            minLines = 4,
            isError = tooLong,
            supportingText = { Text("${state.input.length} / $MAX_INPUT_CHARS") },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = { pickingLanguage = true }) { Text("To: ${state.target.englishName}") }
            Spacer(Modifier.weight(1f))
            if (state.translating) {
                Button(onClick = viewModel::stop) { Text("Stop") }
            } else {
                Button(
                    onClick = viewModel::translate,
                    enabled = state.input.isNotBlank() && !tooLong,
                ) { Text("Translate") }
            }
        }
        if (state.translating || state.output.isNotEmpty()) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.translating && state.output.isEmpty()) LoadingIndicator(Modifier.size(32.dp))
                    SelectionContainer { Text(state.output, style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }
    }

    if (pickingLanguage) {
        ModalBottomSheet(onDismissRequest = { pickingLanguage = false }) {
            LazyColumn {
                items(Languages.all, key = { it.code }) { language ->
                    ListItem(
                        headlineContent = { Text(language.englishName) },
                        supportingContent = { Text(language.nativeName) },
                        modifier = Modifier.clickable {
                            viewModel.onTargetChange(language)
                            pickingLanguage = false
                        },
                    )
                }
            }
        }
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
