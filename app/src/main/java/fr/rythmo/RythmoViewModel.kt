package fr.rythmo

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.rythmo.domain.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val SCHOOL_CLASSES = listOf("3e Ouessant", "3e Baléares", "3e Hoedic", "3e Belle-ile")
enum class RythmoScreen { IDENTIFICATION, RACE }

data class RythmoState(
    val lastName: String = "",
    val firstName: String = "",
    val schoolClass: String = "",
    val date: LocalDate = LocalDate.now(),
    val startedAt: LocalDateTime? = null,
    val screen: RythmoScreen = RythmoScreen.IDENTIFICATION,
    val cumulativeTimesMs: List<Long> = emptyList(),
    val minutes: String = "",
    val seconds: String = "",
    val inputError: String? = null,
    val editingLapNumber: Int? = null,
    val corrections: List<LapCorrection> = emptyList(),
    val timingMode: TimingMode = TimingMode.MANUAL,
    val chronoStartedAt: LocalDateTime? = null,
    val timerStartedElapsedMs: Long? = null,
    val elapsedMs: Long = 0L,
) {
    val student: Student get() = Student(lastName.trim(), firstName.trim(), schoolClass)
    val canStart: Boolean get() = student.isComplete && schoolClass in SCHOOL_CLASSES
    val formattedDate: String get() = date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE))
    val formattedStartTime: String? get() = startedAt?.format(DateTimeFormatter.ofPattern("HH:mm", Locale.FRANCE))
    val studentLabel: String get() = "${student.firstName} ${student.lastName.uppercase(Locale.FRANCE)} • $schoolClass"
    val laps: List<LapResult> get() = RaceCalculator.calculateLaps(cumulativeTimesMs)
    val nextDistance: Int? get() = if (cumulativeTimesMs.size < LAP_COUNT) (cumulativeTimesMs.size + 1) * LAP_DISTANCE_METERS else null
    val result: RaceResult? get() = if (cumulativeTimesMs.size == LAP_COUNT) RaceCalculator.calculate(cumulativeTimesMs) else null
    val timerRunning: Boolean get() = timingMode == TimingMode.AUTOMATIC && timerStartedElapsedMs != null && nextDistance != null
    val hasTimingData: Boolean get() = timerStartedElapsedMs != null || cumulativeTimesMs.isNotEmpty()
    val currentLapElapsedMs: Long get() = (elapsedMs - (cumulativeTimesMs.lastOrNull() ?: 0L)).coerceAtLeast(0L)
    val report: RaceReport? get() = result?.takeIf { canStart && editingLapNumber == null }
        ?.let { RaceReport(student, date, it, startedAt, corrections, timingMode, chronoStartedAt) }
}

