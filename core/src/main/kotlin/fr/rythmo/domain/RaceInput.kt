package fr.rythmo.domain

data class RaceAnalysis(
    val laps: List<LapResult>,
    val errors: List<String?>,
    val result: RaceResult?,
)

object RaceInput {
    fun validateLap(minutes: String, seconds: String): SplitValidation {
        if (minutes.isBlank() || seconds.isBlank()) {
            return SplitValidation.Invalid("Renseignez les minutes et les secondes (0 si besoin).")
        }
        val min = minutes.toLongOrNull()
        val sec = seconds.toLongOrNull()
        if (min == null || min < 0 || minutes.any { it !in '0'..'9' }) {
            return SplitValidation.Invalid("Les minutes doivent être un nombre positif ou nul.")
        }
        if (sec == null || sec !in 0..59 || seconds.any { it !in '0'..'9' }) {
            return SplitValidation.Invalid("Les secondes doivent être comprises entre 0 et 59.")
        }
        if (min > (Long.MAX_VALUE / 1_000 - sec) / 60) {
            return SplitValidation.Invalid("Ce temps est trop grand.")
        }
        val durationMs = (min * 60 + sec) * 1_000
        if (durationMs <= 0) return SplitValidation.Invalid("Le temps du tour doit être supérieur à 0:00.")
        return SplitValidation.Accepted(durationMs)
    }

    fun analyze(entries: List<String>): RaceAnalysis {
        require(entries.size == LAP_COUNT)
        val validTimes = mutableListOf<Long>()
        val errors = entries.mapIndexed { index, entry ->
            val time = TimeFormat.parseInput(entry)
            when {
                entry.isBlank() -> null
                time == null -> "Temps invalide : secondes de 00 à 59."
                time <= 0 -> "Le temps doit être supérieur à zéro."
                validTimes.size != index -> "Complétez d’abord les passages précédents."
                time <= (validTimes.lastOrNull() ?: 0L) -> "Le temps doit dépasser le passage précédent."
                else -> { validTimes.add(time); null }
            }
        }
        return RaceAnalysis(
            laps = RaceCalculator.calculateLaps(validTimes),
            errors = errors,
            result = if (validTimes.size == LAP_COUNT) RaceCalculator.calculate(validTimes) else null,
        )
    }
}

sealed interface SplitValidation {
    data class Accepted(val durationMs: Long) : SplitValidation
    data class Invalid(val message: String) : SplitValidation
}
