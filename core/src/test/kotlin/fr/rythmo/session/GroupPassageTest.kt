package fr.rythmo.session

import org.junit.Assert.*
import org.junit.Test

class GroupPassageTest {
    private fun group(): RaceGroup {
        val session = demoSession().copy(distanceMeters = 1000, lapCount = 6)
        return RaceGroup(session = session, runners = session.pupils.take(8).map { RunnerRecord(pupil = it) }, claimed = true, startElapsedMs = 0)
    }
    @Test fun `eight simultaneous passages share exact milliseconds and invalid selection changes nothing`() {
        val original = group()
        val ids = original.runners.map { it.id }.toSet()
        val recorded = original.recordBatch(ids, 81_123)
        assertTrue(recorded.runners.all { it.rawCumulativeMs == listOf(81_123L) })
        assertThrows(NoSuchElementException::class.java) { original.recordBatch(ids.toList().dropLast(1).toSet() + "unknown", 81_123) }
        assertTrue(original.runners.all { it.rawCumulativeMs.isEmpty() })
        val restored = sessionJson.decodeFromString<RaceGroup>(sessionJson.encodeToString(RaceGroup.serializer(), recorded))
        assertEquals(recorded, restored)
    }
    @Test fun `undoing arrival preserves the original in audit and invalidates PDF until another arrival`() {
        var group = group()
        val id = group.runners.first().id
        repeat(6) { group = group.record(id, (it + 1) * 60_000L) }
        val original = group.runners.first().copy(pdfRevision = 1)
        val undone = original.cancelLastPassage()
        assertEquals(5, undone.rawCumulativeMs.size)
        assertFalse(undone.finished(group.session))
        assertEquals(360_000L, undone.cancelledPassages.single().cumulativeMs)
        assertEquals(2, undone.revision)
        assertNotEquals(undone.pdfRevision, undone.revision)
        val next = group.copy(runners = group.runners.map { if (it.id == id) undone else it }).record(id, 380_000)
        val report = next.report(next.runners.first())
        assertEquals(380_000L, report.result.totalMs)
        assertEquals(1, report.cancelledPassages.size)
        assertThrows(IllegalArgumentException::class.java) { original.copy(syncedRevision = 1).cancelLastPassage() }
        assertThrows(IllegalArgumentException::class.java) { original.correct(group.session, 1, 61_000).cancelLastPassage() }
    }
    @Test fun `old archives without cancellation history retain their correction revision`() {
        val session = demoSession()
        val runner = RunnerRecord(pupil = session.pupils.first(), rawCumulativeMs = listOf(60_000,120_000,180_000,240_000,300_000)).correct(session, 1, 61_000)
        val encoded = sessionJson.encodeToString(RunnerRecord.serializer(), runner)
        assertEquals(2, sessionJson.decodeFromString<RunnerRecord>(encoded).revision)
    }
}
