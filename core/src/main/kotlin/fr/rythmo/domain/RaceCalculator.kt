package fr.rythmo.domain

import kotlin.math.abs

const val LAP_COUNT = 5
const val LAP_DISTANCE_METERS = 400

enum class PaceChange(val label: String) {
    EQUIVALENT("Équivalent"), FASTER("Plus rapide"), SLOWER("Plus lent");

    companion object {
        fun fromDifference(differenceMs: Long): PaceChange = when {
            differenceMs < -1_000L -> FASTER
            differenceMs > 1_000L -> SLOWER
            else -> EQUIVALENT
        }
    }
}

data class LapResult(
    val number: Int,
    val cumulativeMs: Long,
    val durationMs: Long,
    val differenceMs: Long?,
    val distanceMeters: Int = number * LAP_DISTANCE_METERS,
    val segmentMeters: Int = LAP_DISTANCE_METERS,
) {
    val paceChange: PaceChange? get() = differenceMs?.let(PaceChange::fromDifference)
}

data class RaceResult(
    val laps: List<LapResult>,
    val totalMs: Long,
    val averageLapMs: Long,
    val progressionMs: Long,
    val cumulativeIrregularityMs: Long,
)

object RaceCalculator {
    fun cumulativeTimes(lapTimesMs: List<Long>): List<Long> {
        require(lapTimesMs.size <= LAP_COUNT && lapTimesMs.all { it > 0 })
        return lapTimesMs.runningFold(0L) { total, lap -> Math.addExact(total, lap) }.drop(1)
    }

    fun appendLap(cumulativeTimesMs: List<Long>, lapDurationMs: Long): List<Long> {
        require(cumulativeTimesMs.size < LAP_COUNT && lapDurationMs > 0)
        val previous = cumulativeTimesMs.lastOrNull() ?: 0L
        return cumulativeTimesMs + Math.addExact(previous, lapDurationMs)
    }

    fun calculate(cumulativeTimesMs: List<Long>, distances: List<Int> = (1..LAP_COUNT).map { it * LAP_DISTANCE_METERS },
                  equalLengthLaps: Boolean = false, referenceLapMeters: Int = LAP_DISTANCE_METERS): RaceResult {
        require(cumulativeTimesMs.size == distances.size && distances.isNotEmpty()) { "Tous les passages sont requis." }
        val laps = calculateLaps(cumulativeTimesMs, distances, equalLengthLaps)
        val fullLaps = if (equalLengthLaps) laps else laps.filter { it.segmentMeters == referenceLapMeters }
        return RaceResult(
            laps = laps,
            totalMs = cumulativeTimesMs.last(),
            averageLapMs = fullLaps.sumOf { it.durationMs } / fullLaps.size.coerceAtLeast(1),
            progressionMs = if (fullLaps.size > 1) fullLaps.last().durationMs - fullLaps.first().durationMs else 0L,
            cumulativeIrregularityMs = laps.sumOf { abs(it.differenceMs ?: 0L) },
        )
    }

    fun calculateLaps(cumulativeTimesMs: List<Long>, distances: List<Int> = (1..LAP_COUNT).map { it * LAP_DISTANCE_METERS },
                      equalLengthLaps: Boolean = false): List<LapResult> {
        require(distances.isNotEmpty() && distances.first() > 0 && distances.zipWithNext().all { it.second > it.first })
        require(cumulativeTimesMs.size <= distances.size) { "Trop de passages." }
        var previousCumulative = 0L
        var previousLap: Long? = null
        return cumulativeTimesMs.mapIndexed { index, cumulative ->
            require(cumulative > previousCumulative) { "Les temps doivent être strictement croissants." }
            val duration = cumulative - previousCumulative
            val segment = distances[index] - (distances.getOrNull(index - 1) ?: 0)
            val previousSegment = if (index == 0) null else distances[index - 1] - (distances.getOrNull(index - 2) ?: 0)
            LapResult(index + 1, cumulative, duration,
                previousLap?.takeIf { equalLengthLaps || segment == previousSegment }?.let { duration - it }, distances[index], segment).also {
                previousCumulative = cumulative
                previousLap = duration
            }
        }
    }
}
