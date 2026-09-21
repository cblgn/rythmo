package fr.rythmo.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.rythmo.*
import fr.rythmo.R
import fr.rythmo.domain.TimeFormat
import fr.rythmo.session.*
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.NetworkInterface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RythmoWorkspace(
    model: SessionViewModel = viewModel(),
    settings: TeacherSettingsViewModel,
    individual: RythmoViewModel = viewModel(),
    onDocument: (Uri) -> Unit = {},
) {
    val documentPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onDocument) }
    val onPickDocument = { documentPicker.launch(arrayOf("text/*", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream", "application/vnd.ms-excel")) }
    val archive = model.archive
    val snack = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var serverActive by remember { mutableStateOf(TeacherService.status.running) }
    LaunchedEffect(Unit) { while (true) { serverActive = TeacherService.status.running; delay(1000) } }
    LaunchedEffect(model.ready) {
        if (model.ready) settings.restoreNavigation(archive.activeGroup == null && individual.raceInProgress)
    }
    LaunchedEffect(model.message) {
        model.message?.let { value -> snack.showSnackbar(value, withDismissAction = true); if (model.message == value) model.dismissMessage() }
    }
    val setupRequired = model.ready && settings.ready && !settings.setupComplete && !model.raceRunning && !individual.raceInProgress
    BackHandler(settings.requested || settings.individual || setupRequired) {
        if (!setupRequired && !settings.busy) {
            if (settings.requested) settings.leaveSettings() else settings.returnToGroup()
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_rythmo),
                    contentDescription = "Logo Rythmo", modifier = Modifier.size(36.dp))
                Column {
                    Text("Rythmo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic, letterSpacing = (-1).sp)
                    Text(if (settings.requested) "Professeur" else "Élève", style = MaterialTheme.typography.labelSmall)
                    if (serverActive) ServerIndicator()
                }
            }
        }, actions = {
            IconButton(onClick = { menuOpen = true }, enabled = model.ready && settings.ready) {
                Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_more_vert), contentDescription = "Menu")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Accès professeur") }, leadingIcon = { Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_school), contentDescription = null) }, onClick = { menuOpen = false; settings.requestSettings() })
            }
        })
    }, snackbarHost = { SnackbarHost(snack) }, modifier = Modifier.fillMaxSize().imePadding()) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            when {
                !model.ready || !settings.ready -> Column(Modifier.padding(24.dp)) {
                    val error = model.loadError ?: settings.error
                    if (error == null) { CircularProgressIndicator(); Text("Chargement des séances…") }
                    else Text(error, color = MaterialTheme.colorScheme.error)
                }
                setupRequired || settings.requested -> {
                    if (!settings.unlocked || settings.recoveryCode != null) TeacherLockScreen(settings, setupRequired)
                    else TeacherScreen(model, settings, individual, onPickDocument)
                }
                settings.individual -> RythmoApp(model = individual, onHome = settings::returnToGroup)
                archive.activeGroup != null -> GroupScreen(model, requireNotNull(archive.activeGroup))
                else -> PreparationScreen(model)
            }
        }
    }
    if (settings.stopServerRequested && settings.unlocked && settings.recoveryCode == null) {
        AlertDialog(onDismissRequest = settings::cancelServerStop,
            title = { Text("Arrêter le partage de séance ?") },
            text = { Text("Les courses et les bilans restent enregistrés.") },
            confirmButton = { TextButton(onClick = { model.stopTeacherServer(settings); settings.cancelServerStop() }) { Text("Arrêter") } },
            dismissButton = { TextButton(onClick = settings::cancelServerStop) { Text("Continuer le partage") } })
    }
    NearbyDialogs(model, settings)
    model.teacherRequest?.let { request -> TeacherActionDialog(model, request) }
}

