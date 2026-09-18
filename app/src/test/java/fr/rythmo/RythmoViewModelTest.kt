package fr.rythmo

import androidx.lifecycle.SavedStateHandle
import fr.rythmo.domain.PaceChange
import org.junit.Assert.*
import org.junit.Test

class RythmoViewModelTest {
    private fun startedModel(handle: SavedStateHandle = SavedStateHandle()) = RythmoViewModel(handle).apply {
        setLastName("Dupont")
        setFirstName("Lucas")
        setSchoolClass("3e Ouessant")
        startRace()
    }

    @Test fun `all five passages can be entered sequentially without a false fourth passage error`() {
        val model = startedModel()
        listOf("1" to "29", "1" to "29", "1" to "27", "1" to "29", "1" to "27")
            .forEachIndexed { index, (min, sec) ->
                assertEquals((index + 1) * 400, model.state.nextDistance)
                model.setMinutes(min)
                assertNull(model.state.inputError) // An unfinished minute is never validated as seconds.
                model.setSeconds(sec)
                assertTrue(model.addPassage())
                assertNull(model.state.inputError)
                assertEquals("", model.state.minutes)
                assertEquals("", model.state.seconds)
                assertEquals(index + 1, model.state.laps.size)
            }
        val result = requireNotNull(model.state.result)
        assertEquals(listOf(89_000L, 178_000L, 265_000L, 354_000L, 441_000L), model.state.cumulativeTimesMs)
        assertEquals(listOf(89_000L, 89_000L, 87_000L, 89_000L, 87_000L), result.laps.map { it.durationMs })
        assertEquals(listOf(null, 0L, -2_000L, 2_000L, -2_000L), result.laps.map { it.differenceMs })
        assertEquals(listOf(null, PaceChange.EQUIVALENT, PaceChange.FASTER, PaceChange.SLOWER, PaceChange.FASTER), result.laps.map { it.paceChange })
        assertEquals(441_000L, result.totalMs)
        assertEquals(88_200L, result.averageLapMs)
        assertEquals(-2_000L, result.progressionMs)
        assertEquals(6_000L, result.cumulativeIrregularityMs)
        assertNull(model.state.nextDistance)
        assertFalse(model.addPassage())
        assertEquals(5, model.state.laps.size)
        assertNotNull(model.state.report)
    }

    @Test fun `4 minutes 59 followed by 5 minutes 00 records two independent laps`() {
        val model = startedModel()
        model.setMinutes("4"); model.setSeconds("59")
        assertTrue(model.addPassage())
        model.setMinutes("5"); model.setSeconds("00")
        assertTrue(model.addPassage())
        assertEquals(listOf(299_000L, 599_000L), model.state.cumulativeTimesMs)
        assertEquals(300_000L, model.state.laps.last().durationMs)
        assertEquals(1_000L, model.state.laps.last().differenceMs)
    }

    @Test fun `1 minute 24 after 1 minute 26 is valid faster and equal or slower laps are also accepted`() {
        val model = startedModel()
        listOf("26", "24", "24", "27", "23").forEach {
            model.setMinutes("1"); model.setSeconds(it)
            assertTrue(model.addPassage())
        }
        assertEquals(listOf(86_000L, 84_000L, 84_000L, 87_000L, 83_000L), model.state.laps.map { it.durationMs })
        assertEquals(listOf(null, -2_000L, 0L, 3_000L, -4_000L), model.state.laps.map { it.differenceMs })
        assertEquals(424_000L, model.state.result?.totalMs)
    }

    @Test fun `800 m shows plus two seconds after laps 1 minute 23 and 1 minute 25`() {
        val model = startedModel()
        model.setMinutes("1"); model.setSeconds("23"); assertTrue(model.addPassage())
        model.setMinutes("1"); model.setSeconds("25"); assertTrue(model.addPassage())
        val second = model.state.laps[1]
        assertEquals(800, second.distanceMeters)
        assertEquals(85_000L, second.durationMs)
        assertEquals(168_000L, second.cumulativeMs)
        assertEquals(2_000L, second.differenceMs)
        assertEquals(PaceChange.SLOWER, second.paceChange)
    }

    @Test fun `start time is recorded only after valid start and survives editing and restoration`() {
        val handle = SavedStateHandle()
        val empty = RythmoViewModel(handle)
        empty.startRace()
        assertNull(empty.state.startedAt)
        val before = java.time.LocalDateTime.now()
        val model = startedModel(handle)
        val start = requireNotNull(model.state.startedAt)
        assertFalse(start.isBefore(before))
        assertFalse(start.isAfter(java.time.LocalDateTime.now()))
        assertEquals(start.toLocalDate(), model.state.date)
        model.editStudent(); model.startRace()
        assertEquals(start, model.state.startedAt)
        assertEquals(start, RythmoViewModel(handle).state.startedAt)
    }

    @Test fun `invalid passage leaves recorded times and entered fields intact`() {
        val model = startedModel()
        model.setMinutes("1"); model.setSeconds("29"); model.addPassage()
        listOf("0" to "00", "2" to "60", "-1" to "2", "" to "3").forEach { (min, sec) ->
            model.setMinutes(min); model.setSeconds(sec)
            assertFalse(model.addPassage())
            assertEquals(listOf(89_000L), model.state.cumulativeTimesMs)
            assertEquals(min, model.state.minutes)
            assertEquals(sec, model.state.seconds)
            assertNotNull(model.state.inputError)
        }
        model.setMinutes("10"); model.setSeconds("01")
        assertTrue(model.addPassage())
        assertEquals(690_000L, model.state.cumulativeTimesMs.last())
    }

