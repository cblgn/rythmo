package fr.rythmo

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import fr.rythmo.domain.TimingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StopwatchTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private var now = 10_000L

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() {
        stores.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    private fun model(handle: SavedStateHandle = SavedStateHandle()) = RythmoViewModel(handle) { now }.also {
        stores += ViewModelStore().apply { put("test", it) }
    }

    private fun ready(handle: SavedStateHandle = SavedStateHandle()) = model(handle).apply {
        setLastName("Dupont"); setFirstName("Lucas"); setSchoolClass("3e Ouessant")
        setTimingMode(TimingMode.AUTOMATIC)
        startRace()
    }

    @Test fun `stopwatch records five exact cumulative passages and stops at 2000 m`() {
        val model = ready()
        assertFalse(model.recordAutomaticPassage())
        assertFalse(model.state.timerRunning)
        model.startStopwatch()
        val start = now
        assertNotNull(model.state.chronoStartedAt)
        assertFalse(model.recordAutomaticPassage()) // No zero-length lap.
        listOf(89_000L, 178_000L, 265_000L, 354_000L, 441_000L).forEach {
            now = start + it
            assertTrue(model.recordAutomaticPassage())
        }
        assertEquals(listOf(89_000L, 89_000L, 87_000L, 89_000L, 87_000L), model.state.laps.map { it.durationMs })
        assertEquals(listOf(null, 0L, -2_000L, 2_000L, -2_000L), model.state.laps.map { it.differenceMs })
        assertEquals(441_000L, model.state.result?.totalMs)
        assertFalse(model.state.timerRunning)
        now += 5_000L
        assertFalse(model.recordAutomaticPassage())
        model.startStopwatch()
        assertEquals(441_000L, model.state.elapsedMs)
        assertEquals(TimingMode.AUTOMATIC, model.state.report?.timingMode)
    }

    @Test fun `elapsed clock survives delayed rendering and saved state restoration`() {
        val handle = SavedStateHandle()
        val original = ready(handle)
        original.startStopwatch()
        now += 83_123L
        assertTrue(original.recordAutomaticPassage())
        stores.forEach { it.clear() }
        now += 85_456L
        val restored = model(handle)
        assertTrue(restored.state.timerRunning)
        dispatcher.scheduler.runCurrent()
        assertEquals(168_579L, restored.state.elapsedMs)
        assertTrue(restored.recordAutomaticPassage())
        assertEquals(85_456L, restored.state.laps[1].durationMs)
        assertEquals(2_333L, restored.state.laps[1].differenceMs)
        assertEquals(original.state.chronoStartedAt, restored.state.chronoStartedAt)
        restored.startStopwatch() // Never restart an active clock.
        assertEquals(10_000L, restored.state.timerStartedElapsedMs)
    }

    @Test fun `mode is protected while stopwatch is running and automatic mode rejects manual additions`() {
        val model = ready()
        model.startStopwatch()
        model.setTimingMode(TimingMode.MANUAL)
        assertEquals(TimingMode.AUTOMATIC, model.state.timingMode)
        model.setMinutes("1"); model.setSeconds("29")
        assertFalse(model.addPassage())
        assertTrue(model.state.laps.isEmpty())
        val manual = model().apply {
            setLastName("Dupont"); setFirstName("Lucas"); setSchoolClass("3e Hoedic"); startRace()
        }
        manual.startStopwatch()
        assertFalse(manual.state.timerRunning)
        assertNull(manual.state.chronoStartedAt)
    }

    @Test fun `automatic differences compare the current lap to its immediate predecessor`() {
        val model = ready()
        model.startStopwatch()
        listOf(83_000L, 85_000L, 81_000L, 81_000L, 90_000L).forEach {
            now += it
            dispatcher.scheduler.runCurrent()
            assertTrue(model.recordAutomaticPassage())
            assertEquals(0L, model.state.currentLapElapsedMs)
        }
        assertEquals(listOf(null, 2_000L, -4_000L, 0L, 9_000L), model.state.laps.map { it.differenceMs })
        assertEquals(420_000L, model.state.result?.totalMs)
    }

    @Test fun `reset cancels the running clock and restores a fresh selectable evaluation`() {
        val handle = SavedStateHandle()
        val model = ready(handle)
        model.startStopwatch()
        now += 18_657L
        assertTrue(model.recordAutomaticPassage())
        model.resetEvaluation(keepStudent = true, timingMode = TimingMode.MANUAL)
        now += 305_898L
        dispatcher.scheduler.advanceTimeBy(500L)
        dispatcher.scheduler.runCurrent()
        assertFalse(model.state.timerRunning)
        assertFalse(model.recordAutomaticPassage())
        assertEquals(0L, model.state.elapsedMs)
        assertNull(model.state.chronoStartedAt)
        assertNull(model.state.timerStartedElapsedMs)
        assertNull(model.state.startedAt)
        assertEquals(RythmoScreen.IDENTIFICATION, model.state.screen)
        assertEquals("Lucas", model.state.firstName)
        assertEquals(model.state, model(handle).state)
        model.startRace()
        model.setMinutes("1"); model.setSeconds("23"); assertTrue(model.addPassage())
        model.setMinutes("1"); model.setSeconds("25"); assertTrue(model.addPassage())
        assertEquals(2_000L, model.state.laps[1].differenceMs)
    }

    @Test fun `finished automatic times retain millisecond precision and reject further passages`() {
        val model = ready()
        model.startStopwatch()
        repeat(5) { now += 89_123L; assertTrue(model.recordAutomaticPassage()) }
        assertFalse(model.recordAutomaticPassage())
        assertEquals(445_615L, model.state.result?.totalMs)
        assertTrue(model.state.corrections.isEmpty())
        assertFalse(model.state.timerRunning)
    }
}
