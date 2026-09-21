package fr.rythmo.session

import fr.rythmo.domain.LapResult
import kotlinx.serialization.Serializable

@Serializable
data class PerformanceThreshold(val timeMs: Long, val pointsTenths: Int)

/** Ascending lower bounds, like Excel's approximate VLOOKUP; values outside the table are clamped. */
@Serializable
data class AssessmentRubric(
    val schemaVersion: Int = 1,
    val version: String = newId(),
    val name: String,
    val performanceMaxTenths: Int,
    val comparisonMaxTenths: Int,
    val comparisonThresholdMs: Long = 0,
    val tables: Map<Sex, List<PerformanceThreshold>>,
    val sourceMaxTenths: Int = performanceMaxTenths,
) {
    val maxTenths: Int get() = performanceMaxTenths + comparisonMaxTenths
    fun validate() {
        require(schemaVersion in 1..2 && validId(version)) { "Version de barème incompatible." }
        require(name.isNotBlank() && name.length <= 100)
        require(performanceMaxTenths in 0..1000 && comparisonMaxTenths in 0..1000 && maxTenths in 10..1000) { "Le total du barème doit être compris entre 1 et 100 points." }
        require(comparisonThresholdMs in -60_000L..60_000L) { "Le seuil doit être compris entre −60 et +60 secondes." }
        require(sourceMaxTenths in 0..1000)
        if (schemaVersion == 1) require(sourceMaxTenths == performanceMaxTenths)
        if (schemaVersion == 2) require(comparisonThresholdMs == 0L && performanceMaxTenths > 0 && sourceMaxTenths > 0)
        require(tables.keys == Sex.entries.toSet()) { "Une table est requise pour chaque profil." }
        tables.values.forEach { rows ->
            require(rows.size in 1..1000 && rows.all { it.timeMs in 1..86_400_000 && it.pointsTenths in 0..sourceMaxTenths }) { "Seuil de performance invalide." }
            require(rows.zipWithNext().all { (a, b) -> a.timeMs < b.timeMs && a.pointsTenths >= b.pointsTenths }) { "Les temps doivent augmenter et les points ne doivent pas augmenter." }
        }
    }
    fun score(laps: List<LapResult>, sex: Sex, equalLaps: Boolean, referenceMeters: Int): AssessmentScore {
        validate()
        require(laps.isNotEmpty())
        val table = tables.getValue(sex)
        val applied = table.lastOrNull { it.timeMs <= laps.last().cumulativeMs } ?: table.first()
        val performance = applied.pointsTenths
        val eligible = laps.zipWithNext().filter { (a, b) -> equalLaps || (a.segmentMeters == referenceMeters && b.segmentMeters == referenceMeters) }
        require(comparisonMaxTenths == 0 || eligible.isNotEmpty()) { "La comparaison nécessite au moins deux intervalles comparables." }
        if (schemaVersion == 2) {
            require(equalLaps && comparisonMaxTenths == eligible.size * 10)
            val successes = eligible.count { (a, b) -> roundedSeconds(b.durationMs) <= roundedSeconds(a.durationMs) }
            val performanceNumerator = performance.toLong() * performanceMaxTenths
            val totalNumerator = successes * 10L * sourceMaxTenths + performanceNumerator
            return AssessmentScore(roundedRatio(performanceNumerator, sourceMaxTenths.toLong()), performanceMaxTenths,
                successes * 10, comparisonMaxTenths, successes, eligible.size,
                roundedRatio(totalNumerator, sourceMaxTenths.toLong()), maxTenths,
                roundedRatio(totalNumerator * 200, sourceMaxTenths.toLong() * maxTenths),
                performance, sourceMaxTenths, applied.timeMs)
        }
        val successes = eligible.count { (a, b) -> b.durationMs - a.durationMs <= comparisonThresholdMs }
        val comparison = if (eligible.isEmpty()) 0 else roundedRatio(comparisonMaxTenths.toLong() * successes, eligible.size.toLong())
        val total = performance + comparison
        return AssessmentScore(performance, performanceMaxTenths, comparison, comparisonMaxTenths, successes, eligible.size,
            total, maxTenths, roundedRatio(total.toLong() * 200, maxTenths.toLong()), performance, sourceMaxTenths, applied.timeMs)
    }
}

fun roundedSeconds(milliseconds: Long): Long = (milliseconds + 500) / 1000

private fun roundedRatio(numerator: Long, denominator: Long): Int = ((numerator + denominator / 2) / denominator).toInt()

@Serializable
data class AssessmentScore(
    val performanceTenths: Int, val performanceMaxTenths: Int,
    val comparisonTenths: Int, val comparisonMaxTenths: Int,
    val successfulComparisons: Int, val comparisons: Int,
    val totalTenths: Int, val maxTenths: Int, val outOf20Tenths: Int,
    val sourcePerformanceTenths: Int = performanceTenths,
    val sourceMaxTenths: Int = performanceMaxTenths,
    val appliedThresholdMs: Long? = null,
)

/** A portable draft has no server identity, associations, results or authentication data. */
@Serializable
data class PreparationPackage(val schemaVersion: Int = 1, val session: SessionConfig) {
    fun validate() { require(schemaVersion == 1) { "Version de configuration incompatible." }; session.validate() }
}
