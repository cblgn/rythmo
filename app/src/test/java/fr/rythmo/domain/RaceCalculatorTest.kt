package fr.rythmo.domain

import org.junit.Assert.*
import org.junit.Test

class RaceCalculatorTest {
    @Test fun `reference race produces the required laps and differences`() {
        val result = RaceCalculator.calculate(listOf(89_000L, 178_000L, 265_000L, 354_000L, 441_000L))
        assertEquals(listOf(89_000L, 89_000L, 87_000L, 89_000L, 87_000L), result.laps.map { it.durationMs })
        assertEquals(listOf(null, 0L, -2_000L, 2_000L, -2_000L), result.laps.map { it.differenceMs })
        assertEquals(listOf(null, PaceChange.EQUIVALENT, PaceChange.FASTER, PaceChange.SLOWER, PaceChange.FASTER), result.laps.map { it.paceChange })
        assertEquals(441_000L, result.totalMs)
        assertEquals(88_200L, result.averageLapMs)
        assertEquals(-2_000L, result.progressionMs)
        assertEquals(6_000L, result.cumulativeIrregularityMs)
    }

    @Test fun `regular race has zero differences progression and irregularity`() {
        val result = RaceCalculator.calculate((1L..5L).map { it * 90_000 })
        assertEquals(List(5) { 90_000L }, result.laps.map { it.durationMs })
        assertEquals(listOf(null, 0L, 0L, 0L, 0L), result.laps.map { it.differenceMs })
        assertEquals(450_000L, result.totalMs)
        assertEquals(90_000L, result.averageLapMs)
        assertEquals(0L, result.progressionMs)
        assertEquals(0L, result.cumulativeIrregularityMs)
    }

    @Test fun `tolerance includes both one second boundaries`() {
        listOf(-1_000L, -999L, 0L, 999L, 1_000L).forEach {
            assertEquals(PaceChange.EQUIVALENT, PaceChange.fromDifference(it))
        }
        assertEquals(PaceChange.FASTER, PaceChange.fromDifference(-1_001))
        assertEquals(PaceChange.SLOWER, PaceChange.fromDifference(1_001))
    }

    @Test fun `millisecond precision is retained`() {
        val result = RaceCalculator.calculate(listOf(10_001L, 20_002L, 29_002L, 40_004L, 50_005L))
        assertEquals(10_001L, result.averageLapMs)
        assertEquals(-1_001L, result.laps[2].differenceMs)
        assertEquals(4_004L, result.cumulativeIrregularityMs)
    }

    @Test fun `invalid cumulative sequences are rejected`() {
        listOf(emptyList(), listOf(1L), listOf(0L, 1L, 2L, 3L, 4L),
            listOf(-1L, 1L, 2L, 3L, 4L), listOf(1L, 2L, 2L, 3L, 4L),
            listOf(1L, 3L, 2L, 4L, 5L), (1L..6L).toList()).forEach { times ->
            assertThrows(IllegalArgumentException::class.java) { RaceCalculator.calculate(times) }
        }
    }

    @Test fun `partial race displays only available laps`() {
        assertEquals(emptyList<LapResult>(), RaceCalculator.calculateLaps(emptyList()))
        assertEquals(listOf(89_000L, 89_000L), RaceCalculator.calculateLaps(listOf(89_000L, 178_000L)).map { it.durationMs })
    }
}
