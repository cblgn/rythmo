package fr.rythmo.session

import fr.rythmo.domain.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

val sessionJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
fun newId(): String = UUID.randomUUID().toString()
fun validId(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_-]{1,80}"))
const val MAX_GROUP_SIZE = 8
fun formatPoints(tenths: Int): String = if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10},${tenths % 10}"

@Serializable enum class Sex { BOY, GIRL }
@Serializable enum class AppRole { CHOICE, TIMER, TEACHER, INDIVIDUAL }

@Serializable
data class Pupil(val id: String = newId(), val lastName: String, val firstName: String, val sex: Sex) {
    val label: String get() = "$firstName $lastName"
    fun validate() { require(validId(id) && lastName.isNotBlank() && firstName.isNotBlank() && label.length <= 160) { "Identité élève invalide." } }
}

@Serializable
data class DemoRubric(
    val name: String = "Démonstration v1",
    val version: String = newId(),
    val reference2000Ms: Long = 600_000L,
    val boyPermille: Int = 1000,
    val girlPermille: Int = 1100,
    val levelsPermille: Map<String, Int> = mapOf("6e" to 1000, "5e" to 950, "4e" to 900, "3e" to 850),
    val maxGradeTenths: Int = 200,
) {
    fun validate() {
        require(name.isNotBlank() && name.length <= 100 && validId(version)) { "Nom ou version du barème invalide." }
        require(reference2000Ms in 60_000L..3_600_000L && boyPermille in 100..3000 && girlPermille in 100..3000)
        require(levelsPermille.keys == setOf("6e", "5e", "4e", "3e") && levelsPermille.values.all { it in 100..3000 })
        require(maxGradeTenths in 10..1000)
    }
    fun referenceMs(distance: Int, level: String, sex: Sex): Long {
        validate()
        require(distance in 400..4000)
        val product = reference2000Ms * distance * (if (sex == Sex.BOY) boyPermille else girlPermille) * requireNotNull(levelsPermille[level])
        return (product + 1_000_000_000L) / 2_000_000_000L
    }
    fun gradeTenths(totalMs: Long, distance: Int, level: String, sex: Sex): Int {
        require(totalMs > 0)
        val reference = referenceMs(distance, level, sex)
        if (totalMs <= reference) return maxGradeTenths
        return ((maxGradeTenths.toLong() * reference + totalMs / 2) / totalMs).toInt().coerceIn(0, maxGradeTenths)
    }
}

@Serializable
data class SessionConfig(
    val id: String = newId(),
    val title: String = "Évaluation demi-fond",
    val schoolClass: String,
    val level: String,
    val distanceMeters: Int = 2000,
    val date: String = LocalDate.now().toString(),
    val rubric: DemoRubric = DemoRubric(),
    val pupils: List<Pupil>,
    val lapCount: Int? = null,
    val passageEveryMeters: Int = 400,
    val assessment: AssessmentRubric? = null,
) {
    // For equal laps, these integer metre markers are approximate. Display the lap number,
    // and compare all laps as equal lengths (1000 / 6 is not an integer number of metres).
    val distances: List<Int> get() = lapCount?.let { count -> (1..count).map { distanceMeters * it / count } }
        ?: ((passageEveryMeters until distanceMeters step passageEveryMeters).toList() + distanceMeters)
    fun passageLabel(number: Int): String = lapCount?.let { "Tour $number/$it" } ?: "${distances[number - 1]} m"
    val courseLabel: String get() = lapCount?.let { "$distanceMeters m · $it tours identiques" }
        ?: "$distanceMeters m · passages tous les $passageEveryMeters m"
    fun result(cumulativeMs: List<Long>): RaceResult = RaceCalculator.calculate(cumulativeMs, distances, lapCount != null, passageEveryMeters)
    val rubricName: String get() = assessment?.name ?: rubric.name
    val maxGradeTenths: Int get() = assessment?.maxTenths ?: rubric.maxGradeTenths
    fun validate() {
        require(validId(id) && title.isNotBlank() && title.length <= 100 && schoolClass.isNotBlank() && schoolClass.length <= 100)
        require(level in setOf("6e", "5e", "4e", "3e") && distanceMeters in 400..4000 && distanceMeters % 100 == 0)
        require(lapCount == null || lapCount in 1..20) { "Choisissez de 1 à 20 tours." }
        require(passageEveryMeters in 100..4000)
        require(lapCount != null || passageEveryMeters <= distanceMeters) { "La distance entre passages ne peut pas dépasser l’épreuve." }
        require(distances.size in 1..20) { "Le parcours doit comporter de 1 à 20 passages." }
        LocalDate.parse(date)
        rubric.validate()
        assessment?.let {
            it.validate()
            if (it.schemaVersion == 2) require(lapCount != null && it.comparisonMaxTenths == (lapCount - 1) * 10)
            val comparable = lapCount ?: (distanceMeters / passageEveryMeters)
            require(it.comparisonMaxTenths == 0 || comparable >= 2) { "Prévoyez au moins deux intervalles comparables." }
        }
        require(pupils.isNotEmpty() && pupils.size <= 30 && pupils.map { it.id }.distinct().size == pupils.size) { "Liste d’élèves invalide (30 maximum)." }
        pupils.forEach(Pupil::validate)
    }
}

