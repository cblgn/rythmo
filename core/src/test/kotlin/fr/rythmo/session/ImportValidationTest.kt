package fr.rythmo.session

import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImportValidationTest {
 private fun encoded(name:String, bytes:ByteArray)=ImportFile(name,Base64.getEncoder().encodeToString(bytes))
 private fun zip(entries:Map<String,String>):ImportFile {
  val out=ByteArrayOutputStream();ZipOutputStream(out).use { z->entries.forEach { (name,value)->z.putNextEntry(ZipEntry(name));z.write(value.toByteArray());z.closeEntry() } }
  return encoded("fiction.xlsx",out.toByteArray())
 }
 private fun workbook(sheet:String,target:String="worksheets/one.xml",strings:String?=null,external:Boolean=false):ImportFile {
  val entries=mutableMapOf("xl/workbook.xml" to """<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Fictional" r:id="r1"/></sheets></workbook>""",
   "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="r1" Target="$target" ${if(external) "TargetMode=\"External\"" else ""}/></Relationships>""",
   "xl/worksheets/one.xml" to sheet)
  if(strings!=null)entries["xl/sharedStrings.xml"]=strings
  return zip(entries)
 }
 @Test fun `CSV dialect preserves BOM escaped quotes multiline cells and rejects excessive dimensions`() {
  val file=encoded("fiction.CSV","\uFEFFNOM;Prénom;Sexe\r\n\"Exemple \"\"A\"\"\";\"Alice\nAnne\";F\r".toByteArray())
  assertEquals("Exemple \"A\"",TabularImport.read(file).sheets.single().rows[1][0])
  assertEquals("Alice\nAnne",TabularImport.read(file).sheets.single().rows[1][1])
  for(text in listOf("", "\n\r\n", "x".repeat(1001), List(51){"x"}.joinToString(";"),List(1001){"x"}.joinToString("\n")))
   assertThrows(IllegalArgumentException::class.java) { TabularImport.csv(text) }
  for(size in listOf(0,2_000_001)) assertThrows(IllegalArgumentException::class.java) { TabularImport.read(encoded("fiction.csv",ByteArray(size))) }
  assertThrows(IllegalStateException::class.java) { TabularImport.read(encoded("fiction.xls",byteArrayOf(1))) }
 }
 @Test fun `XLSX shared strings sparse columns and absolute worksheet targets preserve values`() {
  val sheet="""<worksheet><row><c r="B1" t="s"><v>0</v></c><c r="D1" t="s"><v>1</v></c></row><row><c r="A2"/></row></worksheet>"""
  val result=TabularImport.read(workbook(sheet,"/xl/worksheets/one.xml","<sst><si><r><t>Alice</t></r><r><t> Anne</t></r></si><si><t>Exemple</t></si></sst>"))
  assertEquals(listOf(listOf("","Alice Anne","","Exemple")),result.sheets.single().rows)
 }
 @Test fun `XLSX rejects invalid links text references oversized cells and excessive XML depth`() {
  val ordinary="<worksheet><row><c r=\"A1\"><v>1</v></c></row></worksheet>"
  for(target in listOf("../secret.xml","worksheets/../secret.xml","https://example.invalid/one.xml"))
   assertThrows(IllegalArgumentException::class.java) { TabularImport.read(workbook(ordinary,target)) }
  assertThrows(IllegalArgumentException::class.java) { TabularImport.read(workbook(ordinary,external=true)) }
  for(cell in listOf("<c r=\"AZ1\"><v>1</v></c>","<c><v>1</v></c>","<c r=\"A1\" t=\"s\"><v>bad</v></c>","<c r=\"A1\"><v>${"x".repeat(1001)}</v></c>"))
   assertThrows(RuntimeException::class.java) { TabularImport.read(workbook("<worksheet><row>$cell</row></worksheet>")) }
  assertThrows(IllegalArgumentException::class.java) { TabularImport.read(workbook("<worksheet>"+"<a>".repeat(65)+"</a>".repeat(65)+"</worksheet>")) }
  assertThrows(IllegalArgumentException::class.java) { TabularImport.read(zip(mapOf("only.txt" to "missing workbook"))) }
  assertThrows(IllegalArgumentException::class.java) { TabularImport.read(zip((1..201).associate { "$it.txt" to "" })) }
  assertThrows(IllegalArgumentException::class.java) { TabularImport.read(zip(mapOf("large.txt" to "x".repeat(8_000_001)))) }
 }
 @Test fun `roster matching rejects ambiguity and retains identifiers across normalized names`() {
  val pupil=Pupil(firstName="Alice",lastName="Exemple",sex=Sex.GIRL)
  val cls=SchoolClass(newId(),"6e Test","6e",listOf(pupil))
  assertEquals(pupil.id,ClassImport.reconcile(cls.copy(pupils=listOf(pupil.copy(firstName=" ALICE "))),cls).pupils.single().id)
  for(bad in listOf(cls.copy(name=""),cls.copy(name="x".repeat(101)),cls.copy(level="unknown"),cls.copy(pupils=emptyList()),cls.copy(pupils=List(31){pupil})))
   assertThrows(IllegalArgumentException::class.java) { ClassImport.reconcile(bad,null) }
  assertThrows(IllegalArgumentException::class.java) { ClassImport.reconcile(cls,cls.copy(pupils=listOf(pupil,pupil.copy(id=newId())))) }
 }
 @Test fun `preparation rejects missing choices and incompatible grading before publication`() {
  val cls=demoClasses().first();val table=PerformanceTable(name="Fictional",distanceMeters=1000,sourceMaxTenths=70,tables=Sex.entries.associateWith { listOf(PerformanceThreshold(60000,70)) })
  val draft=TeacherDraft(classId=cls.id,tableId=table.id)
  for(bad in listOf(draft.copy(teacherName=""),draft.copy(teacherName="x".repeat(41)),draft.copy(classId="missing"),draft.copy(tableId="missing"),draft.copy(distance="bad"),draft.copy(laps="bad"),draft.copy(laps="0"),draft.copy(laps="21"),draft.copy(total="101"),draft.copy(total="0")))
   assertThrows(RuntimeException::class.java) { bad.session(listOf(cls),listOf(table)) }
  for(bad in listOf(table.copy(id="../bad"),table.copy(distanceMeters=399),table.copy(distanceMeters=450),table.copy(sourceMaxTenths=0))) assertThrows(IllegalArgumentException::class.java,bad::validate)
 }
 @Test fun `time and roster values reject unsupported profiles and invalid durations`() {
  assertEquals(83500L,ImportValues.time("83,5",ImportTimeUnit.SECONDS))
  assertEquals(83500L,ImportValues.time("83,5",ImportTimeUnit.CLOCK))
  assertEquals(83500L,ImportValues.time("1'23,5",ImportTimeUnit.CLOCK))
  assertEquals("prenom",ImportValues.normalized(" Prénom "))
  for(time in listOf("1:60","-1","0","25:00:00","1:-2","NaN","90000")) assertThrows(RuntimeException::class.java) { ImportValues.time(time,ImportTimeUnit.CLOCK) }
  for(sex in listOf("F","fille","féminin","M","G","garçon","masculin")) assertEquals(1,ImportValues.pupils(ImportSheet("Fictional",listOf(listOf("Exemple","Alice",sex))),0,0,1,2).size)
  assertThrows(RuntimeException::class.java) { ImportValues.pupils(ImportSheet("Fictional",listOf(listOf("Exemple","Alice","?"))),0,0,1,2) }
  assertThrows(IllegalArgumentException::class.java) { ImportValues.performance(ImportSheet("Fictional",listOf(listOf("1:00","7"))),0,0,0,ImportTimeUnit.CLOCK) }
 }
}
