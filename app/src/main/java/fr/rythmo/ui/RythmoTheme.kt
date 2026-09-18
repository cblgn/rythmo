package fr.rythmo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import fr.rythmo.domain.PaceChange

private val RythmoColors = lightColorScheme(
    primary = Color(0xFFBC4D29),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF5E3D5),
    onPrimaryContainer = Color(0xFF5E2412),
    secondary = Color(0xFF765A49),
    background = Color(0xFFFFFCF5),
    surface = Color(0xFFFFFCF5),
    surfaceContainer = Color(0xFFF0EFE9),
    surfaceContainerLow = Color.White,
    onSurface = Color(0xFF332B25),
    onSurfaceVariant = Color(0xFF6E5F54),
    outline = Color(0xFF827063),
)

object PaceColors {
    val equivalent = Color(0xFF185ABC)
    val faster = Color(0xFF146C3A)
    val slower = Color(0xFFB3261E)
}

@Composable
fun paceColor(pace: PaceChange?): Color = when (pace) {
    PaceChange.FASTER -> PaceColors.faster
    PaceChange.SLOWER -> PaceColors.slower
    PaceChange.EQUIVALENT -> PaceColors.equivalent
    null -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun RythmoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RythmoColors,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}
