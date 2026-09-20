package fr.rythmo.session

import fr.rythmo.sync.TeacherStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TabularImportTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun `quoted csv preserves names and supports comma and semicolon separators`() {
        assertEquals(listOf(listOf("Prénom", "Nom", "Profil"), listOf("Alice", "Exemple; Test", "F")),
            TabularImport.csv("Prénom;Nom;Profil\r\nAlice;\"Exemple; Test\";F\r\n"))
        assertEquals(listOf(listOf("A", "B"), listOf("Alice", "Exemple")), TabularImport.csv("A,B\nAlice,Exemple"))
        assertThrows(IllegalArgumentException::class.java) { TabularImport.csv("A;\"B") }
    }
    @Test fun `reimport preserves stable ids and published sessions`() {
        val store = TeacherStore(temp.newFolder())
        val published = store.state.sessions
        val cls = SchoolClass("new", "3e Exemple", "3e", listOf(Pupil(firstName = "Alice", lastName = "Exemple", sex = Sex.GIRL)))
        val first = store.importClass(cls)
        val again = store.importClass(cls.copy(pupils = cls.pupils + Pupil(firstName = "Basile", lastName = "Test", sex = Sex.BOY)))
        assertEquals(first.id, again.id)
        assertEquals(first.pupils.first().id, again.pupils.first().id)
        assertEquals(published, store.state.sessions)
        assertThrows(IllegalArgumentException::class.java) { store.importClass(cls.copy(pupils = cls.pupils + cls.pupils)) }
        assertEquals(again, store.state.classes.last())
    }
    private fun workbook(sheet: String): ImportWorkbook {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val entries = mapOf(
                "xl/workbook.xml" to """<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Fictional" r:id="r1"/></sheets></workbook>""",
                "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="r1" Target="worksheets/sheet1.xml"/></Relationships>""",
                "xl/worksheets/sheet1.xml" to sheet)
            entries.forEach { (name, content) -> zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry() }
        }
        return TabularImport.read(ImportFile("fixture.xlsx", Base64.getEncoder().encodeToString(out.toByteArray())))
    }
    @Test fun `xlsx reads numeric and inline values and excludes cached formulas`() {
        val result = workbook("""<worksheet><sheetData><row><c r="A1" t="inlineStr"><is><t>Alice</t></is></c><c r="B1"><v>42</v></c><c r="C1"><f>1+1</f><v>2</v></c></row></sheetData></worksheet>""")
        assertEquals(listOf(listOf("Alice", "42")), result.sheets.single().rows)
        assertEquals(1, result.sheets.single().ignoredFormulas)
    }
    @Test fun `xml entities are rejected without reading external resources`() {
        assertThrows(IllegalArgumentException::class.java) { workbook("""<!DOCTYPE worksheet [<!ENTITY x SYSTEM "file:///private">]><worksheet>&x;</worksheet>""") }
    }
}