@Serializable
data class RecordedCorrection(val number: Int, val originalMs: Long, val correctedMs: Long, val at: String)

@Serializable
data class CancelledPassage(val number: Int, val cumulativeMs: Long, val cancelledAt: String)

@Serializable
data class RunnerRecord(
    val id: String = newId(),
    val pupil: Pupil,
    val rawCumulativeMs: List<Long> = emptyList(),
    val corrections: List<RecordedCorrection> = emptyList(),
    val cancelledPassages: List<CancelledPassage> = emptyList(),
    val abandoned: Boolean = false,
    val pdfRevision: Int = 0,
    val syncedRevision: Int = 0,
) {
    val revision: Int get() = 1 + corrections.size + cancelledPassages.size
    fun finished(session: SessionConfig): Boolean = rawCumulativeMs.size == session.distances.size
    fun closed(session: SessionConfig): Boolean = abandoned || finished(session)
    fun cumulativeMs(session: SessionConfig): List<Long> {
        val rawLaps = RaceCalculator.calculateLaps(rawCumulativeMs, session.distances, session.lapCount != null)
        return rawLaps.runningFold(0L) { sum, lap ->
            Math.addExact(sum, corrections.find { it.number == lap.number }?.correctedMs ?: lap.durationMs)
        }.drop(1)
    }
    fun laps(session: SessionConfig): List<LapResult> = RaceCalculator.calculateLaps(cumulativeMs(session), session.distances, session.lapCount != null)
    fun assessmentScore(session: SessionConfig): AssessmentScore? = if (!abandoned && finished(session))
        session.assessment?.score(laps(session), pupil.sex, session.lapCount != null, session.passageEveryMeters) else null
    fun grade(session: SessionConfig): Int? = if (!abandoned && finished(session))
        assessmentScore(session)?.totalTenths ?: session.rubric.gradeTenths(cumulativeMs(session).last(), session.distanceMeters, session.level, pupil.sex) else null
    fun validate(session: SessionConfig) {
        require(validId(id) && session.pupils.any { it == pupil }) { "Élève absent de cette séance." }
        val raw = RaceCalculator.calculateLaps(rawCumulativeMs, session.distances, session.lapCount != null)
        require(raw.all { it.durationMs <= 86_400_000L }) { "Durée de segment invalide." }
        require(!(abandoned && finished(session)))
        require(corrections.isEmpty() || finished(session))
        cancelledPassages.forEach {
            require(it.number in 1..session.distances.size && it.cumulativeMs > 0)
            LocalDateTime.parse(it.cancelledAt)
        }
        require(corrections.map { it.number }.distinct().size == corrections.size)
        corrections.forEach {
            require(it.number in 1..raw.size && it.originalMs == raw[it.number - 1].durationMs && it.correctedMs in 1..86_400_000L)
            LocalDateTime.parse(it.at)
        }
    }
    fun cancelLastPassage(): RunnerRecord {
        require(rawCumulativeMs.isNotEmpty() && !abandoned && corrections.isEmpty() && syncedRevision == 0) {
            "Ce passage ne peut plus être annulé."
        }
        return copy(rawCumulativeMs = rawCumulativeMs.dropLast(1), cancelledPassages = cancelledPassages +
            CancelledPassage(rawCumulativeMs.size, rawCumulativeMs.last(), LocalDateTime.now().toString()))
    }
}

