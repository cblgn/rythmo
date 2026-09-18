package fr.rythmo.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.rythmo.R

@Composable
fun WelcomeScreen(onTiming: () -> Unit, onTeacher: () -> Unit, onIndividual: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("EPS · DEMI-FOND", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(50)) {
                Text("Hors ligne", Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(32.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Image(painterResource(R.drawable.ic_rythmo), contentDescription = "Logo Rythmo : une piste et son coureur", modifier = Modifier.size(72.dp))
                    Column {
                        Text("Rythmo", fontSize = 38.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Évaluation demi-fond", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Text("Chaque tour compte.", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("Au rythme des progrès de chaque élève.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("À vous de jouer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            EntryCard("01", "Chronométrer un groupe", "Jusqu’à 8 coureurs, un passage à la fois.", onTiming, primary = true)
            EntryCard("02", "Espace enseignant", "Préparer les classes, les épreuves et les bilans.", onTeacher)
        }
        Text("Les passages sont sauvegardés au fil de la course. Chaque arrivée produit son propre bilan.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onIndividual, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Évaluation individuelle · mode existant")
        }
    }
}

@Composable
private fun EntryCard(number: String, title: String, subtitle: String, onClick: () -> Unit, primary: Boolean = false) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp),
        colors = CardDefaults.cardColors(containerColor = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(number, style = MaterialTheme.typography.labelLarge)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            }
            Text("›", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 851)
@Composable
private fun WelcomePreview() { RythmoTheme { WelcomeScreen({}, {}, {}) } }
