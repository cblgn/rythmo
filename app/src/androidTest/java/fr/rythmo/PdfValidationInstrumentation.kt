package fr.rythmo

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import fr.rythmo.session.*
import java.io.File

/** Runs on Android's real PDF implementation without a third-party test runner. */
class PdfValidationInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            val directory = File(targetContext.cacheDir, "pdf-validation-${newId()}").also { check(it.mkdirs()) }
            for (count in listOf(6, 20)) {
                val session = demoSession().copy(distanceMeters = 1000, lapCount = count,
                    assessment = AssessmentRubric(schemaVersion = 2, name = "Fictional validation", sourceMaxTenths = 70,
                        performanceMaxTenths = 80, comparisonMaxTenths = (count - 1) * 10,
                        tables = Sex.entries.associateWith { listOf(PerformanceThreshold(60_000, 60)) }))
                val runner = RunnerRecord(pupil = session.pupils.first(), rawCumulativeMs = List(count) { (it + 1) * 70_000L })
                val report = RaceGroup(session = session, runners = listOf(runner)).report(runner)
                if (count == 6) { check(report.assessmentScore?.totalTenths == 119); check(report.assessmentScore?.outOf20Tenths == 182) }
                render(PdfExporter.export(targetContext, report, File(directory, "laps-$count.pdf")), directory)
            }
            val session = demoSession()
            val runner = RunnerRecord(pupil = session.pupils.first(), rawCumulativeMs = List(5) { (it + 1) * 90_000L })
            render(PdfExporter.export(targetContext, RaceGroup(session = session, runners = listOf(runner)).report(runner), File(directory, "legacy.pdf")), directory)
            result.putString("stream", "PDF validation passed: ${directory.absolutePath}\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Exception) {
            result.putString("stream", "PDF validation failed: ${error.message}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
    private fun render(file: File, directory: File) {
        check(file.length() > 0)
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
            check(renderer.pageCount in 1..3)
            for (index in 0 until renderer.pageCount) renderer.openPage(index).use { page ->
                val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                File(directory, "${file.nameWithoutExtension}-$index.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
            }
        }
    }
}
