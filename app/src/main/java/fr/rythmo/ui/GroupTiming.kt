package fr.rythmo.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import fr.rythmo.domain.TimeFormat
import fr.rythmo.runnerLabels
import fr.rythmo.domain.PaceChange
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalViewConfiguration
import fr.rythmo.stopwatchTenths
import fr.rythmo.session.*
import kotlinx.coroutines.delay

@Composable
internal fun GroupClock(group: RaceGroup, elapsedMs: () -> Long) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${group.session.schoolClass} · ${group.session.distanceMeters} m", style = MaterialTheme.typography.bodySmall)
        if (group.complete) Text("Série terminée", style = MaterialTheme.typography.headlineMedium)
        else Text(stopwatchTenths(elapsedMs()), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.displayMedium)
        Text("${group.runners.count { it.finished(group.session) }} / ${group.runners.size} arrivées", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun RunnerGrid(group: RaceGroup, interrupted: Boolean, pdfErrors: Set<String>, modifier: Modifier = Modifier,
    onUndo: (RunnerRecord) -> Unit = {}, selectableIds: Set<String>? = null, selectedIds: Set<String> = emptySet(), onRunner: (RunnerRecord) -> Unit) {
    val labels = remember(group.runners.map { it.pupil }) { runnerLabels(group.runners) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 600.dp) 4 else 2
        LazyVerticalGrid(columns = GridCells.Fixed(columns), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(group.runners, key = { it.id }) { runner ->
                RunnerCard(group, runner, labels.getValue(runner.id), interrupted, runner.id in pdfErrors,
                    { onUndo(runner) }, selectableIds?.let { runner.id in it }, runner.id in selectedIds) { onRunner(runner) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RunnerCard(group: RaceGroup, runner: RunnerRecord, label: String, interrupted: Boolean, pdfError: Boolean,
    onUndo: () -> Unit, selectable: Boolean?, selected: Boolean, onClick: () -> Unit) {
    val count = runner.rawCumulativeMs.size
    var previous by remember(runner.id) { mutableIntStateOf(count) }
    var feedback by remember(runner.id) { mutableStateOf(false) }
    LaunchedEffect(count) {
        val accepted = count > previous
        previous = count
        feedback = accepted
        if (accepted) { delay(4000); feedback = false }
    }
    val lap = remember(runner, group.session) { runner.laps(group.session).lastOrNull() }
    val closed = runner.closed(group.session)
    val container = when (lap?.paceChange) {
        PaceChange.FASTER -> Color(0xFFDAF2E3)
        PaceChange.SLOWER -> Color(0xFFFFE1DC)
        PaceChange.EQUIVALENT -> Color(0xFFE1EDFF)
        null -> MaterialTheme.colorScheme.surfaceContainer
    }
    val symbol = when (lap?.paceChange) { PaceChange.FASTER -> "↑"; PaceChange.SLOWER -> "↓"; PaceChange.EQUIVALENT -> "="; null -> "" }
    val paceDescription = when (lap?.paceChange) {
        PaceChange.FASTER -> "Plus rapide"
        PaceChange.SLOWER -> "Plus lent"
        PaceChange.EQUIVALENT -> "Allure équivalente"
        null -> ""
    }
    val interaction = remember { MutableInteractionSource() }
    val shape = CardDefaults.shape
    val configuration = LocalViewConfiguration.current
    CompositionLocalProvider(LocalViewConfiguration provides object : androidx.compose.ui.platform.ViewConfiguration by configuration {
        override val longPressTimeoutMillis: Long = 1000
    }) {
        Card(modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp).clip(shape).semantics(mergeDescendants = true) {
            stateDescription = "$count sur ${group.session.distances.size} passages. $paceDescription"
            if (selectable == true) toggleableState = ToggleableState(selected)
        }.combinedClickable(
            interactionSource = interaction, indication = ripple(),
            role = if (selectable == true) Role.Checkbox else Role.Button,
            enabled = selectable ?: (closed || (group.claimed && group.startElapsedMs != null && !interrupted)),
            onClick = onClick, onLongClickLabel = "Annuler le dernier passage", onLongClick = if (selectable == null) onUndo else null),
            border = if (selectable == true && selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else if (feedback) BorderStroke(2.dp, Color(0xFF172033)) else null,
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = container, contentColor = Color(0xFF172033))) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (selectable == true) Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.size(24.dp))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(if (feedback && lap != null) "Tour ${TimeFormat.duration(lap.durationMs)}" else
                    runner.cumulativeMs(group.session).lastOrNull()?.let(::stopwatchTenths) ?: "—",
                    style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
                Text("$count / ${group.session.distances.size}" + if (feedback && lap?.differenceMs != null)
                    " · $symbol ${TimeFormat.difference(requireNotNull(lap.differenceMs))}" else " $symbol",
                    style = MaterialTheme.typography.labelLarge)
                if (closed) Text(when {
                    runner.abandoned -> "Abandon"
                    runner.pdfRevision == runner.revision -> "Arrivé · PDF"
                    pdfError -> "Arrivé · PDF à réessayer"
                    else -> "Arrivé · PDF…"
                }, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun previewGroup(complete: Boolean = false): RaceGroup {
    val pupils = listOf("Alice", "Basile", "Chloé", "David", "Emma", "Félix", "Gabrielle", "Hugo").mapIndexed { i, name ->
        Pupil(id = "preview-$i", firstName = name, lastName = "Exemple", sex = if (i % 2 == 0) Sex.GIRL else Sex.BOY)
    }
    val session = SessionConfig(schoolClass = "6e Mistral", level = "6e", distanceMeters = 1000, lapCount = 6, pupils = pupils)
    return RaceGroup(session = session, claimed = true, startElapsedMs = 1L, runners = pupils.mapIndexed { i, pupil ->
        val count = if (complete) 6 else i.coerceAtMost(6)
        RunnerRecord(pupil = pupil, rawCumulativeMs = (1..count).map { it * 60_000L }, pdfRevision = if (count == 6) 1 else 0)
    })
}

@Preview(showBackground = true, widthDp = 393, heightDp = 851)
@Preview(showBackground = true, widthDp = 900, heightDp = 900)
@Preview(showBackground = true, widthDp = 393, heightDp = 851, fontScale = 1.6f)
@Composable
private fun EightRunnersPreview() { TimingPreview(false) }

@Preview(showBackground = true, widthDp = 393, heightDp = 851)
@Composable
private fun FinishedGroupPreview() { TimingPreview(true) }

@Composable
private fun TimingPreview(complete: Boolean) {
    val group = remember { previewGroup(complete) }
    RythmoTheme { Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GroupClock(group) { 263_456 }
        RunnerGrid(group, false, emptySet(), Modifier.weight(1f)) {}
    } }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 851)
@Composable
private fun PreparationPreview() {
    val group = remember { previewGroup() }
    RythmoTheme {
        PreparationContent(ClientArchive(session = group.session), emptyList(), emptyList(), false, {}, { _, _, _ -> }, {}, {}, {})
    }
}
