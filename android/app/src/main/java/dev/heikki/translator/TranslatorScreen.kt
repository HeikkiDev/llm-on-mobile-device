package dev.heikki.translator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

const val LOW_RAM_WARNING = "Este modelo está recomendado para móviles con 8 GB de RAM o más."
const val ABOUT_TEXT = "Funciona con Gemma 4 E2B de Google (licencia Apache-2.0), dentro de este móvil. " +
    "El audio se procesa en el teléfono y se borra al instante."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslatorScreen(viewModel: TranslatorViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var picker by remember { mutableStateOf<List<Language>?>(null) } // non-null = open, with "Parecidos" first
    var micDenied by rememberSaveable { mutableStateOf(false) }
    var pendingMic by remember { mutableStateOf<Pair<Side, Boolean>?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingMic
        pendingMic = null
        if (granted && pending != null) viewModel.onMic(pending.first, pending.second) else if (!granted) micDenied = true
    }
    // RECORD_AUDIO is requested on the first mic tap, never at launch (SPEC-WALTER §4.1).
    val mic: (Side, Boolean) -> Unit = { side, detecting ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.onMic(side, detecting)
        } else {
            pendingMic = side to detecting
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }
    val conversation = state.modelStatus == ModelStatus.Ready && state.coworker != null
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val coworker = state.coworker
                    if (conversation && coworker != null) {
                        LanguageChip(coworker, voiceless = coworker.code in state.voiceless) { picker = emptyList() }
                    } else {
                        Text("Traductor Walter")
                    }
                },
                actions = {
                    if (conversation) {
                        IconButton(
                            onClick = viewModel::openDetection,
                            modifier = Modifier.semantics { contentDescription = "Detectar idioma" },
                        ) { Text("🎤?", style = MaterialTheme.typography.titleMedium) }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            when {
                state.modelStatus == ModelStatus.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Busy("Cargando el modelo…")
                }
                state.modelStatus != ModelStatus.Ready -> DownloadContent(state, viewModel)
                state.coworker == null -> ChooseLanguage(state, onDetect = viewModel::openDetection, onSearch = { picker = emptyList() })
                else -> ConversationContent(state, viewModel, mic, onChange = { picker = it })
            }
        }
    }

    picker?.let { related ->
        LanguageSheet(
            state = state,
            related = related,
            onSelect = {
                viewModel.setCoworker(it)
                picker = null
            },
            onDismiss = { picker = null },
        )
    }
    state.detectSheet?.let { sheet ->
        DetectionSheet(
            sheet = sheet,
            activity = state.activity,
            onMic = { mic(Side.Coworker, true) },
            onRetry = viewModel::openDetection,
            onSearch = {
                viewModel.closeDetection()
                picker = emptyList()
            },
            onDismiss = viewModel::closeDetection,
        )
    }
    if (micDenied) {
        AlertDialog(
            onDismissRequest = { micDenied = false },
            title = { Text("Falta permiso de micrófono") },
            text = { Text("Para traducir lo que se dice, la app necesita el micrófono. El audio nunca sale del teléfono.") },
            confirmButton = {
                TextButton(onClick = {
                    micDenied = false
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    )
                }) { Text("Abrir ajustes") }
            },
            dismissButton = { TextButton(onClick = { micDenied = false }) { Text("Cerrar") } },
        )
    }
}

