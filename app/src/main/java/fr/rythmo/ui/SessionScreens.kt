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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.rythmo.*
import fr.rythmo.domain.TimeFormat
import fr.rythmo.session.*
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.NetworkInterface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RythmoWorkspace(model: SessionViewModel = viewModel()) {
    val archive = model.archive
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(model.message) {
        model.message?.let { value -> snack.showSnackbar(value, withDismissAction = true); if (model.message == value) model.dismissMessage() }
    }
    BackHandler(archive.role != AppRole.CHOICE) { model.chooseRole(AppRole.CHOICE) }
    if (archive.role == AppRole.INDIVIDUAL && model.ready) {
        RythmoApp(onHome = { model.chooseRole(AppRole.CHOICE) })
        return
    }
    Scaffold(topBar = {
        if (archive.role != AppRole.CHOICE) {
        TopAppBar(title = { Text(when (archive.role) {
            AppRole.TIMER -> "Rythmo · Chronométrage"
            AppRole.TEACHER -> "Rythmo · Enseignant"
            else -> "Rythmo"
        }, style = MaterialTheme.typography.titleLarge) }, navigationIcon = {
            if (archive.role != AppRole.CHOICE) TextButton(onClick = { model.chooseRole(AppRole.CHOICE) }) { Text("‹ Accueil") }
        })
        }
    }, snackbarHost = { SnackbarHost(snack) }, modifier = Modifier.fillMaxSize().imePadding()) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            when {
                !model.ready -> Column(Modifier.padding(24.dp)) {
                    if (model.loadError == null) { CircularProgressIndicator(); Text("Chargement des séances…") }
                    else Text(requireNotNull(model.loadError), color = MaterialTheme.colorScheme.error)
                }
                archive.role == AppRole.CHOICE -> WelcomeScreen(
                    onTiming = { model.chooseRole(AppRole.TIMER) }, onTeacher = { model.chooseRole(AppRole.TEACHER) },
                    onIndividual = { model.chooseRole(AppRole.INDIVIDUAL) })
                archive.role == AppRole.TEACHER -> TeacherScreen()
                archive.activeGroup != null -> GroupScreen(model, requireNotNull(archive.activeGroup))
                else -> PreparationScreen(model)
            }
        }
    }
    model.teacherRequest?.let { request -> TeacherActionDialog(model, request) }
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
        }, dismissButton = { TextButton(onClick = model::cancelTeacherAction, enabled = !model.checkingTeacher) { Text("Annuler") } })
}

