package fr.rythmo.domain

import java.time.LocalDate
import java.time.LocalDateTime

data class Student(val lastName: String, val firstName: String, val schoolClass: String) {
    val isComplete: Boolean get() = listOf(lastName, firstName, schoolClass).all { it.isNotBlank() }
}

data class RaceReport(
    val student: Student,
    val date: LocalDate,
    val result: RaceResult,
    val startedAt: LocalDateTime? = null,
    val corrections: List<LapCorrection> = emptyList(),
    val timingMode: TimingMode = TimingMode.MANUAL,
    val chronoStartedAt: LocalDateTime? = null,
    val distanceMeters: Int = 2000,
    val assessmentDetails: String? = null,
    val gradeTenths: Int? = null,
    val equalLapCount: Int? = null,
    val referenceLapMeters: Int = 400,
    val maxGradeTenths: Int = 200,
    val cancelledPassages: List<String> = emptyList(),
)

enum class TimingMode { MANUAL, AUTOMATIC }

data class LapCorrection(
    val lapNumber: Int,
    val originalMs: Long,
    val correctedMs: Long,
    val correctedAt: LocalDateTime,
)
