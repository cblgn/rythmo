package fr.rythmo.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.rythmo.R
import fr.rythmo.RythmoScreen
import fr.rythmo.RythmoState
import fr.rythmo.SCHOOL_CLASSES
import fr.rythmo.domain.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentificationScreen(
    state: RythmoState,
    onLastName: (String) -> Unit,
    onFirstName: (String) -> Unit,
    onClass: (String) -> Unit,
    onStart: () -> Unit,
    onTimingMode: (TimingMode) -> Unit = {},
    onHome: (() -> Unit)? = null,
    onReset: () -> Unit = {},
) {
    val firstNameFocus = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    var expanded by remember { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        topBar = {
            TopAppBar(navigationIcon = {
                if (onHome != null) TextButton(onClick = onHome) { Text("‹ Accueil") }
            }, actions = {
                HelpButton("Identifiez l’élève et choisissez le mode. En manuel, saisissez chaque tour. En automatique, démarrez le chrono puis touchez le bouton à chaque passage. COMMENCER enregistre l’heure de préparation de l’évaluation.")
            }, title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(painterResource(R.drawable.ic_rythmo), null, Modifier.size(44.dp))
                    Column {
                        Text("Rythmo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic, letterSpacing = (-1).sp)
                        Text("Évaluation demi-fond", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            })
        },
        bottomBar = {
            BottomAction {
                Column {
                    TextButton(onClick = { focus.clearFocus(); onReset() },
                        modifier = Modifier.fillMaxWidth()) { Text("Nouvelle évaluation") }
                    Button(
                        onClick = { focus.clearFocus(); onStart() },
                        enabled = state.canStart,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) { Text(if (state.startedAt == null) "COMMENCER" else "REPRENDRE L’ÉVALUATION", fontWeight = FontWeight.Bold) }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Avant le départ", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Identifiez l’élève pour préparer son évaluation.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = state.lastName, onValueChange = onLastName,
                label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { firstNameFocus.requestFocus() }),
            )
            OutlinedTextField(
                value = state.firstName, onValueChange = onFirstName,
                label = { Text("Prénom") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(firstNameFocus),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); expanded = true }),
            )
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = state.schoolClass, onValueChange = {}, readOnly = true, singleLine = true,
                    label = { Text("Classe") }, placeholder = { Text("Sélectionner une classe") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    SCHOOL_CLASSES.forEach { schoolClass ->
                        DropdownMenuItem(text = { Text(schoolClass) }, onClick = { onClass(schoolClass); expanded = false; focus.clearFocus() })
                    }
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Date", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(state.formattedDate, fontWeight = FontWeight.SemiBold)
                }
            }
            Text("Chronométrage", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                TimingMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = state.timingMode == mode,
                        onClick = { focus.clearFocus(); onTimingMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, TimingMode.entries.size),
                    ) { Text(if (mode == TimingMode.MANUAL) "Manuel" else "Automatique") }
                }
            }
            Text(if (state.timingMode == TimingMode.MANUAL)
                "Saisissez le temps de chaque tour de 400 m."
            else "Démarrez le chrono sur l’écran suivant, puis touchez le bouton à chaque passage.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.hasTimingData) Text("Changer de mode permet de recommencer pour cet élève, après confirmation.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimingScreen(
    state: RythmoState,
    onMinutes: (String) -> Unit,
    onSeconds: (String) -> Unit,
    onAdd: () -> Boolean,
    onBack: () -> Unit,
    onValidate: () -> Unit,
    pdfAvailable: Boolean = false,
    exporting: Boolean = false,
    pdfError: String? = null,
    onOpenPdf: () -> Unit = {},
    onSharePdf: () -> Unit = {},
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onEditLap: (Int) -> Unit = {},
    onCancelCorrection: () -> Unit = {},
    onStartStopwatch: () -> Unit = {},
    onAutomaticPassage: () -> Unit = {},
    onReset: () -> Unit = {},
) {
    val minutesFocus = remember { FocusRequester() }
    val secondsFocus = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val preview = LocalInspectionMode.current
    val listState = rememberLazyListState()
    val result = state.result
    val manualEntry = state.editingLapNumber != null || (state.nextDistance != null && state.timingMode == TimingMode.MANUAL)
    val view = LocalView.current
    DisposableEffect(state.timerRunning, view) {
        val previous = view.keepScreenOn
        if (state.timerRunning) view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    LaunchedEffect(state.nextDistance, state.editingLapNumber) {
        if (!preview) {
            listState.scrollToItem(0)
            if (manualEntry) minutesFocus.requestFocus()
            else { focus.clearFocus(); keyboard?.hide() }
        }
    }
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("2000 m", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(onClick = { focus.clearFocus(); onBack() }) { Text("‹ Élève") }
                },
                actions = {
                    HelpButton(if (result == null && state.timingMode == TimingMode.AUTOMATIC)
                        "Au départ du coureur, touchez DÉMARRER LE CHRONO. À chaque tour, touchez ENREGISTRER LE PASSAGE. Le chrono s’arrête automatiquement à 2000 m. Gardez le téléphone en main pour ne manquer aucun passage."
                    else if (result == null)
                        "Saisissez le temps de chaque tour de 400 m, pas le cumul. Il peut être plus court, identique ou plus long. Validez avec + ou Terminé."
                    else "Vous pouvez corriger chaque passage une seule fois. Le professeur verra le temps initial et le temps corrigé. Validez ensuite l’évaluation pour créer le PDF.")
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (result != null && state.editingLapNumber == null) BottomAction {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    pdfError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    if (pdfAvailable) {
                        Text("Évaluation validée · PDF prêt", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onOpenPdf, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Ouvrir le PDF") }
                            Button(onClick = onSharePdf, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Partager") }
                        }
                    } else {
                        Button(onClick = onValidate, enabled = !exporting,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text(if (exporting) "CRÉATION DU PDF…" else "VALIDER L’ÉVALUATION", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            state = listState, contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "student") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.studentLabel, style = MaterialTheme.typography.titleSmall)
                    Text(state.formattedDate + (state.formattedStartTime?.let { " • Évaluation commencée à $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { focus.clearFocus(); onReset() }) { Text("Nouvelle évaluation") }
                }
            }
            if (manualEntry) {
                item(key = "entry") {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(state.editingLapNumber?.let { "Corriger le passage des ${it * LAP_DISTANCE_METERS} m" }
                                ?: "Prochain passage : ${state.nextDistance} m", style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            Text("Temps de ce tour de 400 m · Cumul automatique", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                            if (state.editingLapNumber != null) {
                                Text("Une seule modification est autorisée pour ce passage. Le temps initial restera visible dans le bilan et le PDF.",
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TimeField(state.minutes, onMinutes, "min", "Minutes", ImeAction.Next,
                                    KeyboardActions(onNext = { secondsFocus.requestFocus() }),
                                    Modifier.weight(1f).focusRequester(minutesFocus))
                                Text(":", style = MaterialTheme.typography.headlineMedium)
                                TimeField(state.seconds, onSeconds, "sec", "Secondes", ImeAction.Done,
                                    KeyboardActions(onDone = { onAdd() }),
                                    Modifier.weight(1f).focusRequester(secondsFocus))
                                FilledIconButton(onClick = { onAdd() }, modifier = Modifier.size(56.dp)
                                    .semantics { contentDescription = if (state.editingLapNumber == null) "Ajouter le passage" else "Confirmer la correction unique" }) {
                                    Text(if (state.editingLapNumber == null) "+" else "✓", style = MaterialTheme.typography.headlineLarge)
                                }
                            }
                            state.inputError?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            }
                            if (state.editingLapNumber != null) {
                                TextButton(onClick = onCancelCorrection) { Text("Annuler la modification") }
                            }
                        }
                    }
                }
            } else if (state.nextDistance != null) {
                item(key = "stopwatch") {
                    StopwatchCard(state, onStartStopwatch, onAutomaticPassage)
                }
            } else {
                item(key = "finished") {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("Épreuve terminée", style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("5 passages enregistrés · 2000 m", color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("Vérifiez vos temps. Une correction par passage est permise ; le professeur verra les deux valeurs.",
                                modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }
            item(key = "heading") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Passages enregistrés", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Écart = temps du tour − temps du tour précédent.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.laps.isEmpty()) {
                        Text("Le premier passage apparaîtra ici.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Row(Modifier.fillMaxWidth()) {
                                listOf("Distance", "Tour 400 m", "Total", "Écart / préc.").forEach {
                                Text(it, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            items(state.laps, key = { it.number }) { lap ->
                PassageRow(lap, state.corrections.find { it.lapNumber == lap.number },
                    canEdit = result != null && state.editingLapNumber == null, onEdit = { onEditLap(lap.number) })
            }
            if (result != null) item(key = "summary") { SummaryCard(result) }
        }
    }
}

@Composable
private fun StopwatchCard(state: RythmoState, onStart: () -> Unit, onPassage: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Prochain passage : ${state.nextDistance} m", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Text("Tour en cours", style = MaterialTheme.typography.labelLarge)
            Text(TimeFormat.duration(state.currentLapElapsedMs / 100 * 100),
                style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text("Temps total : ${TimeFormat.duration(state.elapsedMs / 100 * 100)}", style = MaterialTheme.typography.titleMedium)
            Text(if (state.timerRunning) "Chrono en cours · Touchez le bouton au passage du coureur."
                else "Prêt ? Démarrez le chrono au départ du coureur.", style = MaterialTheme.typography.bodyMedium)
            if (!state.timerRunning) {
                Button(onClick = onStart, enabled = state.cumulativeTimesMs.isEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                    Text("DÉMARRER LE CHRONO", fontWeight = FontWeight.Bold)
                }
            }
            Button(onClick = onPassage, enabled = state.timerRunning,
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp)) {
                Text("ENREGISTRER ${state.nextDistance} m", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            state.inputError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun TimeField(value: String, onChange: (String) -> Unit, label: String, description: String,
                      ime: ImeAction, actions: KeyboardActions, modifier: Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        label = { Text(label) }, placeholder = { Text("00") },
        textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
        modifier = modifier.heightIn(min = 76.dp).semantics { contentDescription = description },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ime),
        keyboardActions = actions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@Composable
private fun PassageRow(lap: LapResult, correction: LapCorrection? = null, canEdit: Boolean = false, onEdit: () -> Unit = {}) {
    val color = paceColor(lap.paceChange)
    val symbol = when (lap.paceChange) {
        PaceChange.FASTER -> "↑"
        PaceChange.SLOWER -> "↓"
        PaceChange.EQUIVALENT -> "="
        null -> ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${lap.distanceMeters} m", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(TimeFormat.duration(lap.durationMs), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(TimeFormat.duration(lap.cumulativeMs), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Column(Modifier.weight(1f)) {
                Text(lap.differenceMs?.let(TimeFormat::difference) ?: "—", color = color, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium)
                lap.paceChange?.let { Text("$symbol ${it.label}", color = color, style = MaterialTheme.typography.labelSmall) }
                if (lap.number > 1) Text("vs tour ${lap.number - 1}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (correction != null) {
            Text("Initial : ${TimeFormat.duration(correction.originalMs)} → Corrigé : ${TimeFormat.duration(correction.correctedMs)}\nModification utilisée · non modifiable",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (canEdit) {
            TextButton(onClick = onEdit, modifier = Modifier.align(Alignment.End)) { Text("Corriger ${lap.distanceMeters} m") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun SummaryCard(result: RaceResult) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Bilan de l’épreuve", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Temps total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(TimeFormat.duration(result.totalMs), style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            StatRow("Temps moyen / 400 m", TimeFormat.duration(result.averageLapMs))
            StatRow("Progression", TimeFormat.difference(result.progressionMs))
            StatRow("Irrégularité cumulée", TimeFormat.difference(result.cumulativeIrregularityMs).removePrefix("+"))
            Text("Progression : dernier tour − premier tour. Un écart négatif indique une amélioration.\nIrrégularité : somme des écarts absolus entre les tours.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Les statistiques tiennent compte des corrections confirmées.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BottomAction(content: @Composable () -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HelpButton(message: String) {
    val tooltip = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(message) } },
        state = tooltip,
    ) {
        IconButton(onClick = { scope.launch { tooltip.show() } },
            modifier = Modifier.semantics { contentDescription = "Aide sur cet écran" }) {
            Text("?", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, device = "spec:width=393dp,height=851dp,dpi=440")
@Composable
private fun IdentificationPreview() = RythmoTheme {
    IdentificationScreen(RythmoState(lastName = "Dupont", firstName = "Lucas", schoolClass = SCHOOL_CLASSES.first()), {}, {}, {}, {})
}

@Preview(showBackground = true, showSystemUi = true, device = "spec:width=393dp,height=851dp,dpi=440")
@Composable
private fun TimingPreview() = RythmoTheme {
    TimingScreen(previewState(listOf(89_000L, 178_000L, 265_000L)), {}, {}, { false }, {}, {})
}

@Preview(showBackground = true, showSystemUi = true, device = "spec:width=393dp,height=851dp,dpi=440")
@Composable
private fun CompletedPreview() = RythmoTheme {
    TimingScreen(previewState(listOf(89_000L, 178_000L, 265_000L, 354_000L, 441_000L)), {}, {}, { false }, {}, {})
}

@Preview(showBackground = true, showSystemUi = true, device = "spec:width=393dp,height=851dp,dpi=440")
@Composable
private fun AutomaticPreview() = RythmoTheme {
    TimingScreen(previewState(listOf(89_000L)).copy(timingMode = TimingMode.AUTOMATIC,
        timerStartedElapsedMs = 1L, elapsedMs = 102_400L), {}, {}, { false }, {}, {})
}

private fun previewState(times: List<Long>) = RythmoState(
    lastName = "Dupont", firstName = "Lucas", schoolClass = SCHOOL_CLASSES.first(),
    screen = RythmoScreen.RACE, cumulativeTimesMs = times,
)
