package fr.rythmo.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fr.rythmo.*
import fr.rythmo.sync.*

@Composable
internal fun NearbyButton(label: String, enabled: Boolean = true, onReady: () -> Unit) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    val action by rememberUpdatedState(onReady)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        error = NearbyRequirements.problem(context)
        if (error == null) action()
    }
    Button(onClick = { launcher.launch(NearbyRequirements.permissions()) }, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(label) }
    error?.let {
        Text(it, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }) { Text("Ouvrir les réglages Android") }
    }
}

@Composable
internal fun NearbyPreparation(model: SessionViewModel) {
    val state by model.nearby.state.collectAsState()
    var name by remember(model.archive.deviceName) { mutableStateOf(model.archive.deviceName) }
    OutlinedTextField(name, { name = it.take(80) }, singleLine = true, label = { Text("Nom de cet appareil") }, modifier = Modifier.fillMaxWidth())
    NearbyButton(if (model.networkBusy) "Synchronisation…" else "Récupérer la séance", !model.networkBusy && name.isNotBlank()) {
        model.retrieveNearby(name)
    }
    Text(nearbyStatus(state), style = MaterialTheme.typography.bodySmall)
}

internal fun nearbyStatus(state: TransportState): String = state.error ?: when (state.phase) {
    ConnectionPhase.IDLE -> "À proximité · sans connexion Internet"
    ConnectionPhase.SEARCHING -> "Recherche du professeur…"
    ConnectionPhase.CONNECTING -> "Connexion…"
    ConnectionPhase.ASSOCIATING -> "Comparez le code sur les deux appareils."
    ConnectionPhase.CONNECTED -> "Connecté"
    ConnectionPhase.LOST -> "Connexion perdue · course conservée"
    ConnectionPhase.ERROR -> "Connexion impossible. Réessayez."
}

@Composable
internal fun NearbyDialogs(model: SessionViewModel, settings: TeacherSettingsViewModel) {
    val client by model.nearby.state.collectAsState()
    var hostTransport by remember { mutableStateOf(TeacherService.nearby) }
    LaunchedEffect(Unit) { while (true) { hostTransport = TeacherService.nearby; kotlinx.coroutines.delay(500) } }
    val host = hostTransport?.state?.collectAsState()?.value
    val hostAssociation = host?.associations?.firstOrNull()
    val association = hostAssociation ?: client.associations.firstOrNull()
    if (association != null && !(hostAssociation != null && settings.requested && !settings.unlocked)) {
        val isHost = hostAssociation != null
        AlertDialog(onDismissRequest = { model.confirmNearby(association.endpoint.id, false, isHost, settings) },
            title = { Text(if (isHost) "Autoriser cet appareil ?" else "Rejoindre ce professeur ?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(association.endpoint.name)
                Text(association.code, style = MaterialTheme.typography.headlineLarge)
                Text("Vérifiez que ce code est identique sur les deux appareils.")
            } },
            confirmButton = { TextButton(onClick = {
                if (isHost && !settings.unlocked) settings.requestSettings()
                else model.confirmNearby(association.endpoint.id, true, isHost, settings)
            }) { Text(if (isHost && !settings.unlocked) "Déverrouiller le professeur" else "Les codes correspondent") } },
            dismissButton = { TextButton(onClick = { model.confirmNearby(association.endpoint.id, false, isHost, settings) }) { Text("Refuser") } })
    } else if (model.nearbyPanel) {
        AlertDialog(onDismissRequest = model::dismissNearbyPanel,
            title = { Text("Récupérer la séance") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (model.networkBusy) "Synchronisation…" else nearbyStatus(client))
                if (client.phase == ConnectionPhase.SEARCHING) client.endpoints.forEach { endpoint ->
                    OutlinedButton(onClick = { model.nearby.connect(endpoint, model.archive.deviceName) }, modifier = Modifier.fillMaxWidth()) { Text(endpoint.name) }
                }
                if (client.connected.isNotEmpty() && !model.networkBusy) {
                    model.message?.let { Text(it) }
                    TextButton(onClick = model::download) { Text("Synchroniser la séance") }
                }
                if (client.phase in listOf(ConnectionPhase.ERROR, ConnectionPhase.LOST, ConnectionPhase.IDLE)) NearbyButton("Réessayer", onReady = model::retrieveNearby)
            } }, confirmButton = { TextButton(onClick = model::dismissNearbyPanel) { Text("Fermer") } })
    }
}
