package fr.rythmo

import fr.rythmo.session.*
import org.junit.Assert.*
import org.junit.Test

class RunnerProgressTest {
    @Test fun `saved passages and pace survive refusal restoration correction and finish`() {
        val pupil = Pupil(firstName = "Alice", lastName = "Exemple", sex = Sex.GIRL)
        val session = SessionConfig(schoolClass = "6e Mistral", level = "6e", pupils = listOf(pupil), distanceMeters = 1000, lapCount = 6)
        val runner = RunnerRecord(pupil = pupil)
        var group = RaceGroup(session = session, runners = listOf(runner), claimed = true, startElapsedMs = 1)

        repeat(6) { i ->
            group = group.record(runner.id, (i + 1) * 60_000L)
            val saved = group.runners.single()
            assertEquals(i + 1, saved.rawCumulativeMs.size)
            assertThrows(IllegalArgumentException::class.java) { group.record(runner.id, (i + 1) * 60_000L) }
            val restored = sessionJson.decodeFromString<RaceGroup>(sessionJson.encodeToString(RaceGroup.serializer(), group))
            assertEquals(saved, restored.runners.single())
        }
        assertTrue(group.complete)
        val corrected = group.runners.single().correct(session, 2, 58_000)
        assertEquals(6, corrected.rawCumulativeMs.size)
        assertEquals(listOf(null, -2000L, 2000L, 0L, 0L, 0L), corrected.laps(session).map { it.differenceMs })
    }
    @Test fun `short labels expand collisions and distinguish identical full names`() {
        val runners = listOf("Martin", "Moreau", "Martin", "Durand").map { name ->
            RunnerRecord(pupil = Pupil(firstName = "Alice", lastName = name, sex = Sex.GIRL))
        }
        val labels = runnerLabels(runners).values.toList()
        assertEquals(listOf("Alice Martin · 1", "Alice Moreau", "Alice Martin · 3", "Alice D."), labels)
    }
    @Test fun `stopwatch displays tenths without rounding underlying milliseconds`() {
        assertEquals("0:00,0", stopwatchTenths(0))
        assertEquals("1:23,4", stopwatchTenths(83_499))
        assertEquals("60:00,0", stopwatchTenths(3_600_000))
    }
}
