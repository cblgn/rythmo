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
    primary = Color(0xFF185ABC),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EDFF),
    onPrimaryContainer = Color(0xFF10356D),
    secondary = Color(0xFF53627B),
    background = Color(0xFFF8F9FD),
    surface = Color(0xFFF8F9FD),
    surfaceContainer = Color(0xFFEEF1F8),
    surfaceContainerLow = Color.White,
    onSurface = Color(0xFF172033),
    onSurfaceVariant = Color(0xFF505D72),
    outline = Color(0xFF737F92),
)

object PaceColors {
    val faster = Color(0xFF146C3A)
    val slower = Color(0xFFB3261E)
}

@Composable
fun paceColor(pace: PaceChange?): Color = when (pace) {
    PaceChange.FASTER -> PaceColors.faster
    PaceChange.SLOWER -> PaceColors.slower
    PaceChange.EQUIVALENT -> MaterialTheme.colorScheme.primary
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
