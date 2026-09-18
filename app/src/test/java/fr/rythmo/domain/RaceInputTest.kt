package fr.rythmo.domain

import org.junit.Assert.*
import org.junit.Test

class RaceInputTest {
    @Test fun `separate minute and second fields validate an independent lap duration`() {
        assertEquals(SplitValidation.Accepted(300_000), RaceInput.validateLap("5", "00"))
        assertEquals(SplitValidation.Accepted(601_000), RaceInput.validateLap("10", "01"))
        assertEquals(SplitValidation.Accepted(1_000), RaceInput.validateLap("0", "1"))
    }

    @Test fun `zero invalid seconds negative values and overflow are rejected`() {
        listOf("0" to "0", "1" to "60", "-1" to "20", "1" to "-1", "" to "1",
            "1" to "", "x" to "1", Long.MAX_VALUE.toString() to "59").forEach { (min, sec) ->
            assertTrue(RaceInput.validateLap(min, sec) is SplitValidation.Invalid)
        }
    }

    @Test fun `legacy validation reproduced a false warning during incomplete fourth passage typing`() {
        val partial = RaceInput.analyze(listOf("129", "258", "425", "5", ""))
        assertNotNull(partial.errors[3])
        val complete = RaceInput.analyze(listOf("129", "258", "425", "554", "721"))
        assertTrue(complete.errors.all { it == null })
        assertNotNull(complete.result)
    }

    @Test fun `digits and colon times parse identically`() {
        assertEquals(89_000L, TimeFormat.parseInput("129"))
        assertEquals(89_000L, TimeFormat.parseInput("1:29"))
        assertEquals(5_000L, TimeFormat.parseInput("5"))
        assertEquals(0L, TimeFormat.parseInput("000"))
        assertEquals(600_000L, TimeFormat.parseInput("10:00"))
    }

    @Test fun `invalid formats are rejected`() {
        listOf("", " ", "-129", "1:60", "160", "1:2", "1:29.5", "abc", "1000000").forEach {
            assertNull(it, TimeFormat.parseInput(it))
        }
    }

    @Test fun `durations and differences keep meaningful milliseconds`() {
        assertEquals("1:28,2", TimeFormat.duration(88_200L))
        assertEquals("1:29", TimeFormat.duration(89_000L))
        assertEquals("0:00,001", TimeFormat.duration(1))
        assertEquals("−2 s", TimeFormat.difference(-2_000))
        assertEquals("+1,001 s", TimeFormat.difference(1_001))
        assertEquals("0 s", TimeFormat.difference(0))
        assertEquals("129", TimeFormat.inputDigits(89_999))
    }

    @Test fun `reference input yields a complete report`() {
        val analysis = RaceInput.analyze(listOf("129", "258", "425", "554", "721"))
        assertTrue(analysis.errors.all { it == null })
        assertEquals(441_000L, analysis.result?.totalMs)
    }

    @Test fun `missing or invalid previous passages prevent misleading results`() {
        listOf("", "160", "000", "129").forEach { second ->
            val analysis = RaceInput.analyze(listOf("129", second, "425", "554", "721"))
            assertNull(analysis.result)
            assertEquals(1, analysis.laps.size)
            assertNotNull(analysis.errors[2])
        }
    }

    @Test fun `editing an early split invalidates later non increasing times`() {
        val analysis = RaceInput.analyze(listOf("300", "258", "425", "554", "721"))
        assertNotNull(analysis.errors[1])
        assertNull(analysis.result)
        assertEquals(1, analysis.laps.size)
    }
}