@Serializable
data class RaceGroup(
    val id: String = newId(),
    val session: SessionConfig,
    val runners: List<RunnerRecord>,
    val preparedAt: String = LocalDateTime.now().toString(),
    val claimed: Boolean = false,
    val startedAt: String? = null,
    val startElapsedMs: Long? = null,
    val bootCount: Int? = null,
    val sourceServerId: String? = null,
    val sourceUrl: String? = null,
) {
    val complete: Boolean get() = runners.all { it.closed(session) }
    fun record(runnerId: String, elapsed: Long): RaceGroup {
        require(claimed && startElapsedMs != null) { "Démarrez d’abord le chrono." }
        val runner = runners.first { it.id == runnerId }
        require(!runner.closed(session)) { "Cet élève a terminé." }
        require(elapsed > (runner.rawCumulativeMs.lastOrNull() ?: 0)) { "Deux passages ne peuvent pas avoir le même instant." }
        val updated = runner.copy(rawCumulativeMs = runner.rawCumulativeMs + elapsed)
        updated.validate(session)
        return copy(runners = runners.map { if (it.id == runnerId) updated else it })
    }
    fun recordBatch(runnerIds: Set<String>, elapsed: Long): RaceGroup {
        require(runnerIds.isNotEmpty() && runnerIds.size <= MAX_GROUP_SIZE)
        return runnerIds.fold(this) { group, id -> group.record(id, elapsed) }
    }
    fun report(runner: RunnerRecord): RaceReport {
        require(runner.finished(session))
        return RaceReport(Student(runner.pupil.lastName, runner.pupil.firstName, session.schoolClass),
            LocalDateTime.parse(startedAt ?: preparedAt).toLocalDate(),
            session.result(runner.cumulativeMs(session)),
            LocalDateTime.parse(preparedAt),
            runner.corrections.map { LapCorrection(it.number, it.originalMs, it.correctedMs, LocalDateTime.parse(it.at)) },
            TimingMode.AUTOMATIC, startedAt?.let(LocalDateTime::parse), session.distanceMeters,
            "${session.level} · ${if (runner.pupil.sex == Sex.BOY) "Garçon" else "Fille"} · ${session.rubricName} · ${(session.assessment?.version ?: session.rubric.version).take(8)}", runner.grade(session),
            session.lapCount, session.passageEveryMeters, session.maxGradeTenths,
            runner.cancelledPassages.map { "Passage ${it.number} annulé : cumul ${fr.rythmo.domain.TimeFormat.duration(it.cumulativeMs)} · ${it.cancelledAt}" }, runner.assessmentScore(session), session.assessment)
    }
}

@Serializable
data class ClientArchive(
    val deviceId: String = newId(), val deviceName: String = "Tablette Rythmo",
    val role: AppRole = AppRole.CHOICE,
    val serverUrl: String = "https://127.0.0.1:8765", val pairingCode: String = "",
    val session: SessionConfig? = null, val groups: List<RaceGroup> = emptyList(), val activeGroupId: String? = null,
    val trustedServers: Map<String, String> = emptyMap(),
    val transport: String = "", val serverId: String? = null,
    val teacherAccess: TeacherAccess? = null, val teacherAttempts: TeacherAttempts = TeacherAttempts(),
) {
    val activeGroup: RaceGroup? get() = groups.find { it.id == activeGroupId }
    fun replace(group: RaceGroup): ClientArchive = copy(groups = groups.filterNot { it.id == group.id } + group)
}

@Serializable
data class SchoolClass(val id: String, val name: String, val level: String, val pupils: List<Pupil>)

fun demoClasses(): List<SchoolClass> {
    val boys = listOf("Lucas", "Gabriel", "Adam", "Louis", "Arthur", "Jules", "Raphaël", "Hugo", "Noé", "Léo", "Sacha", "Nathan", "Maël", "Eliott", "Yanis")
    val girls = listOf("Emma", "Louise", "Jade", "Alice", "Chloé", "Lina", "Rose", "Léa", "Inès", "Anna", "Mila", "Nina", "Ambre", "Zoé", "Manon")
    val names = listOf("Martin", "Bernard", "Thomas", "Petit", "Robert", "Richard", "Durand", "Dubois", "Moreau", "Laurent", "Simon", "Michel", "Lefebvre", "Leroy", "Roux", "David", "Bertrand", "Morel", "Fournier", "Girard", "Bonnet", "Dupont", "Lambert", "Fontaine", "Rousseau", "Vincent", "Muller", "Lefèvre", "Faure", "André")
    return listOf("6e" to "Mistral", "6e" to "Tramontane", "5e" to "Azur", "5e" to "Indigo",
        "4e" to "Quartz", "4e" to "Améthyste", "3e" to "Ouessant", "3e" to "Belle-Île").mapIndexed { index, (level, name) ->
        SchoolClass("demo-class-$index", "$level $name", level, (0 until 30).map { pupil ->
            val boy = pupil % 2 == 0
            Pupil("demo-$index-$pupil", names[(pupil + index * 3) % names.size],
                (if (boy) boys else girls)[(pupil / 2 + index) % 15], if (boy) Sex.BOY else Sex.GIRL)
        })
    }
}

fun demoSession(): SessionConfig = demoClasses().first().let { SessionConfig(schoolClass = it.name, level = it.level, pupils = it.pupils) }
