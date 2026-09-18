package fr.rythmo

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import android.text.TextUtils
import fr.rythmo.domain.*
import fr.rythmo.session.formatPoints
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.format.DateTimeFormatter

object PdfExporter {
    fun export(context: Context, report: RaceReport, destination: File? = null): File {
        val directory = destination?.parentFile ?: File(context.filesDir, "reports")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Dossier PDF inaccessible")
        val file = destination ?: File.createTempFile("rythmo-", ".pdf", directory)
        val pending = File(directory, file.name + ".pending")
        val document = PdfDocument()
        try {
            var pageNumber = 1
            var page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(23, 32, 51) }
            var y = 48f
            fun text(value: String, x: Float = 40f, line: Float = y, size: Float = 12f, bold: Boolean = false) {
                paint.textSize = size
                paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                page.canvas.drawText(TextUtils.ellipsize(value, paint, 555f - x, TextUtils.TruncateAt.END).toString(), x, line, paint)
            }
            fun nextPage() {
                document.finishPage(page)
                page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, ++pageNumber).create())
                y = 48f
                text("Rythmo · ${report.student.firstName} ${report.student.lastName} · Suite", bold = true)
                y += 36f
            }
            paint.color = Color.rgb(24, 90, 188)
            text("Rythmo", size = 28f, bold = true); y += 28f
            text("Évaluation demi-fond · ${report.distanceMeters} m" + (report.equalLapCount?.let { " · $it tours" } ?: ""), size = 16f); y += 34f
            paint.color = Color.rgb(23, 32, 51)
            text("${report.student.firstName} ${report.student.lastName}", size = 18f, bold = true); y += 24f
            text("${report.student.schoolClass} · ${report.date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}"); y += 20f
            report.assessmentDetails?.let { text(it, size = 10f); y += 20f }
            report.startedAt?.let { text("Évaluation commencée à ${it.format(DateTimeFormatter.ofPattern("HH:mm:ss"))}", size = 10f); y += 18f }
            text(if (report.timingMode == TimingMode.AUTOMATIC)
                "Chrono automatique · Départ à ${report.chronoStartedAt?.format(DateTimeFormatter.ofPattern("HH:mm:ss")) ?: "—"}"
                else "Saisie manuelle par tour", size = 10f); y += 28f
            val columns = listOf(40f, 150f, 255f, 365f)
            fun headings() {
                listOf("Distance", "Segment", "Total", "Écart / tour préc.").forEachIndexed { i, label -> text(label, columns[i], bold = true) }
                y += 30f
            }
            headings()
            report.result.laps.forEach { lap ->
                if (y > 710f) { nextPage(); headings() }
                text(report.equalLapCount?.let { "Tour ${lap.number}/$it" } ?: "${lap.distanceMeters} m", columns[0]); text(TimeFormat.duration(lap.durationMs), columns[1])
                text(TimeFormat.duration(lap.cumulativeMs), columns[2])
                paint.color = when (lap.paceChange) {
                    PaceChange.FASTER -> Color.rgb(20, 108, 58)
                    PaceChange.SLOWER -> Color.rgb(179, 38, 30)
                    else -> Color.rgb(24, 90, 188)
                }
                text(lap.differenceMs?.let(TimeFormat::difference) ?: "—", columns[3], bold = true)
                lap.paceChange?.let { text(it.label, columns[3] + 92f, size = 9f) }
                paint.color = Color.DKGRAY
                y += 15f
                if (report.equalLapCount == null && lap.segmentMeters != report.referenceLapMeters) { text("Segment de ${lap.segmentMeters} m : écart non comparable au tour complet", 150f, size = 9f); y += 14f }
                report.corrections.find { it.lapNumber == lap.number }?.let {
                    text("Initial : ${TimeFormat.duration(it.originalMs)} → Corrigé : ${TimeFormat.duration(it.correctedMs)} à ${it.correctedAt.format(DateTimeFormatter.ofPattern("HH:mm:ss"))}", 150f, size = 9f)
                    y += 15f
                }
                paint.color = Color.LTGRAY; page.canvas.drawLine(40f, y, 555f, y, paint)
                paint.color = Color.rgb(23, 32, 51); y += 28f
            }
            if (y + 310f > 800f) nextPage()
            text("Bilan de l’épreuve", size = 20f, bold = true); y += 34f
            report.gradeTenths?.let {
                text("Note provisoire · barème de démonstration", size = 12f)
                text("${formatPoints(it)} / ${formatPoints(report.maxGradeTenths)}", 420f, size = 18f, bold = true); y += 30f
            }
            val result = report.result
            listOf("Temps total" to TimeFormat.duration(result.totalMs),
                (if (report.equalLapCount != null) "Temps moyen / tour" else "Temps moyen / ${report.referenceLapMeters} m") to TimeFormat.duration(result.averageLapMs),
                "Progression sur tours complets" to TimeFormat.difference(result.progressionMs),
                "Irrégularité cumulée" to TimeFormat.difference(result.cumulativeIrregularityMs).removePrefix("+")
            ).forEach { (label, value) -> text(label); text(value, 420f, bold = true); y += 26f }
            y += 12f
            text("Écart = temps du tour − temps du tour précédent, à distance égale.", size = 10f); y += 18f
            text("Bleu : −1 à +1 s · Vert : plus rapide · Rouge : plus lent.", size = 10f); y += 18f
            text("La progression et l’irrégularité excluent le dernier segment s’il est plus court.", size = 10f); y += 18f
            text("Une correction par passage. Les calculs utilisent les temps corrigés.", size = 10f); y += 18f
            text("La régularité n’intervient pas dans la note de démonstration.", size = 10f)
            report.cancelledPassages.forEach { entry ->
                y += 18f
                if (y > 780f) nextPage()
                text(entry, size = 9f)
            }
            document.finishPage(page)
            FileOutputStream(pending).use { document.writeTo(it); it.fd.sync() }
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { document.close() }
        return file
    }
}
