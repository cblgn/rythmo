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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.format.DateTimeFormatter

object PdfExporter {
    fun export(context: Context, report: RaceReport, destination: File? = null): File {
        val directory = destination?.parentFile ?: File(context.filesDir, "reports")
        Files.createDirectories(directory.toPath())
        val file = destination ?: File.createTempFile("rythmo-", ".pdf", directory)
        val pending = File(directory, file.name + ".pending")
        val document = PdfDocument()
        try {
            var pageNumber = 1
            var page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(23, 32, 51) }
            var y = 48f
            fun text(value: String, x: Float = 40f, line: Float = y, size: Float = 12f, bold: Boolean = false, width: Float = 555f - x, italic: Boolean = false) {
                paint.textSize = size
                paint.typeface = if (italic) Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC) else if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                page.canvas.drawText(TextUtils.ellipsize(value, paint, width, TextUtils.TruncateAt.END).toString(), x, line, paint)
            }
            fun nextPage() {
                document.finishPage(page)
                page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, ++pageNumber).create())
                y = 48f
                text("Rythmo · ${report.student.firstName} ${report.student.lastName} · Suite", bold = true)
                y += 36f
            }
            paint.color = Color.rgb(174, 65, 23)
            context.getDrawable(fr.rythmo.R.drawable.ic_rythmo)?.let { logo -> logo.setBounds(40, 25, 72, 65); logo.draw(page.canvas) }
            text("Rythmo", x = 82f, size = 28f, bold = true, italic = true); y += 28f
            text("Évaluation demi-fond · ${report.distanceMeters} m" + (report.equalLapCount?.let { " · $it tours" } ?: ""), size = 16f); y += 34f
            paint.color = Color.rgb(23, 32, 51)
            text("${report.student.firstName} ${report.student.lastName}", size = 18f, bold = true); y += 24f
            text("${report.student.schoolClass} · ${report.date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}"); y += 20f
            report.assessmentDetails?.let { text(it, size = 10f); y += 20f }
            report.startedAt?.let { text("Évaluation commencée à ${it.format(DateTimeFormatter.ofPattern("HH:mm:ss"))}", size = 10f); y += 18f }
            text(if (report.timingMode == TimingMode.AUTOMATIC)
                "Chrono automatique · Départ à ${report.chronoStartedAt?.format(DateTimeFormatter.ofPattern("HH:mm:ss")) ?: "—"}"
                else "Saisie manuelle par tour", size = 10f); y += 28f
            val columns = listOf(40f, 155f, 285f, 465f)
            fun headings() {
                listOf("Passage", "Temps du tour", "Écart / précédent", "Cumul").forEachIndexed { i, label -> text(label, columns[i], bold = true) }
                y += 30f
            }
            headings()
            report.result.laps.forEach { lap ->
                if (y > 710f) { nextPage(); headings() }
                text(report.equalLapCount?.let { "Tour ${lap.number}/$it" } ?: "${lap.distanceMeters} m", columns[0]); text(TimeFormat.duration(lap.durationMs), columns[1])
                text(TimeFormat.duration(lap.cumulativeMs), columns[3])
                paint.color = when (lap.paceChange) {
                    PaceChange.FASTER -> Color.rgb(20, 108, 58)
                    PaceChange.SLOWER -> Color.rgb(179, 38, 30)
                    else -> Color.rgb(24, 90, 188)
                }
                text(lap.differenceMs?.let(TimeFormat::difference) ?: "—", columns[2], bold = true)
                lap.paceChange?.let { text(it.label, columns[2] + 80f, size = 9f) }
                paint.color = Color.DKGRAY
                y += 15f
                if (report.equalLapCount == null && lap.segmentMeters != report.referenceLapMeters) { text("Segment de ${lap.segmentMeters} m : écart non comparable au tour complet", 150f, size = 9f); y += 14f }
                report.corrections.find { it.lapNumber == lap.number }?.let {
                    text("Initial : ${TimeFormat.duration(it.originalMs)} → Corrigé : ${TimeFormat.duration(it.correctedMs)} à ${it.correctedAt.format(DateTimeFormatter.ofPattern("HH:mm:ss"))}", 150f, size = 9f)
                    y += 15f
                }
                paint.color = Color.LTGRAY; page.canvas.drawLine(40f, y, 555f, y, paint)
                paint.color = Color.rgb(23, 32, 51); y += 15f
            }
            fun line(value: String, size: Float = 10f) {
                if (y > 775f) nextPage()
                text(value, size = size); y += 17f
            }
            line("Écart = temps du tour − temps du tour précédent, à distance égale.", 9f)
            line("Allure : bleu ±1 s · vert plus rapide · rouge plus lent.", 9f)
            report.cancelledPassages.forEach { line(it, 9f) }
            if (report.corrections.isNotEmpty()) line("Les corrections historiques sont conservées.", 9f)
            if (y + 80f > 790f) nextPage()
            y += 8f
            text("Bilan chronométrique", size = 16f, bold = true); y += 26f
            listOf("Temps total" to TimeFormat.duration(report.result.totalMs),
                (if (report.equalLapCount != null) "Temps moyen / tour" else "Temps moyen / ${report.referenceLapMeters} m") to TimeFormat.duration(report.result.averageLapMs)
            ).forEach { (label, value) -> text(label); text(value, 430f, bold = true); y += 22f }
            val score = report.assessmentScore
            if (score != null) {
                val rubric = report.assessment
                if (y + 245f > 790f) nextPage()
                y += 12f
                text("Barème et résultat", size = 16f, bold = true); y += 24f
                val source = "${formatPoints(score.sourcePerformanceTenths)} / ${formatPoints(score.sourceMaxTenths)}"
                line("Performance : $source selon la table du profil de l’élève.")
                score.appliedThresholdMs?.let { line("Palier appliqué : ${TimeFormat.duration(it)} (sans interpolation).") }
                val rule = if (rubric?.schemaVersion == 2) "Même temps ou plus rapide après arrondi à la seconde : 1 point." else {
                    val threshold = rubric?.comparisonThresholdMs ?: 0L
                    when {
                        threshold == 0L -> "Comparaison réussie : même temps ou plus rapide que le tour précédent."
                        threshold < 0 -> "Comparaison réussie : gagner au moins ${TimeFormat.difference(-threshold).removePrefix("+")} sur le tour précédent."
                        else -> "Comparaison réussie : au plus ${TimeFormat.difference(threshold).removePrefix("+")} de plus que le tour précédent."
                    }
                }
                line(rule)
                line("${score.successfulComparisons} / ${score.comparisons} comparaisons réussies.")
                y += 4f
                listOf("Régularité" to "${formatPoints(score.comparisonTenths)} / ${formatPoints(score.comparisonMaxTenths)}",
                    "Performance pondérée" to "${formatPoints(score.performanceTenths)} / ${formatPoints(score.performanceMaxTenths)}",
                    "Total" to "${formatPoints(score.totalTenths)} / ${formatPoints(score.maxTenths)}"
                ).forEach { (label, value) -> text(label); text(value, 430f, bold = true); y += 22f }
                val expression = if (rubric?.schemaVersion == 2)
                    "(${formatPoints(score.comparisonTenths)} + ${formatPoints(score.sourcePerformanceTenths)} × ${formatPoints(score.performanceMaxTenths)} / ${formatPoints(score.sourceMaxTenths)})"
                    else formatPoints(score.totalTenths)
                line("Conversion : $expression × 20 / ${formatPoints(score.maxTenths)} ; arrondi final au dixième.", 9f)
                y += 6f
                paint.color = Color.rgb(248, 235, 225)
                page.canvas.drawRoundRect(40f, y - 5f, 555f, y + 44f, 10f, 10f, paint)
                paint.color = Color.rgb(174, 65, 23)
                text("Note finale", 54f, y + 24f, 16f, true)
                text("${formatPoints(score.outOf20Tenths)} / 20", 400f, y + 25f, 23f, true)
            } else report.gradeTenths?.let { grade ->
                if (y + 110f > 790f) nextPage()
                y += 12f
                line("Barème de démonstration : la régularité ne modifie pas la note.")
                line("Total : ${formatPoints(grade)} / ${formatPoints(report.maxGradeTenths)} ; conversion sur 20.")
                y += 12f
                text("Note finale", size = 16f, bold = true)
                val normalized = ((grade.toLong() * 200 + report.maxGradeTenths / 2) / report.maxGradeTenths).toInt()
                text("${formatPoints(normalized)} / 20", 400f, size = 23f, bold = true)
            }
            document.finishPage(page)
            FileOutputStream(pending).use { document.writeTo(it); it.fd.sync() }
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { document.close() }
        return file
    }
}
