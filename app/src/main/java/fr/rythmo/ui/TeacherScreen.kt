package fr.rythmo.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fr.rythmo.*
import fr.rythmo.R
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.NetworkInterface

@Composable
internal fun TeacherScreen(model: SessionViewModel, settings: TeacherSettingsViewModel, individual: RythmoViewModel, onPickDocument: () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(TeacherService.status) }
    var transport by remember { mutableStateOf(TeacherService.nearby) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var serverUrl by rememberSaveable { mutableStateOf(model.archive.serverUrl) }
    val blocked = model.raceRunning || individual.raceInProgress
    LaunchedEffect(Unit) { while (true) { status = TeacherService.status; transport = TeacherService.nearby; delay(1000) } }
    fun console(destination: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:8767/?tab=$destination#${status.adminKey}")))
    }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            listOf("Séance", "Appareils", "Bilans").forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (tab) {
                0 -> TeacherPreparationForm(settings, blocked, onPickDocument, {
                    model.startTeacherServer(settings, individual, enableNearby = true)
                })
                1 -> {
                    Text("Appareils élèves", style = MaterialTheme.typography.headlineSmall)
                    transport?.let {
                        val nearby by it.state.collectAsState()
                        Text(if (nearby.advertising) "Séance disponible" else nearbyStatus(nearby))
                        if (nearby.connected.isEmpty()) Text("Aucun appareil connecté pour le moment.")
                        nearby.connected.forEach { endpoint -> ListItem(headlineContent = { Text(endpoint.name) }, supportingContent = { Text("Connecté") }) }
                    } ?: Text("Rendez la séance disponible depuis l’onglet Séance.")
                    OutlinedButton(enabled = status.running, onClick = { console("devices") }) { ActionLabel(R.drawable.ic_groups, "Voir les appareils connus") }
                }
                2 -> {
                    Text("Bilans de la classe", style = MaterialTheme.typography.headlineSmall)
                    if (!status.running) Button(onClick = { model.startTeacherServer(settings, individual) }, enabled = !blocked) { Text("Ouvrir mes bilans") }
                    Button(enabled = status.running, onClick = { console("reception") }, modifier = Modifier.fillMaxWidth()) { ActionLabel(R.drawable.ic_download, "Suivre les bilans reçus") }
                    OutlinedButton(enabled = status.running, onClick = { console("results") }, modifier = Modifier.fillMaxWidth()) { ActionLabel(R.drawable.ic_picture_as_pdf, "Résultats et PDF") }
                }
            }
            status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            HorizontalDivider()
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Masquer les options" else "Autres options") }
            if (advanced) {
                TextButton(onClick = settings::openIndividual) { Text("Évaluation individuelle · 2000 m") }
                Text("Connexion à un serveur PC", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(serverUrl, { serverUrl = it }, label = { Text("Adresse HTTPS") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { model.inspectServer(serverUrl, settings) }, enabled = !model.networkBusy) { Text("Vérifier le serveur") }
                model.candidateServer?.let { (_, pin) ->
                    Text(fr.rythmo.sync.TeacherTls.verificationCode(pin), style = MaterialTheme.typography.titleLarge)
                    Text("Comparez le code affiché sur le PC.")
                    Button(onClick = { model.trustServer(settings) }) { Text("Les codes correspondent") }
                    TextButton(onClick = model::dismissServerCandidate) { Text("Annuler") }
                }
                if (status.running) {
                    val addresses = remember(status.running) { runCatching { NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                        .filterIsInstance<Inet4Address>().filterNot { it.isLoopbackAddress }.joinToString("\n") { "https://${it.hostAddress}:8765" } }.getOrDefault("") }
                    Text("Réseau local : ${addresses.ifBlank { "indisponible" }}")
                    Text("Tunnel USB : https://127.0.0.1:8765")
                    Text("Code d’association : ${status.pairingCode}")
                    Text("Vérification : ${status.verificationCode}")
                }
            }
            if (status.running) OutlinedButton(onClick = { model.stopTeacherServer(settings) }, modifier = Modifier.fillMaxWidth()) { ActionLabel(R.drawable.ic_stop, "Arrêter le partage de séance") }
        }
        TextButton(onClick = settings::leaveSettings, modifier = Modifier.fillMaxWidth().padding(8.dp)) { ActionLabel(R.drawable.ic_lock, "Retour aux élèves") }
    }
}
