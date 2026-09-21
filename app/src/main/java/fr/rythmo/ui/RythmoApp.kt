package fr.rythmo.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.rythmo.PdfExporter
import fr.rythmo.RythmoScreen
import fr.rythmo.RythmoViewModel
import fr.rythmo.domain.TimingMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@Composable
fun RythmoApp(model: RythmoViewModel = viewModel(), onHome: (() -> Unit)? = null) {
    val state = model.state
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var pdfUri by rememberSaveable(state.student, state.cumulativeTimesMs, state.corrections) { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var pdfError by remember { mutableStateOf<String?>(null) }
    var resetRequested by rememberSaveable { mutableStateOf(false) }
    var requestedMode by rememberSaveable { mutableStateOf<TimingMode?>(null) }
    LaunchedEffect(state.student, state.cumulativeTimesMs) { pdfError = null }
    BackHandler(enabled = state.screen == RythmoScreen.RACE) { model.editStudent() }

    fun reset(mode: TimingMode? = null) {
        model.resetEvaluation(keepStudent = mode != null, timingMode = mode ?: state.timingMode)
        pdfUri = null
        pdfError = null
        snackbar.currentSnackbarData?.dismiss()
        resetRequested = false
        requestedMode = null
    }

    if (resetRequested || requestedMode != null) {
        AlertDialog(
            onDismissRequest = { resetRequested = false; requestedMode = null },
            title = { Text(if (requestedMode != null) "Changer de mode ?" else "Nouvelle évaluation ?") },
            text = { Text(if (requestedMode != null)
                "Le chronomètre et les passages seront remis à zéro. L’identité de l’élève sera conservée. Les PDF déjà générés restent inchangés."
            else "L’identité, le chronomètre et les passages seront effacés de cet écran. Les PDF déjà générés restent inchangés.") },
            confirmButton = { TextButton(onClick = { reset(requestedMode) }) { Text("Recommencer") } },
            dismissButton = { TextButton(onClick = { resetRequested = false; requestedMode = null }) { Text("Annuler") } },
        )
    }

    fun launchPdf(share: Boolean) {
        val uri = pdfUri?.let(Uri::parse) ?: return
        val intent = Intent(if (share) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
            if (share) { type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri) }
            else setDataAndType(uri, "application/pdf")
            clipData = ClipData.newRawUri("Bilan Rythmo", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(if (share) Intent.createChooser(intent, "Partager le bilan") else intent)
            pdfError = null
        } catch (_: ActivityNotFoundException) {
            pdfError = "Aucune application compatible. Utilisez Partager pour conserver le PDF."
        }
    }

    when (state.screen) {
        RythmoScreen.IDENTIFICATION -> IdentificationScreen(
            state, model::setLastName, model::setFirstName, model::setSchoolClass,
            onHome = onHome,
            onTimingMode = { mode ->
                if (mode != state.timingMode) {
                    if (state.hasTimingData) requestedMode = mode else model.setTimingMode(mode)
                }
            },
            onReset = { resetRequested = true },
            onStart = {
                val firstStart = state.startedAt == null
                model.startRace()
                if (firstStart && model.state.screen == RythmoScreen.RACE) scope.launch {
                    snackbar.showSnackbar("L’évaluation peut commencer · ${model.state.formattedStartTime}.")
                }
            },
        )
        RythmoScreen.RACE -> TimingScreen(
            state = state, onMinutes = model::setMinutes, onSeconds = model::setSeconds,
            onAdd = model::submitTime, onBack = model::editStudent,
            onStartStopwatch = model::startStopwatch,
            onAutomaticPassage = { model.recordAutomaticPassage() },
            onReset = { resetRequested = true },
            pdfAvailable = pdfUri != null, exporting = exporting, pdfError = pdfError,
            snackbar = snackbar,
            onOpenPdf = { launchPdf(false) }, onSharePdf = { launchPdf(true) },
            onValidate = {
                state.report?.let { report ->
                    exporting = true
                    pdfError = null
                    scope.launch {
                        try {
                            val file = withContext(Dispatchers.IO) { PdfExporter.export(context.applicationContext, report) }
                            if (model.state.report == report) {
                                pdfUri = FileProvider.getUriForFile(context, "${context.packageName}.files", file).toString()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: IOException) {
                            if (model.state.report == report) pdfError = "Le PDF n’a pas pu être créé. Réessayez."
                        } finally { exporting = false }
                    }
                }
            },
        )
    }
}
