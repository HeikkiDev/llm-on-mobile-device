package dev.heikki.translator

import android.content.ClipData
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

const val LOW_RAM_WARNING = "This model is recommended for phones with 8 GB RAM or more."

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TranslatorScreen(viewModel: TranslatorViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.errorShown()
        }
    }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { LargeFlexibleTopAppBar(title = { Text("Translate") }, scrollBehavior = scrollBehavior) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            when (state.modelStatus) {
                ModelStatus.Ready -> TranslatorContent(state, viewModel, snackbar)
                ModelStatus.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Busy("Loading model…")
                }
                else -> DownloadContent(state, viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TranslatorContent(state: UiState, viewModel: TranslatorViewModel, snackbar: SnackbarHostState) {
    var pickingLanguage by rememberSaveable { mutableStateOf(false) }
    val tooLong = state.input.length > MAX_INPUT_CHARS
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
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
            shape = MaterialTheme.shapes.large,
            trailingIcon = if (state.input.isEmpty()) null else {
                {
                    IconButton(onClick = { viewModel.onInputChange("") }) {
                        Icon(AppIcons.Close, contentDescription = "Clear text")
                    }
                }
            },
            supportingText = {
                Text(
                    if (tooLong) "Too long: ${state.input.length} / $MAX_INPUT_CHARS characters"
                    else "${state.input.length} / $MAX_INPUT_CHARS",
                )
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val height = ButtonDefaults.MediumContainerHeight
            FilledTonalButton(
                onClick = { pickingLanguage = true },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.weight(1f).heightIn(min = height)
                    .semantics { contentDescription = "Translate to ${state.target.englishName}. Change language" },
                contentPadding = ButtonDefaults.contentPaddingFor(height),
            ) {
                Text("To: ${state.target.englishName}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(
                onClick = if (state.translating) viewModel::stop else viewModel::translate,
                shapes = ButtonDefaults.shapes(),
                enabled = state.translating || (state.input.isNotBlank() && !tooLong),
                modifier = Modifier.heightIn(min = height),
                contentPadding = ButtonDefaults.contentPaddingFor(height),
            ) {
                Text(if (state.translating) "Stop" else "Translate", style = MaterialTheme.typography.titleMedium)
            }
        }
        AnimatedVisibility(state.translating || state.output.isNotEmpty()) {
            OutputCard(
                state = state,
                onCopy = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Translation", state.output)))
                        // Android 13+ shows its own clipboard confirmation.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) snackbar.showSnackbar("Copied")
                    }
                },
                onClear = viewModel::clear,
            )
        }
    }

    if (pickingLanguage) {
        LanguageSheet(
            selected = state.target,
            onSelect = {
                viewModel.onTargetChange(it)
                pickingLanguage = false
            },
            onDismiss = { pickingLanguage = false },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OutputCard(state: UiState, onCopy: () -> Unit, onClear: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                state.target.englishName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (state.translating && state.output.isEmpty()) {
                LoadingIndicator(Modifier.size(40.dp).semantics { contentDescription = "Translating" })
            }
            SelectionContainer {
                Text(
                    state.output,
                    style = MaterialTheme.typography.headlineSmall,
                    // Announce once when finished, not per streamed token.
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = if (state.translating) "Translating" else state.output
                    },
                )
            }
            if (!state.translating && state.output.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onCopy) { Icon(AppIcons.Copy, contentDescription = "Copy translation") }
                    IconButton(onClick = onClear) { Icon(AppIcons.Close, contentDescription = "Clear") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSheet(selected: Language, onSelect: (Language) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query) { Languages.search(query) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(
            "Translate to",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp).semantics { heading() },
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            placeholder = { Text("Search languages") },
            leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
        )
        LazyColumn {
            items(results, key = { it.code }) { language ->
                val isSelected = language == selected
                ListItem(
                    headlineContent = { Text(language.englishName) },
                    supportingContent = { Text(language.nativeName) },
                    trailingContent = if (isSelected) {
                        { Icon(AppIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.selectable(selected = isSelected, role = Role.RadioButton) { onSelect(language) },
                )
            }
            if (results.isEmpty()) {
                item { Text("No languages found", Modifier.padding(24.dp)) }
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
                Button(onClick = viewModel::download, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) { Text(label) }
            }
            is ModelStatus.Downloading -> {
                LinearWavyProgressIndicator(
                    progress = { status.bytes.toFloat() / status.total },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${formatGb(status.bytes)} of ${formatGb(status.total)}")
                Text("Keep the app open until the download finishes.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = viewModel::cancelDownload, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
            ModelStatus.Verifying -> Busy("Verifying download…")
            is ModelStatus.Error -> {
                Text(status.message, color = MaterialTheme.colorScheme.error)
                if (status.loadFailed) {
                    Button(onClick = viewModel::deleteModelAndDownloadAgain, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                        Text("Delete and download again")
                    }
                } else {
                    Button(onClick = viewModel::download, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) { Text("Retry") }
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
