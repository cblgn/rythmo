package fr.rythmo.session

import fr.rythmo.domain.RaceCalculator
import org.junit.Assert.*
import org.junit.Test

class SessionTest {
    @Test fun `one thousand metres in six equal laps compares every lap without metre rounding errors`() {
        val session = demoSession().copy(distanceMeters = 1000, lapCount = 6)
        session.validate()
        val cumulatives = listOf(50_000L, 98_000L, 144_000L, 188_000L, 230_000L, 270_000L)
        val result = session.result(cumulatives)
        assertEquals(6, result.laps.size)
        assertEquals(listOf(null, -2000L, -2000L, -2000L, -2000L, -2000L), result.laps.map { it.differenceMs })
        assertEquals(45_000L, result.averageLapMs)
        assertEquals(-10_000L, result.progressionMs)
        assertEquals(10_000L, result.cumulativeIrregularityMs)
        val runner = RunnerRecord(pupil = session.pupils[0], rawCumulativeMs = cumulatives)
        assertTrue(runner.finished(session))
        assertFalse(runner.copy(rawCumulativeMs = cumulatives.take(5)).finished(session))
        assertEquals("Tour 6/6", session.passageLabel(6))
        val corrected = runner.correct(session, 6, 41_000)
        assertEquals(-1000L, corrected.laps(session).last().differenceMs)
    }

    @Test fun `a configurable passage distance and grading scale remain part of the saved session`() {
        val session = demoSession().copy(distanceMeters = 1000, passageEveryMeters = 200,
            rubric = DemoRubric(maxGradeTenths = 120))
        session.validate()
        assertEquals(listOf(200, 400, 600, 800, 1000), session.distances)
        assertEquals(120, session.rubric.gradeTenths(200_000, 1000, "6e", Sex.BOY))
        assertEquals(60, session.rubric.gradeTenths(600_000, 1000, "6e", Sex.BOY))
        assertEquals(40_000L, session.result(listOf(40_000L, 80_000L, 120_000L, 160_000L, 200_000L)).averageLapMs)
        assertEquals(session, sessionJson.decodeFromString<SessionConfig>(sessionJson.encodeToString(SessionConfig.serializer(), session)))
        assertThrows(IllegalArgumentException::class.java) { session.copy(lapCount = 0).validate() }
    }

    @Test fun `eight fictional classes contain thirty distinct pupils with equal numbers of boys and girls`() {
        val classes = demoClasses()
        assertEquals(8, classes.size)
        assertEquals(mapOf("6e" to 2, "5e" to 2, "4e" to 2, "3e" to 2), classes.groupingBy { it.level }.eachCount())
        assertEquals(240, classes.flatMap { it.pupils }.map { it.id }.distinct().size)
        classes.forEach { cls ->
            assertEquals(30, cls.pupils.size)
            assertEquals(15, cls.pupils.count { it.sex == Sex.BOY })
            assertEquals(15, cls.pupils.count { it.sex == Sex.GIRL })
            cls.pupils.forEach(Pupil::validate)
        }
        assertEquals(demoClasses(), classes)
    }

    @Test fun `approved demonstration rubric calculates references and grades without floating point durations`() {
        val rubric = DemoRubric()
        assertEquals(600_000L, rubric.referenceMs(2000, "6e", Sex.BOY))
        assertEquals(660_000L, rubric.referenceMs(2000, "6e", Sex.GIRL))
        assertEquals(300_000L, rubric.referenceMs(1000, "6e", Sex.BOY))
        assertEquals(510_000L, rubric.referenceMs(2000, "3e", Sex.BOY))
        assertEquals(listOf(200, 200, 167, 133, 100), listOf(540_000L, 600_000L, 720_000L, 900_000L, 1_200_000L).map {
            rubric.gradeTenths(it, 2000, "6e", Sex.BOY)
        })
        assertThrows(IllegalArgumentException::class.java) { rubric.gradeTenths(0, 2000, "6e", Sex.BOY) }
    }

