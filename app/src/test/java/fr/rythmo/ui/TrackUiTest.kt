package fr.rythmo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import fr.rythmo.session.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h1000dp")
class TrackUiTest {
    @get:Rule val compose = createComposeRule()
    private fun group() = demoSession().let { s -> RaceGroup(session = s, startElapsedMs = 0, claimed = true, runners = s.pupils.take(2).map { RunnerRecord(pupil = it) }) }
    @Test fun `runner button records intended pupil and shows saved progress`() {
        var group by mutableStateOf(group())
        val first = group.runners.first()
        compose.setContent { RythmoTheme { Column { GroupClock(group) { 83_456 }; RunnerGrid(group, false, emptySet(), Modifier.weight(1f)) { runner ->
            group = group.copy(runners = group.runners.map { if (it.id == runner.id) it.copy(rawCumulativeMs = listOf(83_456)) else it })
        } } } }
        compose.onNodeWithText("1:23,4").assertExists()
        compose.onNodeWithText("${first.pupil.firstName} ${first.pupil.lastName.first()}.").performClick()
        compose.onNodeWithText("1 / 5", substring = true).assertExists()
        assertEquals(listOf(83_456L), group.runners.first().rawCumulativeMs)
        assertTrue(group.runners.last().rawCumulativeMs.isEmpty())
        compose.mainClock.advanceTimeBy(4500)
        compose.onAllNodesWithText("Tour ", substring = true).assertCountEquals(0)
    }
    @Test fun `finished and abandoned runners retain distinct accessible states`() {
        val initial = group()
        val group = initial.copy(runners = listOf(initial.runners[0].copy(rawCumulativeMs = listOf(80_000,160_000,240_000,320_000,400_000), pdfRevision = 1), initial.runners[1].copy(abandoned = true)))
        compose.setContent { RythmoTheme { Column { GroupClock(group) { 999_999 }; RunnerGrid(group, false, emptySet(), Modifier.weight(1f)) {} } } }
        compose.onNodeWithText("Série terminée").assertExists()
        compose.onNodeWithText("PDF prêt", substring = true).assertExists()
        compose.onNodeWithText("Abandon", substring = true).assertExists()
    }
}
