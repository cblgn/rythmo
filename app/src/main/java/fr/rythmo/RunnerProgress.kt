package fr.rythmo

fun runnerLabels(runners: List<fr.rythmo.session.RunnerRecord>): Map<String, String> {
    fun normalized(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").lowercase(java.util.Locale.ROOT)
    val short = runners.associate { it.id to "${it.pupil.firstName} ${it.pupil.lastName.first().uppercaseChar()}." }
    return runners.mapIndexed { index, runner ->
        val collision = runners.count { normalized(short.getValue(it.id)) == normalized(short.getValue(runner.id)) } > 1
        val full = runner.pupil.label
        val duplicate = runners.count { normalized(it.pupil.label) == normalized(full) } > 1
        runner.id to if (collision) full + if (duplicate) " · ${index + 1}" else "" else short.getValue(runner.id)
    }.toMap()
}

fun stopwatchTenths(milliseconds: Long): String {
    val tenths = milliseconds.coerceAtLeast(0) / 100
    return "${tenths / 600}:${(tenths / 10 % 60).toString().padStart(2, '0')},${tenths % 10}"
}