    @Test fun `a thousand metres has a final 200m segment excluded from 400m comparisons`() {
        val session = demoSession().copy(distanceMeters = 1000)
        assertEquals(listOf(400, 800, 1000), session.distances)
        val result = RaceCalculator.calculate(listOf(90_000L, 182_000L, 220_000L), session.distances)
        assertEquals(listOf(400, 400, 200), result.laps.map { it.segmentMeters })
        assertEquals(listOf(null, 2_000L, null), result.laps.map { it.differenceMs })
        assertEquals(91_000L, result.averageLapMs)
        assertEquals(2_000L, result.progressionMs)
        assertEquals(2_000L, result.cumulativeIrregularityMs)
        assertEquals(220_000L, result.totalMs)
    }

    @Test fun `interleaved runners use their own previous passages and finish independently`() {
        val session = demoSession()
        val a = RunnerRecord(pupil = session.pupils[0])
        val b = RunnerRecord(pupil = session.pupils[1])
        var group = RaceGroup(session = session, runners = listOf(a, b), claimed = true, startElapsedMs = 1, startedAt = "2026-09-18T10:00:00")
        group = group.record(a.id, 89_000).record(b.id, 95_000)
        group = group.record(a.id, 178_000).record(b.id, 185_000)
        group = group.record(a.id, 265_000).record(a.id, 354_000).record(a.id, 441_000)
        val first = group.runners[0]; val second = group.runners[1]
        assertTrue(first.finished(session)); assertFalse(second.finished(session)); assertFalse(group.complete)
        assertEquals(listOf(null, 0L, -2_000L, 2_000L, -2_000L), first.laps(session).map { it.differenceMs })
        assertEquals(listOf(95_000L, 90_000L), second.laps(session).map { it.durationMs })
        assertEquals(-5_000L, second.laps(session).last().differenceMs)
        assertEquals(441_000L, group.report(first).result.totalMs)
        assertNull(second.grade(session))
        assertThrows(IllegalArgumentException::class.java) { group.record(first.id, 500_000) }
    }

    @Test fun `one correction preserves raw data invalidates pdf version and recalculates adjacent differences`() {
        val session = demoSession()
        val raw = listOf(89_000L, 178_000L, 265_000L, 354_000L, 441_000L)
        val original = RunnerRecord(pupil = session.pupils[0], rawCumulativeMs = raw, pdfRevision = 1, syncedRevision = 1)
        val corrected = original.correct(session, 2, 85_000)
        corrected.validate(session)
        assertEquals(raw, corrected.rawCumulativeMs)
        assertEquals(2, corrected.revision)
        assertNotEquals(corrected.revision, corrected.pdfRevision)
        assertNotEquals(corrected.revision, corrected.syncedRevision)
        assertEquals(listOf(null, -4_000L, 2_000L, 2_000L, -2_000L), corrected.laps(session).map { it.differenceMs })
        assertEquals(437_000L, corrected.cumulativeMs(session).last())
        assertEquals(89_000L, corrected.corrections.single().originalMs)
        assertThrows(IllegalArgumentException::class.java) { corrected.correct(session, 2, 86_000) }
        assertThrows(IllegalArgumentException::class.java) { original.correct(session, 1, 0) }
        assertThrows(IllegalArgumentException::class.java) { original.correct(session, 1, 89_000) }
    }

    @Test fun `session validates roster size and positive coefficients`() {
        val session = demoSession()
        assertThrows(IllegalArgumentException::class.java) { session.copy(pupils = session.pupils + session.pupils.first().copy(id = newId())).validate() }
        assertThrows(IllegalArgumentException::class.java) { session.copy(distanceMeters = 0).validate() }
        assertThrows(IllegalArgumentException::class.java) { session.copy(rubric = session.rubric.copy(girlPermille = 0)).validate() }
    }
}
