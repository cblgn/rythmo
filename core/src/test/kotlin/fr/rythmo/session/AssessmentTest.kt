package fr.rythmo.session

import org.junit.Assert.*
import org.junit.Test

class AssessmentTest {
    private fun rubric(threshold: Long = 0) = AssessmentRubric(name = "Fictional", performanceMaxTenths = 70,
        comparisonMaxTenths = 50, comparisonThresholdMs = threshold, tables = Sex.entries.associateWith {
            listOf(PerformanceThreshold(300_000, 70), PerformanceThreshold(400_000, 50), PerformanceThreshold(500_000, 0))
        })
    private fun score(durations: List<Long>, threshold: Long = 0): AssessmentScore {
        val session = demoSession().copy(distanceMeters = 1000, lapCount = durations.size)
        val cumulative = durations.runningFold(0L, Long::plus).drop(1)
        return rubric(threshold).score(session.result(cumulative).laps, Sex.BOY, true, 400)
    }
    @Test fun `six equal laps have five successful comparisons and exact conversion to twenty`() {
        val score = score(List(6) { 70_000L })
        assertEquals(5, score.comparisons)
        assertEquals(5, score.successfulComparisons)
        assertEquals(50, score.performanceTenths)
        assertEquals(50, score.comparisonTenths)
        assertEquals(100, score.totalTenths)
        assertEquals(167, score.outOf20Tenths)
    }
    @Test fun `threshold includes equality and distinguishes faster and slower intervals`() {
        assertEquals(3, score(listOf(70_000L, 70_000, 69_000, 71_000, 69_000, 70_000)).successfulComparisons)
        assertEquals(1, score(listOf(70_000L, 70_000, 69_000, 71_000, 69_000, 70_000), -2000).successfulComparisons)
        assertEquals(5, score(listOf(70_000L, 70_000, 69_000, 71_000, 69_000, 70_000), 2000).successfulComparisons)
    }
    @Test fun `performance uses lower bounds without interpolation and clamps outside the table`() {
        val session = demoSession().copy(distanceMeters = 1000, lapCount = 2)
        for ((time, expected) in listOf(299_999L to 70, 300_000L to 70, 399_999L to 70, 400_000L to 50, 900_000L to 0)) {
            assertEquals(expected, rubric().score(session.result(listOf(time / 2, time)).laps, Sex.GIRL, true, 400).performanceTenths)
        }
    }
    @Test fun `final short segment is excluded from comparisons`() {
        val session = demoSession().copy(distanceMeters = 1000)
        val score = rubric().score(session.result(listOf(90_000, 180_000, 210_000)).laps, Sex.BOY, false, 400)
        assertEquals(1, score.comparisons)
        assertEquals(1, score.successfulComparisons)
    }
    @Test fun `invalid tables and versions are rejected`() {
        val r = rubric()
        val invalid = listOf(r.copy(schemaVersion = 99), r.copy(comparisonMaxTenths = -1), r.copy(tables = emptyMap()),
            r.copy(tables = r.tables.mapValues { listOf(PerformanceThreshold(400_000, 0), PerformanceThreshold(300_000, 50)) }))
        invalid.forEach { assertThrows(IllegalArgumentException::class.java, it::validate) }
    }
    @Test fun `new assessment snapshots require updated clients and retain the frozen rubric`() {
        val session = demoSession().copy(distanceMeters = 1000, lapCount = 6, assessment = rubric())
        val runner = RunnerRecord(pupil = session.pupils.first(), rawCumulativeMs = List(6) { (it + 1) * 70_000L })
        val group = RaceGroup(session = session, runners = listOf(runner))
        assertEquals(167, group.report(runner).assessmentScore?.outOf20Tenths)
        val changed = session.copy(assessment = rubric().copy(comparisonThresholdMs = -2000))
        assertEquals(100, runner.grade(session))
        assertEquals(50, runner.grade(changed))
        val serialized = sessionJson.encodeToString(SessionConfig.serializer(), session)
        assertEquals(session, sessionJson.decodeFromString<SessionConfig>(serialized))
        assertNull(runner.copy(rawCumulativeMs = listOf(70_000L), abandoned = true).grade(session))
        assertThrows(IllegalArgumentException::class.java) { session.copy(lapCount = 1).validate() }
        assertThrows(IllegalArgumentException::class.java) { PreparationPackage(schemaVersion = 99, session = session).validate() }
    }
}