class RythmoViewModel @JvmOverloads constructor(
    private val savedState: SavedStateHandle,
    private val monotonicNow: () -> Long = SystemClock::elapsedRealtime,
) : ViewModel() {
    private var ticker: Job? = null
    var state by mutableStateOf(
        RythmoState(
            lastName = savedState["lastName"] ?: "",
            firstName = savedState["firstName"] ?: "",
            schoolClass = savedState["schoolClass"] ?: "",
            date = savedState.get<String>("date")?.let(LocalDate::parse) ?: LocalDate.now(),
            startedAt = savedState.get<String>("startedAt")?.let(LocalDateTime::parse),
            screen = savedState.get<String>("screen")?.let(RythmoScreen::valueOf) ?: RythmoScreen.IDENTIFICATION,
            cumulativeTimesMs = savedState.get<LongArray>("times")?.toList() ?: emptyList(),
            minutes = savedState["minutes"] ?: "",
            seconds = savedState["seconds"] ?: "",
            editingLapNumber = savedState["editingLapNumber"],
            timingMode = savedState.get<String>("timingMode")?.let(TimingMode::valueOf) ?: TimingMode.MANUAL,
            chronoStartedAt = savedState.get<String>("chronoStartedAt")?.let(LocalDateTime::parse),
            timerStartedElapsedMs = savedState["timerStartedElapsedMs"],
            elapsedMs = savedState["elapsedMs"] ?: 0L,
            corrections = (1..LAP_COUNT).mapNotNull { number ->
                savedState.get<Long>("original$number")?.let { original ->
                    LapCorrection(number, original, checkNotNull(savedState["corrected$number"]),
                        LocalDateTime.parse(checkNotNull(savedState["correctedAt$number"])))
                }
            },
        )
    )
        private set

    init { if (state.timerRunning) startTicker() }

    fun setLastName(value: String) = update(state.copy(lastName = value))
    fun setFirstName(value: String) = update(state.copy(firstName = value))
    fun setSchoolClass(value: String) = update(state.copy(schoolClass = value))
    fun setTimingMode(value: TimingMode) {
        if (!state.hasTimingData) update(state.copy(timingMode = value, minutes = "", seconds = "", inputError = null))
    }
    fun resetEvaluation(keepStudent: Boolean = false, timingMode: TimingMode = state.timingMode) {
        ticker?.cancel()
        ticker = null
        update(RythmoState(
            lastName = if (keepStudent) state.lastName else "",
            firstName = if (keepStudent) state.firstName else "",
            schoolClass = if (keepStudent) state.schoolClass else "",
            timingMode = timingMode,
        ))
    }
    fun setMinutes(value: String) = update(state.copy(minutes = value, inputError = null))
    fun setSeconds(value: String) = update(state.copy(seconds = value, inputError = null))
    fun editStudent() = update(state.copy(screen = RythmoScreen.IDENTIFICATION))
    fun startRace() {
        if (state.canStart) {
            val start = state.startedAt ?: LocalDateTime.now()
            update(state.copy(screen = RythmoScreen.RACE, startedAt = start, date = start.toLocalDate()))
        }
    }

    fun addPassage(): Boolean {
        if (state.screen != RythmoScreen.RACE || state.nextDistance == null || state.timingMode != TimingMode.MANUAL) return false
        return when (val validation = RaceInput.validateLap(state.minutes, state.seconds)) {
            is SplitValidation.Invalid -> {
                update(state.copy(inputError = validation.message))
                false
            }
            is SplitValidation.Accepted -> {
                val cumulativeTimes = try {
                    RaceCalculator.appendLap(state.cumulativeTimesMs, validation.durationMs)
                } catch (_: ArithmeticException) {
                    update(state.copy(inputError = "Le temps total est trop grand."))
                    return false
                }
                update(state.copy(
                    cumulativeTimesMs = cumulativeTimes,
                    minutes = "",
                    seconds = "",
                    inputError = null,
                ))
                true
            }
        }
    }

    fun startStopwatch() {
        if (state.screen != RythmoScreen.RACE || state.timingMode != TimingMode.AUTOMATIC ||
            state.timerStartedElapsedMs != null || state.cumulativeTimesMs.isNotEmpty()) return
        update(state.copy(timerStartedElapsedMs = monotonicNow(), chronoStartedAt = LocalDateTime.now(), elapsedMs = 0L))
        startTicker()
    }

    fun recordAutomaticPassage(): Boolean {
        if (!state.timerRunning) return false
        val elapsed = monotonicNow() - requireNotNull(state.timerStartedElapsedMs)
        if (elapsed <= (state.cumulativeTimesMs.lastOrNull() ?: 0L)) return false
        update(state.copy(cumulativeTimesMs = state.cumulativeTimesMs + elapsed, elapsedMs = elapsed))
        if (!state.timerRunning) ticker?.cancel()
        return true
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (state.timerRunning) {
                val elapsed = monotonicNow() - requireNotNull(state.timerStartedElapsedMs)
                if (elapsed < 0) {
                    update(state.copy(timerStartedElapsedMs = null, inputError = "Chronomètre interrompu après un redémarrage du téléphone."))
                    break
                }
                state = state.copy(elapsedMs = elapsed)
                delay(100)
            }
        }
    }

    fun submitTime(): Boolean = if (state.editingLapNumber == null) addPassage() else confirmCorrection()

    fun editLap(number: Int) {
        if (state.result == null || number !in 1..LAP_COUNT || state.corrections.any { it.lapNumber == number }) return
        val duration = state.laps[number - 1].durationMs
        update(state.copy(editingLapNumber = number,
            minutes = if (state.timingMode == TimingMode.AUTOMATIC) "" else (duration / 60_000).toString(),
            seconds = if (state.timingMode == TimingMode.AUTOMATIC) "" else ((duration / 1_000) % 60).toString().padStart(2, '0'), inputError = null))
    }

    fun cancelCorrection() = update(state.copy(editingLapNumber = null, minutes = "", seconds = "", inputError = null))

    fun confirmCorrection(): Boolean {
        val number = state.editingLapNumber ?: return false
        if (state.result == null || state.corrections.any { it.lapNumber == number }) return false
        val validation = RaceInput.validateLap(state.minutes, state.seconds)
        if (validation is SplitValidation.Invalid) {
            update(state.copy(inputError = validation.message))
            return false
        }
        val duration = (validation as SplitValidation.Accepted).durationMs
        val original = state.laps[number - 1].durationMs
        if (duration == original) {
            update(state.copy(inputError = "Le temps est inchangé. Modifiez-le ou annulez."))
            return false
        }
        val laps = state.laps.map { if (it.number == number) duration else it.durationMs }
        val times = try { RaceCalculator.cumulativeTimes(laps) } catch (_: ArithmeticException) {
            update(state.copy(inputError = "Le temps total est trop grand."))
            return false
        }
        val correction = LapCorrection(number, original, duration, LocalDateTime.now())
        update(state.copy(cumulativeTimesMs = times, corrections = state.corrections + correction,
            editingLapNumber = null, minutes = "", seconds = "", inputError = null))
        return true
    }

    private fun update(value: RythmoState) {
        state = value
        savedState["lastName"] = value.lastName
        savedState["firstName"] = value.firstName
        savedState["schoolClass"] = value.schoolClass
        savedState["date"] = value.date.toString()
        savedState["startedAt"] = value.startedAt?.toString()
        savedState["screen"] = value.screen.name
        savedState["times"] = value.cumulativeTimesMs.toLongArray()
        savedState["minutes"] = value.minutes
        savedState["seconds"] = value.seconds
        savedState["editingLapNumber"] = value.editingLapNumber
        savedState["timingMode"] = value.timingMode.name
        savedState["chronoStartedAt"] = value.chronoStartedAt?.toString()
        savedState["timerStartedElapsedMs"] = value.timerStartedElapsedMs
        savedState["elapsedMs"] = value.elapsedMs
        (1..LAP_COUNT).forEach { number ->
            val correction = value.corrections.find { it.lapNumber == number }
            savedState["original$number"] = correction?.originalMs
            savedState["corrected$number"] = correction?.correctedMs
            savedState["correctedAt$number"] = correction?.correctedAt?.toString()
        }
    }
}
