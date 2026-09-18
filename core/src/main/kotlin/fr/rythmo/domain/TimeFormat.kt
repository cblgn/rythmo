package fr.rythmo.domain

import java.util.Locale
import kotlin.math.abs

object TimeFormat {
    // Digits represent mmss; a colon is also accepted for pasted times.
    fun parseInput(input: String): Long? {
        val text = input.trim()
        if (!text.matches(Regex("[0-9]{1,6}|[0-9]{1,4}:[0-9]{2}"))) return null
        val digits = text.replace(":", "")
        val seconds = digits.takeLast(2).toLong()
        if (seconds >= 60) return null
        val minutes = digits.dropLast(2).ifEmpty { "0" }.toLong()
        return (minutes * 60 + seconds) * 1_000
    }

    fun inputDigits(durationMs: Long): String {
        val seconds = durationMs / 1_000
        return "%d%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
    }

    fun duration(durationMs: Long): String {
        require(durationMs >= 0)
        val seconds = durationMs / 1_000
        val base = "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
        val fraction = durationMs % 1_000
        return if (fraction == 0L) base else "$base," +
            "%03d".format(Locale.ROOT, fraction).trimEnd('0')
    }

    fun difference(differenceMs: Long): String {
        val magnitude = abs(differenceMs)
        val fraction = magnitude % 1_000
        val seconds = (magnitude / 1_000).toString() + if (fraction == 0L) "" else
            "," + "%03d".format(Locale.ROOT, fraction).trimEnd('0')
        val sign = when {
            differenceMs < 0 -> "−"
            differenceMs > 0 -> "+"
            else -> ""
        }
        return "$sign$seconds s"
    }
}
