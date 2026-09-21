package fr.rythmo.session

import fr.rythmo.sync.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TeacherPreparationTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun `roster accepts profiles but rejects incomplete pupils`() {
        val sheet = ImportSheet("Class", listOf(listOf("NOM", "Prénom", "Sexe"), listOf("Exemple", "Alice", "F"), listOf("Test", "Basile", "M")))
        val pupils = ImportValues.pupils(sheet, 1, 0, 1, 2)
        assertEquals(listOf(Sex.GIRL, Sex.BOY), pupils.map { it.sex })
        assertThrows(IllegalArgumentException::class.java) { ImportValues.pupils(sheet.copy(rows = listOf(listOf("", "Alice", "F"))), 0, 0, 1, 2) }
    }
    @Test fun `import correction is explicit and source cells remain untouched`() {
        val sheet = ImportSheet("Fictional", listOf(listOf("bad", "7"), listOf("0.003", "6")))
        assertThrows(IllegalArgumentException::class.java) { ImportValues.performance(sheet, 0, 0, 1, ImportTimeUnit.EXCEL) }
        val values = ImportValues.performance(sheet, 0, 0, 1, ImportTimeUnit.EXCEL, mapOf(0 to "3:55"))
        assertEquals(235_000L, values.first().timeMs)
        assertEquals(259_200L, values.last().timeMs)
        assertEquals("bad", sheet.rows.first().first())
        assertEquals(83_500L, ImportValues.time("1:23,5", ImportTimeUnit.CLOCK))
    }
    @Test fun `preparation persists and published protocol five retains old sessions`() {
        val table = PerformanceTable(name = "Fictional", distanceMeters = 1000, sourceMaxTenths = 70,
            tables = Sex.entries.associateWith { listOf(PerformanceThreshold(1, 70), PerformanceThreshold(300_000, 50)) })
        val store = TeacherStore(temp.newFolder())
        val old = store.state.sessions
        val cls = store.state.classes.first()
        val draft = TeacherDraft(classId = cls.id, tableId = table.id)
        val prep = TeacherPreparation(draft, listOf(table))
        val file = JsonFile(temp.newFile(), TeacherPreparation.serializer()) { TeacherPreparation() }
        file.write(prep); assertEquals(prep, file.read())
        store.publish(draft.session(listOf(cls), listOf(table)))
        assertEquals(5, store.download("client", "Tablet").protocol)
        assertTrue(store.state.sessions.containsAll(old))
        assertTrue(supportedProtocol(3)); assertTrue(supportedProtocol(4)); assertTrue(supportedProtocol(5)); assertFalse(supportedProtocol(6))
    }
}
