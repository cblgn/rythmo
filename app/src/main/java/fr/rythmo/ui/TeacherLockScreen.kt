package fr.rythmo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import fr.rythmo.R
import fr.rythmo.TeacherSettingsViewModel

@Composable
fun TeacherLockScreen(model: TeacherSettingsViewModel, mandatory: Boolean) {
    var recovering by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf("") }
    var kept by remember(model.recoveryCode) { mutableStateOf(false) }
    val creating = !model.configured || recovering
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Accès professeur", style = MaterialTheme.typography.headlineSmall)
        if (model.recoveryCode != null && model.unlocked) {
            Text("Code de secours · à conserver pour réinitialiser votre PIN.")
            Text(requireNotNull(model.recoveryCode), style = MaterialTheme.typography.titleLarge)
            model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row { Checkbox(kept, { kept = it }); Text("J’ai conservé mon code de secours", Modifier.padding(top = 12.dp)) }
            Button(onClick = model::acknowledgeRecovery, enabled = kept && !model.busy) { ActionLabel(R.drawable.ic_lock, "Terminer et verrouiller") }
        } else {
            if (creating) Text("Choisissez un PIN à six chiffres.")
            if (recovering) {
                OutlinedTextField(recovery, { recovery = it }, label = { Text("Code de secours") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text("La récupération conserve vos séances et PDF.")
            }
            OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(6) }, label = { Text(if (creating) "Nouveau PIN" else "PIN professeur") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (creating) OutlinedTextField(confirmation, { confirmation = it.filter(Char::isDigit).take(6) }, label = { Text("Confirmer le PIN") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
            model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { model.submit(pin, confirmation, if (recovering) recovery else null); pin = ""; confirmation = ""; recovery = "" },
                enabled = !model.busy && pin.length == 6 && (!creating || confirmation.length == 6), modifier = Modifier.fillMaxWidth()) {
                Text(if (model.busy) "Vérification…" else if (creating) "Enregistrer le PIN" else "Déverrouiller")
            }
            if (model.configured) TextButton(onClick = { recovering = !recovering; pin = ""; confirmation = "" }, enabled = !model.busy) {
                Text(if (recovering) "Revenir au PIN" else "PIN oublié")
            }
            if (!mandatory) TextButton(onClick = model::leaveSettings, enabled = !model.busy) { ActionLabel(R.drawable.ic_arrow_back, "Revenir aux élèves") }
        }
    }
}
