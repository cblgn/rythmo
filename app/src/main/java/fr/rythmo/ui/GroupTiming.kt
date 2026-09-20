package fr.rythmo.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.painterResource
import fr.rythmo.R
import fr.rythmo.runnerGridColumns
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
        val fontScale = LocalDensity.current.fontScale
        val columns = runnerGridColumns(maxWidth.value.toInt(), maxHeight.value.toInt(), group.runners.size, fontScale)
        val rows = (group.runners.size + columns - 1) / columns
        val minimumHeight = if (group.runners.any { it.closed(group.session) }) 160 else if (labels.values.any { it.length > 20 }) 144 else 112
        val cellHeight = ((maxHeight - 8.dp * (rows - 1)) / rows).coerceIn((minimumHeight * fontScale).dp, (260 * fontScale).dp)
        LazyVerticalGrid(columns = GridCells.Fixed(columns), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(group.runners, key = { it.id }) { runner ->
                RunnerCard(group, runner, labels.getValue(runner.id), cellHeight, interrupted, runner.id in pdfErrors,
                    { onUndo(runner) }, selectableIds?.let { runner.id in it }, runner.id in selectedIds) { onRunner(runner) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RunnerCard(group: RaceGroup, runner: RunnerRecord, label: String, cellHeight: androidx.compose.ui.unit.Dp, interrupted: Boolean, pdfError: Boolean,
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
    val container = when {
        closed -> MaterialTheme.colorScheme.surfaceContainerHigh
        lap?.paceChange == PaceChange.FASTER -> Color(0xFFDAF2E3)
        lap?.paceChange == PaceChange.SLOWER -> Color(0xFFFFE1DC)
        lap?.paceChange == PaceChange.EQUIVALENT -> Color(0xFFE1EDFF)
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val symbol = when (lap?.paceChange) { PaceChange.FASTER -> "↑"; PaceChange.SLOWER -> "↓"; PaceChange.EQUIVALENT -> "="; null -> "" }
    val paceDescription = when (lap?.paceChange) {
        PaceChange.FASTER -> "Plus rapide"
        PaceChange.SLOWER -> "Plus lent"
        PaceChange.EQUIVALENT -> "Allure équivalente"
        null -> ""
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val elevation by animateDpAsState(if (pressed) 6.dp else 2.dp, tween(100), label = "Pupil press")
    val shape = CardDefaults.shape
    val configuration = LocalViewConfiguration.current
    CompositionLocalProvider(LocalViewConfiguration provides object : androidx.compose.ui.platform.ViewConfiguration by configuration {
        override val longPressTimeoutMillis: Long = 1000
    }) {
        Card(modifier = Modifier.fillMaxWidth().heightIn(min = cellHeight).semantics(mergeDescendants = true) {
            stateDescription = "$count sur ${group.session.distances.size} passages. " + if (runner.abandoned) "Abandon" else if (closed) "Arrivé" else paceDescription
            if (selectable == true) toggleableState = ToggleableState(selected)
        },
            border = if (selectable == true && selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else if (feedback) BorderStroke(2.dp, Color(0xFF172033)) else null,
            shape = shape, elevation = CardDefaults.cardElevation(defaultElevation = elevation),
            colors = CardDefaults.cardColors(containerColor = container, contentColor = Color(0xFF172033))) {
            Column(Modifier.fillMaxWidth().height(cellHeight).clip(shape).combinedClickable(
                interactionSource = interaction, indication = ripple(),
                role = if (selectable == true) Role.Checkbox else Role.Button,
                enabled = selectable ?: (closed || (group.claimed && group.startElapsedMs != null && !interrupted)),
                onClick = onClick, onLongClickLabel = "Annuler le dernier passage",
                onLongClick = if (selectable == null) onUndo else null
            ).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (selectable == true) Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.align(Alignment.End).size(24.dp))
                Spacer(Modifier.weight(1f))
                Text(label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center, maxLines = 3, modifier = Modifier.fillMaxWidth())
                if (closed) {
                    Text(if (runner.abandoned) "Abandon" else "Arrivé", style = MaterialTheme.typography.labelLarge)
                    if (!runner.abandoned) Text(stopwatchTenths(runner.cumulativeMs(group.session).last()),
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                } else if (feedback && lap != null) {
                    Text("Tour ${TimeFormat.duration(lap.durationMs)}", style = MaterialTheme.typography.bodyMedium)
                    lap.differenceMs?.let { Text("$symbol ${TimeFormat.difference(it)}", style = MaterialTheme.typography.labelMedium) }
                }
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (closed) Icon(painterResource(if (runner.abandoned) R.drawable.ic_stop else R.drawable.ic_flag), contentDescription = null, modifier = Modifier.size(20.dp))
                    else Text(symbol, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f))
                    Text("$count / ${group.session.distances.size}", style = MaterialTheme.typography.labelLarge)
                }
                if (closed && !runner.abandoned) Text(when {
                    runner.pdfRevision == runner.revision -> "PDF prêt"
                    pdfError -> "PDF à réessayer"
                    else -> "Création du PDF…"
                }, style = MaterialTheme.typography.labelSmall)
                LinearProgressIndicator(progress = { count.toFloat() / group.session.distances.size },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp), color = Color(0xFF55504B), trackColor = MaterialTheme.colorScheme.outlineVariant)
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
@Preview(showBackground = true, widthDp = 851, heightDp = 393)
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
