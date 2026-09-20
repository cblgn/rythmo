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
) {
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
                    else TeacherScreen(model, settings, individual)
                }
                settings.individual -> RythmoApp(model = individual, onHome = settings::returnToGroup)
                archive.activeGroup != null -> GroupScreen(model, requireNotNull(archive.activeGroup))
                else -> PreparationScreen(model)
            }
        }
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
            Text("${session.date} · ${session.rubric.name}\nChoisissez de 1 à $MAX_GROUP_SIZE élèves. ")
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
    var selected by remember(captured) { mutableStateOf(captured?.eligibleIds ?: emptySet()) }
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
    var editing by remember(runner.id) { mutableStateOf<Int?>(null) }
    var minutes by remember(editing) { mutableStateOf("") }
    var seconds by remember(editing) { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(runner.revision) { editing = null }
    AlertDialog(onDismissRequest = dismiss, title = { Text(runner.pupil.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (runner.abandoned) "Abandon · non noté" else "Note provisoire : ${runner.grade(group.session)?.let(::formatPoints)} / ${formatPoints(group.session.rubric.maxGradeTenths)}")
                Text("${group.session.rubric.name} · La régularité n’influence pas encore la note.", style = MaterialTheme.typography.bodySmall)
                runner.laps(group.session).forEach { lap ->
                    HorizontalDivider()
                    Text("${group.session.passageLabel(lap.number)} · ${TimeFormat.duration(lap.durationMs)} · ${lap.differenceMs?.let(TimeFormat::difference) ?: "—"}", color = paceColor(lap.paceChange))
                    runner.corrections.find { it.number == lap.number }?.let {
                        Text("Initial ${TimeFormat.duration(it.originalMs)} → corrigé ${TimeFormat.duration(it.correctedMs)}\nModification utilisée", style = MaterialTheme.typography.bodySmall)
                    } ?: if (runner.finished(group.session)) {
                        TextButton(onClick = { editing = lap.number }) { Text("Corriger ${group.session.passageLabel(lap.number)}") }
                    } else Unit
                }
                editing?.let { number ->
                    Text("Une seule correction autorisée pour ce passage. Le professeur verra les deux temps.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(minutes, { minutes = it }, label = { Text("min") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                        OutlinedTextField(seconds, { seconds = it }, label = { Text("sec") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    }
                    Button(onClick = { model.correct(runner.id, number, minutes, seconds) }) { Text("Confirmer la correction") }
                    TextButton(onClick = { editing = null }) { ActionLabel(R.drawable.ic_close, "Annuler") }
                    model.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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

@Composable
private fun TeacherScreen(model: SessionViewModel, settings: TeacherSettingsViewModel, individual: RythmoViewModel) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(TeacherService.status) }
    var nearbyTransport by remember { mutableStateOf(TeacherService.nearby) }
    var addresses by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            status = TeacherService.status
            nearbyTransport = TeacherService.nearby
            addresses = runCatching { NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress }.joinToString("\n") { "https://${it.hostAddress}:8765" } }.getOrDefault("")
            delay(1000)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = settings::leaveSettings) { ActionLabel(R.drawable.ic_lock, "Retour aux élèves · verrouiller") }
        TextButton(onClick = settings::openIndividual) { Text("Évaluation individuelle · 2000 m") }
        if (model.raceRunning || individual.raceInProgress) Text("Le démarrage du serveur sera disponible après la course locale.")
        var serverUrl by remember { mutableStateOf(model.archive.serverUrl) }
        Text("Associer le professeur", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(serverUrl, { serverUrl = it }, label = { Text("Adresse HTTPS") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { model.inspectServer(serverUrl, settings) }, enabled = !model.networkBusy) { ActionLabel(R.drawable.ic_wifi, "Vérifier ce serveur") }
        model.candidateServer?.let { (url, pin) ->
            Text(fr.rythmo.sync.TeacherTls.verificationCode(pin), style = MaterialTheme.typography.titleLarge)
            Text("Comparez ce code à celui affiché sur le serveur professeur.")
            Button(onClick = { model.trustServer(settings) }) { ActionLabel(R.drawable.ic_check, "Les codes correspondent") }
            TextButton(onClick = model::dismissServerCandidate) { ActionLabel(R.drawable.ic_close, "Annuler") }
        }
        HorizontalDivider()
        Text("Préparer et collecter", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        NearbyButton("Rendre la séance disponible à proximité", !model.raceRunning && !individual.raceInProgress) {
            model.startTeacherServer(settings, individual, enableNearby = true)
        }
        nearbyTransport?.let { nearby ->
            val state by nearby.state.collectAsState()
            Text(if (state.error != null) nearbyStatus(state) else if (state.advertising) "Séance disponible · ${state.connected.size} appareil(s) connecté(s)" else "Préparation de Nearby…")
            state.connected.forEach { Text(it.name) }
        }
        Text("Le réseau local reste disponible pour les ordinateurs et les appareils sans Nearby.")
        Button(onClick = { model.startTeacherServer(settings, individual) }, enabled = !status.running && !model.raceRunning && !individual.raceInProgress, modifier = Modifier.fillMaxWidth()) {
            if (status.running) ServerIndicator() else ActionLabel(R.drawable.ic_play_arrow, "Démarrer le serveur enseignant")
        }
        if (status.running) {
            Text("Vérification : ${status.verificationCode}")
            Text("Code d’association : ${status.pairingCode}", style = MaterialTheme.typography.titleLarge)
            Text(addresses.ifEmpty { "Activez le Wi-Fi ou le point d’accès pour connecter d’autres appareils." })
            Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:8767/#${status.adminKey}"))) }, modifier = Modifier.fillMaxWidth()) { Text("Ouvrir classes, barèmes et résultats") }
            OutlinedButton(onClick = { model.stopTeacherServer(settings) }, modifier = Modifier.fillMaxWidth()) { ActionLabel(R.drawable.ic_stop, "Arrêter le serveur") }
        }
        status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }) { ActionLabel(R.drawable.ic_wifi, "Réglages Wi-Fi / point d’accès") }
        Text("Vous pouvez aussi utiliser le serveur PC. Le téléphone et le PC conservent chacun leurs propres séances : utilisez le même serveur au début et à la fin du cours.", style = MaterialTheme.typography.bodyMedium)
    }
}