    @Test fun `identity is mandatory and limited to the four classes`() {
        val model = RythmoViewModel(SavedStateHandle())
        model.startRace()
        assertEquals(RythmoScreen.IDENTIFICATION, model.state.screen)
        model.setLastName("  "); model.setFirstName("Lucas"); model.setSchoolClass("3e Ouessant")
        assertFalse(model.state.canStart)
        model.setLastName("Dupont"); model.setSchoolClass("Autre")
        assertFalse(model.state.canStart)
        model.setSchoolClass("3e Hoedic")
        assertTrue(model.state.canStart)
    }

    @Test fun `saved state retains student race date accepted times and pending input`() {
        val handle = SavedStateHandle()
        val model = startedModel(handle)
        model.setMinutes("1"); model.setSeconds("29"); model.addPassage()
        model.setMinutes("2"); model.setSeconds("58")
        val restored = RythmoViewModel(handle)
        assertEquals(model.state, restored.state)
        restored.editStudent()
        restored.startRace()
        assertEquals(listOf(89_000L), restored.state.cumulativeTimesMs)
        assertEquals(model.state.date, restored.state.date)
    }

    private fun completedModel(handle: SavedStateHandle = SavedStateHandle()) = startedModel(handle).apply {
        listOf("29", "29", "27", "29", "27").forEach {
            setMinutes("1"); setSeconds(it); assertTrue(submitTime())
        }
    }

    @Test fun `a final correction preserves the original and recalculates adjacent differences and total`() {
        val model = completedModel()
        model.editLap(2)
        assertEquals(2, model.state.editingLapNumber)
        assertNull(model.state.report)
        model.setSeconds("25")
        assertTrue(model.submitTime())
        assertEquals(listOf(89_000L, 85_000L, 87_000L, 89_000L, 87_000L), model.state.laps.map { it.durationMs })
        assertEquals(listOf(null, -4_000L, 2_000L, 2_000L, -2_000L), model.state.laps.map { it.differenceMs })
        assertEquals(437_000L, model.state.result?.totalMs)
        assertEquals(87_400L, model.state.result?.averageLapMs)
        assertEquals(10_000L, model.state.result?.cumulativeIrregularityMs)
        val correction = model.state.report!!.corrections.single()
        assertEquals(89_000L, correction.originalMs)
        assertEquals(85_000L, correction.correctedMs)
        assertEquals(2, correction.lapNumber)
    }

    @Test fun `a corrected passage cannot be corrected twice even after state restoration`() {
        val handle = SavedStateHandle()
        val model = completedModel(handle)
        model.editLap(2); model.setSeconds("25"); assertTrue(model.confirmCorrection())
        val restored = RythmoViewModel(handle)
        assertEquals(model.state, restored.state)
        restored.editLap(2)
        assertNull(restored.state.editingLapNumber)
        assertFalse(restored.confirmCorrection())
        assertEquals(1, restored.state.corrections.size)
        restored.editLap(3); restored.setSeconds("26"); assertTrue(restored.confirmCorrection())
        assertEquals(2, restored.state.corrections.size)
        assertEquals(85_000L, restored.state.laps[1].durationMs)
    }

    @Test fun `invalid unchanged or cancelled edits do not consume the single correction`() {
        val model = completedModel()
        model.editLap(1)
        assertFalse(model.confirmCorrection())
        model.setSeconds("60")
        assertFalse(model.confirmCorrection())
        assertTrue(model.state.corrections.isEmpty())
        assertEquals(441_000L, model.state.result?.totalMs)
        model.cancelCorrection()
        assertNull(model.state.editingLapNumber)
        model.editLap(1); model.setSeconds("28"); assertTrue(model.confirmCorrection())
        assertEquals(1, model.state.corrections.size)
    }

    @Test fun `corrections are only allowed after the fifth passage`() {
        val model = startedModel()
        model.setMinutes("1"); model.setSeconds("29"); model.submitTime()
        model.editLap(1)
        assertNull(model.state.editingLapNumber)
        assertTrue(model.state.corrections.isEmpty())
    }

    @Test fun `manual differences always refer to the immediately preceding lap`() {
        val model = startedModel()
        listOf("23", "25", "21", "21", "30").forEach {
            model.setMinutes("1"); model.setSeconds(it); assertTrue(model.submitTime())
        }
        assertEquals(listOf(null, 2_000L, -4_000L, 0L, 9_000L), model.state.laps.map { it.differenceMs })
        assertEquals(420_000L, model.state.result?.totalMs)
    }

    @Test fun `mode can change after commencing if no times have been recorded`() {
        val model = startedModel()
        model.editStudent()
        model.setTimingMode(fr.rythmo.domain.TimingMode.AUTOMATIC)
        assertEquals(fr.rythmo.domain.TimingMode.AUTOMATIC, model.state.timingMode)
        model.setTimingMode(fr.rythmo.domain.TimingMode.MANUAL)
        assertEquals(fr.rythmo.domain.TimingMode.MANUAL, model.state.timingMode)
    }

    @Test fun `reset clears identity times correction allowance and saved state for the next pupil`() {
        val handle = SavedStateHandle()
        val model = completedModel(handle)
        model.editLap(2); model.setSeconds("25"); assertTrue(model.confirmCorrection())
        model.editLap(3); model.setSeconds("60"); assertFalse(model.confirmCorrection())
        model.resetEvaluation()
        assertEquals(RythmoState(), model.state)
        val restored = RythmoViewModel(handle)
        assertEquals(model.state, restored.state)
        assertNull(restored.state.report)
        assertFalse(restored.state.canStart)
        val next = completedModel(handle)
        assertTrue(next.state.corrections.isEmpty())
        next.editLap(2); next.setSeconds("24"); assertTrue(next.confirmCorrection())
        assertEquals(89_000L, next.state.corrections.single().originalMs)
    }
}
