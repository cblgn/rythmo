package fr.rythmo

import android.app.Application
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import fr.rythmo.domain.*
import fr.rythmo.session.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [RecordingPdfDocument::class])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class PdfExporterTest {
    @Test fun `weighted reports keep final grade last and paginate long races`() {
        val app = RuntimeEnvironment.getApplication()
        for (count in listOf(1,6,20)) {
            val session = demoSession().copy(distanceMeters = 1000, lapCount = count, assessment = AssessmentRubric(schemaVersion = 2,
                name = "Fictional", performanceMaxTenths = 80, comparisonMaxTenths = (count-1)*10, sourceMaxTenths = 70,
                tables = Sex.entries.associateWith { listOf(PerformanceThreshold(60_000,60)) }))
            val runner = RunnerRecord(pupil = session.pupils.first(), rawCumulativeMs = List(count) { (it+1)*70_000L })
            val report = RaceGroup(session = session, runners = listOf(runner), startedAt = "2026-09-21T10:00:00").report(runner)
            val file = PdfExporter.export(app, report, File(app.cacheDir,"laps-$count.pdf"))
            assertTrue(file.length() > 0)
            assertFalse(File(file.parentFile,"${file.name}.pending").exists())
            val printed = RecordingPdfDocument.last
            assertEquals(if(count == 20) 2 else 1, printed.pageCount)
            assertEquals("Note finale", printed.texts()[printed.texts().lastIndex - 1])
            assertTrue(printed.texts().last().endsWith("/ 20"))
            if(count == 6) assertEquals(182,report.assessmentScore!!.outOf20Tenths)
        }
    }
    @Test fun `legacy manual and automatic reports preserve corrections and pace directions`() {
        val app = RuntimeEnvironment.getApplication()
        val times = listOf(89_000L,178_000L,265_000L,354_000L,441_000L)
        for (mode in TimingMode.entries) {
            val report = RaceReport(Student("Exemple","Alice","6e Test"),java.time.LocalDate.of(2026,9,21),RaceCalculator.calculate(times),
                LocalDateTime.of(2026,9,21,10,0),listOf(LapCorrection(3,90_000,87_000,LocalDateTime.of(2026,9,21,10,10))),mode)
            PdfExporter.export(app,report)
            assertTrue(RecordingPdfDocument.last.texts().any { it.contains("7:21") })
        }
    }
}
