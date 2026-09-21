package fr.rythmo.session

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer

@Serializable
data class PerformanceTable(
    val id: String = newId(), val name: String, val distanceMeters: Int,
    val sourceMaxTenths: Int, val tables: Map<Sex, List<PerformanceThreshold>>,
) {
    fun validate() {
        require(validId(id) && distanceMeters in 400..4000 && distanceMeters % 100 == 0)
        AssessmentRubric(name = name, performanceMaxTenths = sourceMaxTenths, comparisonMaxTenths = 0, tables = tables).validate()
    }
}

@Serializable
data class TeacherDraft(
    val teacherName: String = "Professeur", val title: String = "Demi-fond",
    val classId: String = "", val distance: String = "1000", val laps: String = "6",
    val total: String = "12", val tableId: String = "",
) {
    fun session(classes: List<SchoolClass>, tables: List<PerformanceTable>): SessionConfig {
        require(teacherName.trim().isNotEmpty() && teacherName.length <= 40) { "Indiquez le nom du professeur (40 caractères maximum)." }
        val cls = requireNotNull(classes.find { it.id == classId }) { "Choisissez une classe." }
        val table = requireNotNull(tables.find { it.id == tableId }) { "Importez et choisissez un barème de performance." }
        table.validate()
        val meters = distance.toIntOrNull() ?: error("Distance invalide.")
        val count = laps.toIntOrNull() ?: error("Nombre de tours invalide.")
        require(count in 1..20) { "Prévoyez de 1 à 20 tours." }
        require(meters == table.distanceMeters) { "Ce barème est prévu pour ${table.distanceMeters} m." }
        val maximum = ImportValues.points(total)
        val comparison = (count - 1) * 10
        require(maximum in 10..1000 && maximum > comparison) { "La note totale doit dépasser ${count - 1} points, avec un maximum de 100." }
        return SessionConfig(title = title.trim(), schoolClass = cls.name, level = cls.level, pupils = cls.pupils,
            distanceMeters = meters, lapCount = count, assessment = AssessmentRubric(schemaVersion = 2,
                name = table.name, performanceMaxTenths = maximum - comparison, comparisonMaxTenths = comparison,
                sourceMaxTenths = table.sourceMaxTenths, tables = table.tables)).also { it.validate() }
    }
}

@Serializable
data class TeacherPreparation(val draft: TeacherDraft = TeacherDraft(), val tables: List<PerformanceTable> = emptyList())

enum class ImportTimeUnit { CLOCK, SECONDS, EXCEL }

object ImportValues {
    fun normalized(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").lowercase(java.util.Locale.ROOT)
    fun points(value: String): Int = decimal(value).multiply(BigDecimal.TEN).setScale(0, RoundingMode.UNNECESSARY).intValueExact()
    private fun decimal(value: String): BigDecimal = value.trim().replace(',', '.').toBigDecimal()
    fun time(value: String, unit: ImportTimeUnit): Long {
        val text = value.trim().replace("'", ":").replace("\"", "")
        val milliseconds = when (unit) {
            ImportTimeUnit.EXCEL -> decimal(text).multiply(BigDecimal(86_400_000))
            ImportTimeUnit.SECONDS -> decimal(text).multiply(BigDecimal(1000))
            ImportTimeUnit.CLOCK -> if (':' !in text) decimal(text).multiply(BigDecimal(1000)) else {
                val parts = text.split(':'); require(parts.size == 2)
                val seconds = decimal(parts[1]); require(seconds >= BigDecimal.ZERO && seconds < BigDecimal(60))
                decimal(parts[0]).multiply(BigDecimal(60)).add(seconds).multiply(BigDecimal(1000))
            }
        }
        return milliseconds.setScale(0, RoundingMode.HALF_UP).longValueExact().also { require(it in 1..86_400_000) }
    }
    fun pupils(sheet: ImportSheet, firstRow: Int, last: Int, first: Int, sex: Int): List<Pupil> {
        require(firstRow in sheet.rows.indices && setOf(last, first, sex).size == 3)
        return sheet.rows.drop(firstRow).filter { it.any(String::isNotBlank) }.mapIndexed { index, row ->
            try {
                val profile = when (normalized(row.getOrElse(sex) { "" })) {
                    "f", "fille", "feminin" -> Sex.GIRL
                    "m", "g", "garcon", "masculin" -> Sex.BOY
                    else -> error("Sexe attendu : F ou M/G.")
                }
                Pupil(lastName = row.getOrElse(last) { "" }.trim(), firstName = row.getOrElse(first) { "" }.trim(), sex = profile).also { it.validate() }
            } catch (e: IllegalArgumentException) { throw IllegalArgumentException("Ligne ${firstRow + index + 1} : ${e.message}", e) }
        }.also { require(it.size in 1..30) { "Une classe contient de 1 à 30 élèves." } }
    }
    fun performance(sheet: ImportSheet, firstRow: Int, timeColumn: Int, pointsColumn: Int,
        unit: ImportTimeUnit, corrections: Map<Int, String> = emptyMap()): List<PerformanceThreshold> {
        require(firstRow in sheet.rows.indices && timeColumn != pointsColumn)
        return sheet.rows.drop(firstRow).mapIndexed { i, row ->
            val number = firstRow + i
            try {
                val corrected = corrections[number]
                PerformanceThreshold(time(corrected ?: row.getOrElse(timeColumn) { "" }, if (corrected != null) ImportTimeUnit.CLOCK else unit),
                    points(row.getOrElse(pointsColumn) { "" }))
            } catch (e: Exception) { throw IllegalArgumentException("${sheet.name}, ligne ${number + 1} : temps ou points invalides. Vérifiez la cellule et les colonnes choisies.", e) }
        }
    }
}