@Composable
private fun PreparationScreen(model: SessionViewModel) {
    val archive = model.archive
    val session = archive.session
    var url by remember(archive.serverUrl) { mutableStateOf(archive.serverUrl) }
    var code by remember(archive.pairingCode) { mutableStateOf(archive.pairingCode) }
    var name by remember(archive.deviceName) { mutableStateOf(archive.deviceName) }
    var selected by remember(session?.id) { mutableStateOf(setOf<String>()) }
    val assigned = model.claims.filter { it.sessionId == session?.id }.flatMap { it.pupilIds }.toSet() +
        archive.groups.filter { it.session.id == session?.id }.flatMap { it.runners.map { r -> r.pupil.id } }
    Column(Modifier.fillMaxSize()) {
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Préparer le groupe", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Au début du cours, connectez-vous au même Wi-Fi que le professeur et récupérez la séance.")
        OutlinedTextField(name, { name = it }, label = { Text("Nom de cet appareil") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(url, { url = it }, label = { Text("Adresse du serveur") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = model::discover, enabled = !model.networkBusy, modifier = Modifier.fillMaxWidth()) { Text("Rechercher le professeur sur le Wi-Fi") }
        model.discovered.forEach { address -> TextButton(onClick = { url = address }) { Text(address) } }
        OutlinedTextField(code, { code = it }, label = { Text("Code d’association du professeur") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { model.connection(url, code, name); model.download() }, enabled = !model.networkBusy && name.isNotBlank() && code.isNotBlank(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(if (model.networkBusy) "Synchronisation…" else "Récupérer la séance") }
        if (session != null) {
            HorizontalDivider()
            Text("${session.schoolClass} · ${session.courseLabel}", style = MaterialTheme.typography.titleLarge)
            Text("${session.date} · ${session.rubric.name}\nChoisissez de 1 à $MAX_GROUP_SIZE élèves. Le groupe est réservé pendant cette synchronisation.")
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
            Button(onClick = model::requestUpload, enabled = !model.networkBusy, modifier = Modifier.fillMaxWidth()) { Text("Envoyer les bilans au professeur") }
            archive.groups.asReversed().forEach { group ->
                OutlinedButton(onClick = { model.selectGroup(group.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${group.session.schoolClass} · ${group.preparedAt.take(16).replace('T', ' ')} · ${group.runners.size} élèves")
                }
            }
        }
    }
    if (session != null) {
        Surface(tonalElevation = 3.dp) {
            Button(onClick = { model.prepare(selected) }, enabled = selected.isNotEmpty() && !model.networkBusy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 56.dp)) {
                Text("Préparer ${selected.size} élève(s)")
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
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${group.session.schoolClass} · ${group.session.courseLabel}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (group.complete) "Série terminée" else "Chrono : ${TimeFormat.duration(model.elapsedMs / 100 * 100)}", style = MaterialTheme.typography.titleLarge)
            Text("${group.runners.count { it.finished(group.session) }} arrivée(s)" +
                group.runners.count { it.abandoned }.takeIf { it > 0 }?.let { " · $it abandon(s)" }.orEmpty(), style = MaterialTheme.typography.bodySmall)
        }
        if (model.clockInterrupted && !group.complete) Text("Appareil redémarré : chrono interrompu. Clôturez la série ; les passages sont conservés.", color = MaterialTheme.colorScheme.error)
        if (!group.claimed) {
            Text("Groupe sauvegardé. La réservation auprès du professeur reste à confirmer.")
            Button(onClick = model::retryClaim, enabled = !model.networkBusy, modifier = Modifier.fillMaxWidth()) { Text("Réessayer la réservation") }
        } else if (group.startElapsedMs == null && !group.complete) {
            Button(onClick = model::startRace, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("DÉMARRER LA COURSE") }
        } else if (!group.complete) Text("Touchez la case du coureur à chacun de ses passages.", style = MaterialTheme.typography.bodySmall)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val columns = if (maxWidth >= 600.dp) 4 else 2
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                group.runners.chunked(columns).forEach { row ->
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { runner ->
                            val closed = runner.closed(group.session)
                            val laps = runner.laps(group.session)
                            Card(onClick = { if (closed) runnerDetail = runner.id else model.record(runner.id) },
                                enabled = closed || (group.claimed && group.startElapsedMs != null && !model.clockInterrupted),
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                colors = CardDefaults.cardColors(containerColor = if (closed) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.primaryContainer)) {
                                Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Center) {
                                    Text(runner.pupil.label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (!closed) {
                                        Text(group.session.passageLabel(runner.rawCumulativeMs.size + 1), style = MaterialTheme.typography.titleSmall)
                                        laps.lastOrNull()?.let { lap ->
                                            Text("Tour : ${TimeFormat.duration(lap.durationMs)}", style = MaterialTheme.typography.bodySmall)
                                            lap.differenceMs?.let { Text("${TimeFormat.difference(it)} / préc.", color = paceColor(lap.paceChange), style = MaterialTheme.typography.labelSmall) }
                                        }
                                    } else {
                                        Text(if (runner.abandoned) "Abandon · non noté" else "Arrivé · ${runner.grade(group.session)?.let(::formatPoints)} / ${formatPoints(group.session.rubric.maxGradeTenths)}", style = MaterialTheme.typography.bodyMedium)
                                        Text(when {
                                            runner.syncedRevision == runner.revision -> "Reçu par le professeur"
                                            runner.abandoned -> "À synchroniser"
                                            runner.pdfRevision == runner.revision -> "PDF prêt · Bilan"
                                            runner.id in model.pdfErrors -> "PDF à réessayer"
                                            else -> "Création du PDF…"
                                        }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (group.complete) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = model::newGroup, modifier = Modifier.weight(1f)) { Text("Autre groupe") }
                Button(onClick = model::requestUpload, enabled = !model.networkBusy, modifier = Modifier.weight(1f)) { Text(if (model.networkBusy) "Envoi…" else "Envoyer les bilans") }
            }
        } else TextButton(onClick = { closeRequested = true }, modifier = Modifier.fillMaxWidth()) { Text("Actions professeur · code requis") }
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
                    TextButton(onClick = { editing = null }) { Text("Annuler") }
                    model.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                if (runner.finished(group.session)) {
                    if (runner.pdfRevision == runner.revision) OutlinedButton(onClick = {
                        try {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", model.reportFile(runner))
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        } catch (_: ActivityNotFoundException) { localError = "Aucun lecteur PDF installé. Le bilan sera transmis au professeur." }
                    }) { Text("Ouvrir le PDF") }
                    else TextButton(onClick = model::retryReports) { Text("Réessayer le PDF") }
                }
                model.pdfErrors[runner.id]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = dismiss) { Text("Fermer") } })
}

@Composable
private fun TeacherScreen() {
    val context = LocalContext.current
    var status by remember { mutableStateOf(TeacherService.status) }
    var addresses by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            status = TeacherService.status
            addresses = runCatching { NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress }.joinToString("\n") { "http://${it.hostAddress}:8765" } }.getOrDefault("")
            delay(1000)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Préparer et collecter", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Cet appareil peut héberger la séance. Connectez les chronométreurs au même Wi-Fi ou au point d’accès de ce téléphone. Internet n’est pas nécessaire.")
        Button(onClick = { context.startForegroundService(Intent(context, TeacherService::class.java)) }, enabled = !status.running, modifier = Modifier.fillMaxWidth()) { Text(if (status.running) "Serveur actif" else "Démarrer le serveur enseignant") }
        if (status.running) {
            Text("Code d’association : ${status.pairingCode}", style = MaterialTheme.typography.titleLarge)
            Text(addresses.ifEmpty { "Activez le Wi-Fi ou le point d’accès pour connecter d’autres appareils." })
            Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:8765/#${status.adminKey}"))) }, modifier = Modifier.fillMaxWidth()) { Text("Ouvrir classes, barèmes et résultats") }
            OutlinedButton(onClick = { context.stopService(Intent(context, TeacherService::class.java)) }, modifier = Modifier.fillMaxWidth()) { Text("Arrêter le serveur") }
        }
        status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }) { Text("Réglages Wi-Fi / point d’accès") }
        Text("Vous pouvez aussi utiliser le serveur PC. Le téléphone et le PC conservent chacun leurs propres séances : utilisez le même serveur au début et à la fin du cours.", style = MaterialTheme.typography.bodyMedium)
    }
}
