package fr.rythmo.session

/** Builds a legacy archive fixture; corrections are no longer an application operation. */
fun RunnerRecord.correct(session: SessionConfig, number: Int, durationMs: Long): RunnerRecord {
    require(finished(session) && number in 1..session.distances.size)
    require(corrections.none { it.number == number })
    val original = laps(session)[number - 1].durationMs
    require(durationMs in 1..86_400_000L && durationMs != original)
    return copy(corrections = corrections + RecordedCorrection(number, original, durationMs, "2026-09-20T10:00:00"))
}
