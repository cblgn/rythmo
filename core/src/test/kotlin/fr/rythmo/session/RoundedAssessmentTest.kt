package fr.rythmo.session

import org.junit.Assert.*
import org.junit.Test

class RoundedAssessmentTest {
    private fun session(count: Int = 6, total: Int = 130) = demoSession().copy(distanceMeters = 1000, lapCount = count,
        assessment = AssessmentRubric(schemaVersion = 2, name = "Fictional", performanceMaxTenths = total - (count - 1) * 10,
            comparisonMaxTenths = (count - 1) * 10, sourceMaxTenths = 70,
            tables = Sex.entries.associateWith { listOf(PerformanceThreshold(1, 60)) }))
    private fun score(times: List<Long>, total: Int = 130): AssessmentScore {
        val s = session(times.size, total)
        s.validate()
        return requireNotNull(RunnerRecord(pupil = s.pupils.first(), rawCumulativeMs = times.runningFold(0L, Long::plus).drop(1)).assessmentScore(s))
    }
    @Test fun `round individual laps before comparing with half seconds up`() {
        val result = score(listOf(83_499, 83_500, 84_499, 83_501, 82_499, 82_498))
        assertEquals(4, result.successfulComparisons)
        assertEquals(83L, roundedSeconds(83_499)); assertEquals(84L, roundedSeconds(83_500))
    }
    @Test fun `weight source score and convert exact total without double rounding`() {
        val result = score(List(6) { 70_000L })
        assertEquals(60, result.sourcePerformanceTenths)
        assertEquals(69, result.performanceTenths)
        assertEquals(119, result.totalTenths)
        assertEquals(182, result.outOf20Tenths)
        assertEquals(110, score(List(6) { 70_000L }, 120).totalTenths)
    }
    @Test fun `one and twenty laps retain one point per eligible comparison`() {
        assertEquals(0, score(listOf(70_000L)).comparisons)
        assertEquals(19, score(List(20) { 70_000L }, 300).successfulComparisons)
    }
    @Test fun `native draft binds distance and freezes a new rubric`() {
        val cls = demoClasses().first()
        val table = PerformanceTable(name = "Fictional", distanceMeters = 1000, sourceMaxTenths = 70, tables = session().assessment!!.tables)
        val draft = TeacherDraft(classId = cls.id, tableId = table.id)
        val first = draft.session(listOf(cls), listOf(table))
        assertNotEquals(first.id, draft.session(listOf(cls), listOf(table)).id)
        assertThrows(IllegalArgumentException::class.java) { draft.copy(distance = "2000").session(listOf(cls), listOf(table)) }
        assertThrows(IllegalArgumentException::class.java) { draft.copy(total = "5").session(listOf(cls), listOf(table)) }
    }
}