@Composable
private fun LanguageChip(language: Language, voiceless: Boolean, onClick: () -> Unit) {
    InputChip(
        selected = false,
        onClick = onClick,
        label = {
            Text(
                "Compañero: ${language.nativeName} · ${language.spanishName}" + if (voiceless) " 🔇" else "",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        trailingIcon = { Icon(AppIcons.DropDown, contentDescription = null) },
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Idioma del compañero: ${language.spanishName}. Cambiar" },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChooseLanguage(state: UiState, onDetect: () -> Unit, onSearch: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        Text(
            "¿Qué idioma habla tu compañero?",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Button(
            onClick = onDetect,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
        ) { Text("🎤 Que hable él", style = MaterialTheme.typography.headlineSmall) }
        OutlinedButton(
            onClick = onSearch,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        ) { Text("🔍 Buscar en la lista", style = MaterialTheme.typography.titleLarge) }
        if (state.lowRam) {
            Text(LOW_RAM_WARNING, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Text(ABOUT_TEXT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConversationContent(
    state: UiState,
    viewModel: TranslatorViewModel,
    mic: (Side, Boolean) -> Unit,
    onChange: (List<Language>) -> Unit,
) {
    val coworker = state.coworker ?: return
    val context = LocalContext.current
    var writing by rememberSaveable { mutableStateOf(false) }
    var confirmNew by rememberSaveable { mutableStateOf(false) }
    var shown by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    KeepScreenOn()
    Haptics(state.activity)
    LaunchedEffect(state.turns.size, state.turns.lastOrNull()?.translation?.length) {
        if (state.turns.isNotEmpty()) list.animateScrollToItem(state.turns.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        state.confirmLanguage?.let { detected ->
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${detected.nativeName} · ${detected.spanishName}", style = MaterialTheme.typography.headlineSmall)
                    Text("¿Correcto?", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = viewModel::confirmDetected, modifier = Modifier.heightIn(min = 56.dp)) { Text("Sí") }
                        OutlinedButton(
                            onClick = {
                                viewModel.confirmDetected()
                                onChange(Languages.related(detected))
                            },
                            modifier = Modifier.heightIn(min = 56.dp),
                        ) { Text("Cambiar") }
                    }
                }
            }
        }
        if (Languages.spanish.code in state.voiceless) {
            Notice("Falta la voz española en el teléfono.", "Instalar") { context.installVoices() }
        }
        if (state.lowVolume) {
            Notice("Sube el volumen 🔊", "Vale", viewModel::dismissLowVolume)
        }
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.turns.isEmpty()) {
                item {
                    Text(
                        "Toca un botón y habla. La traducción se dice en voz alta.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                    )
                }
            }
            items(state.turns, key = { it.id }) { turn ->
                Bubble(
                    turn = turn,
                    newest = turn.id == state.turns.last().id,
                    voiceless = turn.target.code in state.voiceless,
                    speaking = state.activity == Activity.Speaking(turn.id),
                    canAct = state.activity is Activity.Idle || state.activity is Activity.Speaking,
                    onRepeat = { viewModel.repeat(turn.id) },
                    onStopSpeaking = viewModel::stopSpeaking,
                    onShow = { shown = turn.translation },
                    onRetry = { viewModel.retry(turn.id) },
                )
            }
        }
        Surface(tonalElevation = 3.dp) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MicButton(
                        side = Side.Walter, label = "HABLO YO", word = null, state = state,
                        modifier = Modifier.weight(1f), onClick = { mic(Side.Walter, false) },
                    )
                    MicButton(
                        side = Side.Coworker, label = "HABLA ÉL", word = coworker.speakWord, state = state,
                        modifier = Modifier.weight(1f), onClick = { mic(Side.Coworker, false) },
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val idle = state.activity is Activity.Idle || state.activity is Activity.Speaking
                    TextButton(onClick = { writing = true }, enabled = idle, modifier = Modifier.heightIn(min = 56.dp)) {
                        Icon(AppIcons.Keyboard, contentDescription = null)
                        Text("  Escribir", style = MaterialTheme.typography.titleMedium)
                    }
                    TextButton(
                        onClick = { confirmNew = true },
                        enabled = idle && state.turns.isNotEmpty(),
                        modifier = Modifier.heightIn(min = 56.dp),
                    ) {
                        Icon(AppIcons.Delete, contentDescription = null)
                        Text("  Nueva", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }

    if (writing) {
        WritingSheet(
            coworker = coworker,
            onTranslate = {
                viewModel.translateText(it)
                writing = false
            },
            onDismiss = { writing = false },
        )
    }
    if (confirmNew) {
        AlertDialog(
            onDismissRequest = { confirmNew = false },
            title = { Text("¿Empezar una conversación nueva?") },
            text = { Text("Se borra lo que hay en la pantalla.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.newConversation()
                    confirmNew = false
                }) { Text("Nueva") }
            },
            dismissButton = { TextButton(onClick = { confirmNew = false }) { Text("Cancelar") } },
        )
    }
    shown?.let { text -> ShowFullScreen(text) { shown = null } }
}

@Composable
private fun Notice(text: String, action: String, onAction: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MicButton(side: Side, label: String, word: String?, state: UiState, modifier: Modifier, onClick: () -> Unit) {
    val activity = state.activity
    val speakingSide = (activity as? Activity.Speaking)?.let { a -> state.turns.firstOrNull { it.id == a.turnId }?.side }
    val listening = activity is Activity.Listening && activity.side == side && !activity.detecting
    val translating = activity is Activity.Translating && activity.side == side
    val speaking = speakingSide == side
    val enabled = when (activity) {
        Activity.Idle, is Activity.Speaking -> true
        is Activity.Listening -> listening
        is Activity.Translating -> translating
        Activity.Detecting -> false
    }
    val pulse = state.hint == side && activity is Activity.Idle
    val scale = if (pulse) {
        rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 1f, targetValue = 1.05f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "scale",
        ).value
    } else 1f
    val colors = if (side == Side.Walter) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.tertiary,
        contentColor = MaterialTheme.colorScheme.onTertiary,
    )
    val (title, subtitle) = when {
        listening -> "Escuchando…" to
            if ((activity as Activity.Listening).elapsedMs >= WARN_RECORDING_MS) "Termina la frase…" else "toca para terminar"
        translating -> "Traduciendo…" to "toca para parar"
        speaking -> "Hablando…" to "toca para parar"
        else -> label to word
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = colors,
        shapes = ButtonDefaults.shapes(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
        modifier = modifier.heightIn(min = 140.dp).graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                translating -> LoadingIndicator(Modifier.size(40.dp), color = LocalContentColorOrWhite(side))
                listening || speaking -> Icon(AppIcons.Stop, contentDescription = null, modifier = Modifier.size(40.dp))
                else -> Icon(AppIcons.Mic, contentDescription = null, modifier = Modifier.size(40.dp))
            }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            if (listening) {
                LinearProgressIndicator(
                    progress = { (activity as Activity.Listening).level },
                    modifier = Modifier.fillMaxWidth(0.8f).height(6.dp),
                    color = LocalContentColorOrWhite(side),
                )
            }
            subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 2) }
        }
    }
}

@Composable
private fun LocalContentColorOrWhite(side: Side): Color =
    if (side == Side.Walter) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onTertiary

/** Light tick when listening starts, double tick when it stops (§4.2). No VIBRATE permission needed. */
@Composable
private fun Haptics(activity: Activity) {
    val view = LocalView.current
    val listening = activity is Activity.Listening
    var wasListening by remember { mutableStateOf(false) }
    LaunchedEffect(listening) {
        if (listening && !wasListening) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        } else if (!listening && wasListening) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            delay(120)
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        wasListening = listening
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Bubble(
    turn: Turn,
    newest: Boolean,
    voiceless: Boolean,
    speaking: Boolean,
    canAct: Boolean,
    onRepeat: () -> Unit,
    onStopSpeaking: () -> Unit,
    onShow: () -> Unit,
    onRetry: () -> Unit,
) {
    val walter = turn.side == Side.Walter
    Box(Modifier.fillMaxWidth(), contentAlignment = if (walter) Alignment.CenterEnd else Alignment.CenterStart) {
        Card(
            modifier = Modifier.widthIn(max = 340.dp).fillMaxWidth(0.88f),
            colors = CardDefaults.cardColors(
                containerColor = if (walter) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
            ),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    (if (walter) "Walter" else "Compañero") + (if (turn.typed) " ⌨" else ""),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (turn.translation.isNotEmpty()) {
                    Text(
                        turn.translation,
                        style = if (newest || voiceless) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                        // Announce once when finished, not per streamed token.
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = if (turn.done) turn.translation else "Traduciendo"
                        },
                    )
                }
                if (turn.transcript.isNotEmpty()) {
                    Text(turn.transcript, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!turn.done && turn.translation.isEmpty()) {
                    LoadingIndicator(Modifier.size(40.dp).semantics { contentDescription = "Traduciendo" })
                }
                turn.error?.let { error ->
                    Text(error, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    FilledTonalButton(onClick = onRetry, enabled = canAct, modifier = Modifier.heightIn(min = 56.dp)) { Text("Reintentar") }
                }
                if (turn.done && turn.error == null && turn.translation.isNotEmpty()) {
                    if (voiceless) {
                        Text("Enséñale la pantalla 👀", style = MaterialTheme.typography.titleMedium)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onShow, modifier = Modifier.heightIn(min = 48.dp)) { Text("Mostrar") }
                        if (!voiceless) {
                            IconButton(
                                onClick = if (speaking) onStopSpeaking else onRepeat,
                                enabled = canAct,
                                modifier = Modifier.size(56.dp),
                            ) {
                                Icon(
                                    if (speaking) AppIcons.Stop else AppIcons.VolumeUp,
                                    contentDescription = if (speaking) "Parar la voz" else "Repetir en voz alta",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Full screen, white on black, the largest font that fits: for the coworker to read (§4.2). */
@Composable
private fun ShowFullScreen(text: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black).clickable(onClickLabel = "Cerrar", onClick = onClose).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text,
                style = TextStyle(color = Color.White, textAlign = TextAlign.Center),
                autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = 160.sp),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WritingSheet(coworker: Language, onTranslate: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val tooLong = text.length > MAX_INPUT_CHARS
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Escribir", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("En español o en ${coworker.spanishName.lowercase()}") },
                minLines = 3,
                isError = tooLong,
                shape = MaterialTheme.shapes.large,
                supportingText = { Text("${text.length} / $MAX_INPUT_CHARS") },
            )
            Button(
                onClick = { onTranslate(text) },
                enabled = text.isNotBlank() && !tooLong,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            ) { Text("Traducir", style = MaterialTheme.typography.titleLarge) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DetectionSheet(
    sheet: DetectSheet,
    activity: Activity,
    onMic: () -> Unit,
    onRetry: () -> Unit,
    onSearch: () -> Unit,
    onDismiss: () -> Unit,
) {
    val listening = activity as? Activity.Listening
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                activity == Activity.Detecting -> Busy("Detectando idioma…")
                sheet == DetectSheet.Failed -> {
                    Text("No lo he reconocido", style = MaterialTheme.typography.headlineSmall)
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                        Text("Probar otra vez", style = MaterialTheme.typography.titleLarge)
                    }
                    OutlinedButton(onClick = onSearch, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                        Text("🔍 Buscar en la lista", style = MaterialTheme.typography.titleLarge)
                    }
                }
                else -> {
                    Text(
                        "Pídele que diga una frase en su idioma",
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                    Button(
                        onClick = onMic,
                        enabled = activity is Activity.Idle || listening?.detecting == true,
                        modifier = Modifier.size(160.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary,
                        ),
                    ) {
                        Icon(
                            if (listening != null) AppIcons.Stop else AppIcons.Mic,
                            contentDescription = if (listening != null) "Terminar" else "Escuchar",
                            modifier = Modifier.size(64.dp),
                        )
                    }
                    if (listening != null) {
                        Text("Escuchando… toca para terminar", style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(progress = { listening.level }, modifier = Modifier.fillMaxWidth(0.6f).height(6.dp))
                    }
                    TextButton(onClick = onSearch, modifier = Modifier.heightIn(min = 48.dp)) { Text("Buscar en la lista") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSheet(state: UiState, related: List<Language>, onSelect: (Language) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val results = remember(query) { Languages.search(query) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(
            "Idioma del compañero",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp).semantics { heading() },
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            placeholder = { Text("Buscar idioma") },
            leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
        )
        LazyColumn {
            @Composable
            fun Row(language: Language) = LanguageRow(
                language,
                selected = language == state.coworker,
                voiceless = language.code in state.voiceless,
                onSelect = onSelect,
            )
            if (query.isBlank()) {
                if (related.isNotEmpty()) {
                    item { Section("Parecidos") }
                    items(related, key = { "rel-" + it.code }) { Row(it) }
                }
                if (state.recents.isNotEmpty()) {
                    item { Section("Recientes") }
                    items(state.recents, key = { "rec-" + it.code }) { Row(it) }
                }
                item { Section("Todos") }
            }
            items(results, key = { it.code }) { Row(it) }
            if (results.isEmpty()) {
                item { Text("No hay ningún idioma con ese nombre", Modifier.padding(24.dp)) }
            }
            item {
                TextButton(onClick = { context.installVoices() }, modifier = Modifier.padding(16.dp).heightIn(min = 48.dp)) {
                    Text("🔇 = sin voz en este teléfono. Instalar voces")
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun LanguageRow(language: Language, selected: Boolean, voiceless: Boolean, onSelect: (Language) -> Unit) {
    ListItem(
        headlineContent = { Text(language.nativeName, style = MaterialTheme.typography.titleLarge) },
        supportingContent = { Text(language.spanishName + if (voiceless) "  🔇" else "") },
        trailingContent = if (selected) {
            { Icon(AppIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        } else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .selectable(selected = selected, role = Role.RadioButton) { onSelect(language) }
            .semantics { contentDescription = language.spanishName + if (voiceless) ", sin voz" else "" },
    )
}

/** Opens the system TTS engine's voice installer; the system does the download, not this app. */
private fun android.content.Context.installVoices() {
    runCatching {
        startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
        Text("Descarga el modelo de idiomas", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Para traducir sin internet, la app necesita descargar un modelo de idiomas una sola vez " +
                "(${formatGb(MODEL_SIZE_BYTES)}). Después funciona sin conexión.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Mejor con wifi. Espacio libre necesario: unos ${formatGb(requiredFreeBytes(0))}. " +
                "Se descarga de Hugging Face.",
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
                    "Continuar (${formatGb(partial)} de ${formatGb(MODEL_SIZE_BYTES)})"
                } else {
                    "Descargar"
                }
                Button(onClick = viewModel::download, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) { Text(label) }
            }
            is ModelStatus.Downloading -> {
                LinearWavyProgressIndicator(
                    progress = { status.bytes.toFloat() / status.total },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${formatGb(status.bytes)} de ${formatGb(status.total)}")
                Text("Deja la app abierta hasta que termine la descarga.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = viewModel::cancelDownload, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
            }
            ModelStatus.Verifying -> Busy("Comprobando la descarga…")
            is ModelStatus.Error -> {
                Text(status.message, color = MaterialTheme.colorScheme.error)
                if (status.loadFailed) {
                    Button(onClick = viewModel::deleteModelAndDownloadAgain, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                        Text("Borrar y descargar de nuevo")
                    }
                } else {
                    Button(onClick = viewModel::download, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) { Text("Reintentar") }
                }
            }
            ModelStatus.Loading, ModelStatus.Ready -> Unit
        }
        Text(ABOUT_TEXT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (status == ModelStatus.NeedsCellularConsent) {
        AlertDialog(
            onDismissRequest = viewModel::waitForWifi,
            title = { Text("¿Usar datos móviles?") },
            text = { Text("Son ${formatGb(MODEL_SIZE_BYTES)} de descarga. ¿Usar datos móviles?") },
            confirmButton = { TextButton(onClick = viewModel::acceptCellular) { Text("Descargar igualmente") } },
            dismissButton = { TextButton(onClick = viewModel::waitForWifi) { Text("Esperar al wifi") } },
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