@Composable
private fun ServerIndicator() {
    Row(Modifier.clearAndSetSemantics { contentDescription = "Serveur enseignant en ligne" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("●", color = PaceColors.faster, fontSize = 10.sp)
        Text("En ligne", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TeacherActionDialog(model: SessionViewModel, request: TeacherRequest) {
    var code by remember(request) { mutableStateOf("") }
    val pupil = model.archive.activeGroup?.runners?.find { it.id == request.runnerId }?.pupil?.label
    AlertDialog(onDismissRequest = model::cancelTeacherAction,
        title = { Text("Action réservée au professeur") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(when (request.action) {
                    TeacherAction.CLOSE_GROUP -> "Clôturer cette série et envoyer les bilans disponibles. Les élèves encore en course seront marqués en abandon, sans note. En l’absence de réseau, les bilans restent sur l’appareil pour un envoi ultérieur."
                    TeacherAction.ABANDON_RUNNER -> "Enregistrer l’abandon de $pupil (blessure, arrêt…). Les passages déjà saisis seront conservés. Les autres élèves continuent leur course."
                    TeacherAction.UPLOAD -> "Envoyer les bilans terminés et leurs PDF au serveur enseignant. Les copies locales restent conservées."
                })
                if (model.archive.teacherAccess == null) {
                    Text("Cette ancienne séance doit récupérer la protection professeur une première fois.")
                    Button(onClick = model::download, enabled = !model.networkBusy) { Text("Synchroniser la protection") }
                } else {
                    OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("Code professeur · 6 chiffres") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    Text("Ce code figure dans l’espace enseignant du serveur. Il est différent du code d’association.", style = MaterialTheme.typography.bodySmall)
                }
                model.teacherError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            TextButton(onClick = { model.confirmTeacherAction(code) }, enabled = code.length == 6 && !model.checkingTeacher) { Text(if (model.checkingTeacher) "Vérification…" else "Confirmer") }
        }, dismissButton = { TextButton(onClick = model::cancelTeacherAction, enabled = !model.checkingTeacher) { ActionLabel(R.drawable.ic_close, "Annuler") } })
}

@Composable
private fun PreparationScreen(model: SessionViewModel) {
    PreparationContent(model.archive, model.claims, model.discovered, model.networkBusy,
        model::discover, { url, code, name -> model.connection(url, code, name); model.download() },
        model::requestUpload, model::selectGroup, model::prepare,
        nearbyControls = if (model.useNearby) ({ NearbyPreparation(model) }) else null,
        connectionChoice = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = model.useNearby, onClick = { model.selectTransport(true) }, label = { Text("À proximité") })
                FilterChip(selected = !model.useNearby, onClick = { model.selectTransport(false) }, label = { Text("Réseau local / PC") })
            }
        })
}

@Composable
internal fun PreparationContent(
    archive: ClientArchive, claims: List<fr.rythmo.sync.GroupClaim>, discovered: List<String>, networkBusy: Boolean,
    onDiscover: () -> Unit, onDownload: (String, String, String) -> Unit,
    onUpload: () -> Unit, onSelectGroup: (String) -> Unit, onPrepare: (Set<String>) -> Unit,
    nearbyControls: (@Composable () -> Unit)? = null,
    connectionChoice: @Composable () -> Unit = {},
) {
    val session = archive.session
    var url by remember(archive.serverUrl) { mutableStateOf(archive.serverUrl) }
    var code by remember(archive.pairingCode) { mutableStateOf(archive.pairingCode) }
    var name by remember(archive.deviceName) { mutableStateOf(archive.deviceName) }
    var selected by remember(session?.id) { mutableStateOf(setOf<String>()) }
    val assigned = claims.filter { it.sessionId == session?.id }.flatMap { it.pupilIds }.toSet() +
        archive.groups.filter { it.session.id == session?.id }.flatMap { it.runners.map { r -> r.pupil.id } }
    Column(Modifier.fillMaxSize()) {
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Préparer le groupe", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        connectionChoice()
        if (nearbyControls != null) nearbyControls() else {
        Text("Au début du cours, connectez-vous au même Wi-Fi que le professeur et récupérez la séance.")
        OutlinedTextField(name, { name = it }, label = { Text("Nom de cet appareil") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(url, { url = it }, label = { Text("Adresse du serveur") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = onDiscover, enabled = !networkBusy, modifier = Modifier.fillMaxWidth()) { ActionLabel(R.drawable.ic_wifi, "Rechercher le professeur sur le Wi-Fi") }
        discovered.forEach { address -> TextButton(onClick = { url = address }) { Text(address) } }
        OutlinedTextField(code, { code = it }, label = { Text("Code d’association du professeur") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onDownload(url, code, name) }, enabled = !networkBusy && name.isNotBlank() && code.isNotBlank(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(if (networkBusy) "Synchronisation…" else "Récupérer la séance") }
        }
        if (session != null) {
            HorizontalDivider()
            Text("${session.schoolClass} · ${session.courseLabel}", style = MaterialTheme.typography.titleLarge)
            Text("${session.date} · ${session.rubricName}\nChoisissez de 1 à $MAX_GROUP_SIZE élèves. ")
            session.pupils.forEach { pupil ->
                val enabled = pupil.id !in assigned && (pupil.id in selected || selected.size < MAX_GROUP_SIZE)
                Row(Modifier.fillMaxWidth().clickable(enabled = enabled) {
                    selected = if (pupil.id in selected) selected - pupil.id else selected + pupil.id
                }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = pupil.id in selected, onCheckedChange = null, enabled = enabled)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(pupil.label, color = if (pupil.id in assigned) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        Text(if (pupil.id in assigned) "Déjà affecté à un groupe" else if (pupil.sex == Sex.GIRL) "Fille" else "Garçon", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (archive.groups.isNotEmpty()) {
            HorizontalDivider()
            Text("Séries conservées", style = MaterialTheme.typography.titleLarge)
            Button(onClick = onUpload, enabled = !networkBusy, modifier = Modifier.fillMaxWidth()) { ActionLabel(R.drawable.ic_upload, "Envoyer les bilans au professeur") }
            archive.groups.asReversed().forEach { group ->
                OutlinedButton(onClick = { onSelectGroup(group.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${group.session.schoolClass} · ${group.preparedAt.take(16).replace('T', ' ')} · ${group.runners.size} élèves")
                }
            }
        }
    }
    if (session != null) {
        Surface(tonalElevation = 3.dp) {
            Button(onClick = { onPrepare(selected) }, enabled = selected.isNotEmpty() && !networkBusy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 56.dp)) {
                ActionLabel(R.drawable.ic_check, "Valider le groupe · ${selected.size} élèves")
            }
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupScreen(model: SessionViewModel, group: RaceGroup) {
    var runnerDetail by remember(group.id) { mutableStateOf<String?>(null) }
    var closeRequested by remember { mutableStateOf(false) }
    var undoRunner by remember { mutableStateOf<String?>(null) }
    val view = LocalView.current
    DisposableEffect(group.startElapsedMs, group.complete, view) {
        val old = view.keepScreenOn
        if (group.startElapsedMs != null && !group.complete) view.keepScreenOn = true
        onDispose { view.keepScreenOn = old }
    }
    if (closeRequested) AlertDialog(onDismissRequest = { closeRequested = false },
        title = { Text("Actions professeur") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Un code professeur est demandé pour confirmer chaque action.")
                group.runners.filterNot { it.closed(group.session) }.forEach { runner ->
                    OutlinedButton(onClick = { closeRequested = false; model.requestAbandon(runner.id) }, modifier = Modifier.fillMaxWidth()) { Text("Abandon · ${runner.pupil.label}") }
                }
                Button(onClick = { closeRequested = false; model.requestCloseGroup() }, modifier = Modifier.fillMaxWidth()) { Text("Clôturer et envoyer les bilans") }
            }
        }, confirmButton = { TextButton(onClick = { closeRequested = false }) { Text("Revenir à la course") } })
    runnerDetail?.let { id ->
        val runner = group.runners.first { it.id == id }
        RunnerDialog(model, group, runner) { runnerDetail = null }
    }
    undoRunner?.let { id ->
        AlertDialog(onDismissRequest = { undoRunner = null }, title = { Text("Annuler ce passage ?") },
            text = { Text(group.runners.first { it.id == id }.pupil.label) },
            confirmButton = { TextButton(onClick = { model.undoPassage(id); undoRunner = null }) { Text("Annuler le passage") } },
            dismissButton = { TextButton(onClick = { undoRunner = null }) { Text("Conserver") } })
    }
    val captured = model.capturedPassage
    var selected by rememberSaveable(group.id, captured?.elapsedMs) { mutableStateOf(captured?.eligibleIds ?: emptySet()) }
    BackHandler(captured != null) { model.dismissCapture() }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GroupClock(group) { model.elapsedMs }
        if (model.clockInterrupted && !group.complete) Text("Appareil redémarré : chrono interrompu. Clôturez la série ; les passages sont conservés.", color = MaterialTheme.colorScheme.error)
        if (model.useNearby && (group.complete || group.startElapsedMs == null)) {
            val nearbyState by model.nearby.state.collectAsState()
            if (nearbyState.connected.isEmpty()) NearbyButton("Reconnecter le professeur", !model.networkBusy, model::retrieveNearby)
        }
        if (!group.claimed) {
            Text("Groupe enregistré · validation à terminer.")
            Button(onClick = model::retryClaim, enabled = !model.networkBusy, modifier = Modifier.fillMaxWidth()) { Text("Réessayer la validation") }
        } else if (group.startElapsedMs == null && !group.complete) {
            Button(onClick = model::startRace, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { ActionLabel(R.drawable.ic_play_arrow, "DÉMARRER LA COURSE") }
        }
        RunnerGrid(group, model.clockInterrupted, model.pdfErrors.keys, Modifier.weight(1f),
            onUndo = { if (captured == null && model.canUndo(it.id)) undoRunner = it.id },
            selectableIds = captured?.eligibleIds, selectedIds = selected) { runner ->
            if (captured != null) selected = if (runner.id in selected) selected - runner.id else selected + runner.id
            else if (runner.closed(group.session)) runnerDetail = runner.id else model.record(runner.id)
        }
        if (group.complete) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = model::newGroup, modifier = Modifier.weight(1f)) { ActionLabel(R.drawable.ic_groups, "Autre groupe") }
                Button(onClick = model::requestUpload, enabled = !model.networkBusy, modifier = Modifier.weight(1f)) { ActionLabel(R.drawable.ic_upload, if (model.networkBusy) "Envoi…" else "Envoyer les bilans") }
            }
        } else {
            if (captured != null) Text("Passage à ${stopwatchTenths(captured.elapsedMs)}", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (captured == null) model.capturePassage() else model.confirmCapture(selected) },
                    enabled = group.startElapsedMs != null && !model.clockInterrupted && (captured == null || selected.isNotEmpty()),
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    ActionLabel(if (captured == null) R.drawable.ic_groups else R.drawable.ic_check, if (captured == null) "Passage groupé" else "Valider · ${selected.size}")
                }
                TextButton(onClick = { if (captured == null) closeRequested = true else model.dismissCapture() },
                    modifier = Modifier.widthIn(min = 110.dp).heightIn(min = 56.dp)) {
                    ActionLabel(if (captured == null) R.drawable.ic_school else R.drawable.ic_close, if (captured == null) "Professeur" else "Annuler")
                }
            }
        }
    }
}

@Composable
private fun RunnerDialog(model: SessionViewModel, group: RaceGroup, runner: RunnerRecord, dismiss: () -> Unit) {
    val context = LocalContext.current
    var localError by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(runner.pupil.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (runner.abandoned) "Abandon · non noté" else "Note : ${runner.grade(group.session)?.let(::formatPoints)} / ${formatPoints(group.session.maxGradeTenths)}")
                Text(group.session.rubricName, style = MaterialTheme.typography.bodySmall)
                runner.assessmentScore(group.session)?.let { score ->
                    Text("Performance ${formatPoints(score.performanceTenths)} / ${formatPoints(score.performanceMaxTenths)}")
                    Text("Comparaison des tours ${formatPoints(score.comparisonTenths)} / ${formatPoints(score.comparisonMaxTenths)}")
                    Text("Note sur 20 : ${formatPoints(score.outOf20Tenths)}", fontWeight = FontWeight.Bold)
                }
                runner.laps(group.session).forEach { lap ->
                    HorizontalDivider()
                    Text("${group.session.passageLabel(lap.number)} · ${TimeFormat.duration(lap.durationMs)} · ${lap.differenceMs?.let(TimeFormat::difference) ?: "—"}", color = paceColor(lap.paceChange))
                    runner.corrections.find { it.number == lap.number }?.let {
                        Text("Initial ${TimeFormat.duration(it.originalMs)} → corrigé ${TimeFormat.duration(it.correctedMs)}\nModification utilisée", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (runner.finished(group.session)) {
                    if (runner.pdfRevision == runner.revision) OutlinedButton(onClick = {
                        try {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", model.reportFile(runner))
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        } catch (_: ActivityNotFoundException) { localError = "Aucun lecteur PDF installé. Le bilan sera transmis au professeur." }
                    }) { ActionLabel(R.drawable.ic_picture_as_pdf, "Ouvrir le PDF") }
                    else TextButton(onClick = model::retryReports) { Text("Réessayer le PDF") }
                }
                model.pdfErrors[runner.id]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = dismiss) { Text("Fermer") } })
}
