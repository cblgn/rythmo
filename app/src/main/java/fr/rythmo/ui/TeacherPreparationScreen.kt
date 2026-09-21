package fr.rythmo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.rythmo.*
import fr.rythmo.session.*

@Composable
internal fun Choice(label: String, selected: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(selected.ifBlank { "Choisir…" }) }
            DropdownMenu(expanded, { expanded = false }) {
                options.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; onSelect(id) }) }
            }
        }
    }
}

@Composable
internal fun TeacherPreparationForm(settings: TeacherSettingsViewModel, blocked: Boolean, onPickDocument: () -> Unit, startSharing: () -> Unit,
    preparation: TeacherPreparationViewModel = viewModel()) {
    val draft = preparation.data.draft
    fun edit(next: TeacherDraft) = preparation.edit(next, settings)
    if (!preparation.ready) { Text(preparation.error ?: "Chargement…"); return }
    Text("Préparer ma séance", style = MaterialTheme.typography.headlineSmall)
    OutlinedTextField(draft.teacherName, { edit(draft.copy(teacherName = it.take(40))) }, label = { Text("Nom affiché aux élèves") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(draft.title, { edit(draft.copy(title = it.take(100))) }, label = { Text("Titre de la séance") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    HorizontalDivider()
    Choice("Classe", preparation.classes.find { it.id == draft.classId }?.name.orEmpty(), preparation.classes.map { it.id to it.name }) { edit(draft.copy(classId = it)) }
    OutlinedButton(onClick = { preparation.beginImport("class", settings); onPickDocument() }) { ActionLabel(fr.rythmo.R.drawable.ic_groups, "Importer une classe") }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(draft.distance, { edit(draft.copy(distance = it.take(4))) }, label = { Text("Distance (m)") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(draft.laps, { edit(draft.copy(laps = it.take(2))) }, label = { Text("Nombre de tours") }, singleLine = true, modifier = Modifier.weight(1f))
    }
    HorizontalDivider()
    Choice("Barème de performance", preparation.data.tables.find { it.id == draft.tableId }?.let { "${it.name} · ${it.distanceMeters} m" }.orEmpty(),
        preparation.data.tables.map { it.id to "${it.name} · ${it.distanceMeters} m" }) { edit(draft.copy(tableId = it)) }
    OutlinedButton(onClick = { preparation.beginImport("performance", settings); onPickDocument() }) { ActionLabel(fr.rythmo.R.drawable.ic_download, "Importer un barème") }
    OutlinedTextField(draft.total, { edit(draft.copy(total = it.take(5))) }, label = { Text("Note totale sur") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    val count = draft.laps.toIntOrNull()
    val total = runCatching { ImportValues.points(draft.total) }.getOrNull()
    if (count != null && count in 1..20 && total != null) {
        val regularity = (count - 1) * 10
        if (total > regularity) Text("Régularité /${count - 1} · Performance /${formatPoints(total - regularity)} · Total /${formatPoints(total)}", style = MaterialTheme.typography.titleSmall)
    }
    Text("Même temps ou plus rapide : 1 point. Comparaison après arrondi à la seconde.", style = MaterialTheme.typography.bodySmall)
    preparation.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    preparation.notice?.let { Text(it) }
    if (blocked) Text("Publication disponible après la course locale.")
    Button(onClick = { preparation.review(settings) }, enabled = !blocked && !preparation.busy, modifier = Modifier.fillMaxWidth()) { Text("Vérifier ma séance") }
    if (TeacherService.status.running) {
        NearbyButton("Partager la séance publiée", !blocked && !preparation.busy, startSharing)
        TextButton(onClick = { preparation.showSessionCode(settings) }) { Text("Voir le code des bilans") }
    }
    preparation.preview?.let { session ->
        AlertDialog(onDismissRequest = preparation::cancelReview, title = { Text("Publier cette séance ?") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${draft.teacherName} · ${session.schoolClass} · ${session.title}")
                Text("${session.distanceMeters} m · ${session.lapCount} tours · ${session.pupils.size} élèves")
                Text("Régularité /${session.lapCount!! - 1} · Performance /${formatPoints(session.assessment!!.performanceMaxTenths)}")
                Text("${session.rubricName} · Total /${formatPoints(session.maxGradeTenths)}")
                preparation.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { NearbyButton("Rendre la séance disponible", !preparation.busy && !blocked) {
            preparation.publish(settings, { blocked }, startSharing)
        } }, dismissButton = { TextButton(onClick = preparation::cancelReview) { Text("Modifier") } })
    }
    preparation.sessionCode?.let { code ->
        AlertDialog(onDismissRequest = preparation::hideSessionCode, title = { Text("Code des bilans") }, text = {
            Column { Text(code, style = MaterialTheme.typography.headlineLarge); Text("À saisir pour envoyer les bilans, abandonner ou clôturer une série.") }
        }, confirmButton = { TextButton(onClick = preparation::hideSessionCode) { Text("Fermer") } })
    }
    preparation.workbook?.let { workbook ->
        ImportDialog(workbook, preparation.importKind, preparation.importFilename, preparation.busy, preparation.error,
            preparation::closeImport, { preparation.importClass(it, settings) }, { preparation.importTable(it, settings) })
    }
}

@Composable
private fun ImportDialog(book: ImportWorkbook, kind: String, filename: String, busy: Boolean, saveError: String?,
    close: () -> Unit, saveClass: (SchoolClass) -> Unit, saveTable: (PerformanceTable) -> Unit) {
    var name by rememberSaveable(book) { mutableStateOf("") }
    var level by rememberSaveable(book) { mutableStateOf("3e") }
    var distance by rememberSaveable(book) { mutableStateOf("1000") }
    var maximum by rememberSaveable(book) { mutableStateOf("7") }
    var classSheet by rememberSaveable(book) { mutableIntStateOf(0) }
    var firstRow by rememberSaveable(book, classSheet) { mutableStateOf("2") }
    val header = book.sheets[classSheet].rows.firstOrNull().orEmpty().map(ImportValues::normalized)
    var last by rememberSaveable(book, classSheet) { mutableIntStateOf(header.indexOf("nom").coerceAtLeast(0)) }
    var first by rememberSaveable(book, classSheet) { mutableIntStateOf(header.indexOf("prenom").takeIf { it >= 0 } ?: 1) }
    var sex by rememberSaveable(book, classSheet) { mutableIntStateOf(header.indexOf("sexe").takeIf { it >= 0 } ?: 2) }
    val girlDefault = book.sheets.indexOfFirst { ImportValues.normalized(it.name).contains("fille") }.coerceAtLeast(0)
    val boyDefault = book.sheets.indexOfFirst { ImportValues.normalized(it.name).contains("garcon") }.takeIf { it >= 0 } ?: (book.sheets.size - 1)
    var girl by rememberSaveable(book) { mutableIntStateOf(girlDefault) }
    var boy by rememberSaveable(book) { mutableIntStateOf(boyDefault) }
    var girlStart by rememberSaveable(book) { mutableStateOf("1") }
    var boyStart by rememberSaveable(book) { mutableStateOf("1") }
    var timeColumn by rememberSaveable(book) { mutableIntStateOf(0) }
    var pointsColumn by rememberSaveable(book) { mutableIntStateOf(1) }
    var unit by rememberSaveable(book) { mutableStateOf(if (filename.endsWith(".xlsx", true)) ImportTimeUnit.EXCEL else ImportTimeUnit.CLOCK) }
    var correctionProfile by rememberSaveable(book) { mutableStateOf("F") }
    var correctionRow by rememberSaveable(book) { mutableStateOf("1") }
    var correctionTime by rememberSaveable(book) { mutableStateOf("") }
    var corrections by remember(book) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var mapping by rememberSaveable(book) { mutableStateOf(if (kind == "class") !header.containsAll(listOf("nom", "prenom", "sexe")) else book.sheets.size < 2) }
    var localError by remember(book) { mutableStateOf<String?>(null) }
    var pupils by remember(book) { mutableStateOf<List<Pupil>?>(null) }
    var table by remember(book) { mutableStateOf<PerformanceTable?>(null) }
    fun invalidate() { pupils = null; table = null; localError = null }
    val sheets = book.sheets.mapIndexed { i, sheet -> i.toString() to sheet.name }
    val cols = (0 until book.sheets.maxOf { sheet -> sheet.rows.maxOfOrNull { it.size } ?: 0 }).map { it.toString() to "Colonne ${it + 1}" }
    AlertDialog(onDismissRequest = close, title = { Text(if (kind == "class") "Importer une classe" else "Importer un barème") }, text = {
        Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(filename)
            val formulas = book.sheets.sumOf { it.ignoredFormulas }
            if (formulas > 0) Text("$formulas cellules de formule ignorées ; seules les valeurs sont importées.")
            OutlinedTextField(name, { name = it; invalidate() }, label = { Text(if (kind == "class") "Nom de la classe" else "Nom du barème") }, singleLine = true)
            if (kind == "class") {
                Choice("Niveau", level, listOf("6e", "5e", "4e", "3e").map { it to it }) { level = it; invalidate() }
                TextButton(onClick = { mapping = !mapping }) { Text(if (mapping) "Masquer les colonnes" else "Choisir la feuille ou les colonnes") }
                if (mapping) {
                if (sheets.size > 1) Choice("Feuille", book.sheets[classSheet].name, sheets) { classSheet = it.toInt(); invalidate() }
                Choice("NOM", "Colonne ${last + 1}", cols) { last = it.toInt(); invalidate() }
                Choice("Prénom", "Colonne ${first + 1}", cols) { first = it.toInt(); invalidate() }
                Choice("Sexe", "Colonne ${sex + 1}", cols) { sex = it.toInt(); invalidate() }
                OutlinedTextField(firstRow, { firstRow = it; invalidate() }, label = { Text("Première ligne d’élèves") }, singleLine = true)
                }
            } else {
                OutlinedTextField(distance, { distance = it; invalidate() }, label = { Text("Distance du barème (m)") }, singleLine = true)
                OutlinedTextField(maximum, { maximum = it; invalidate() }, label = { Text("Performance source sur") }, singleLine = true)
                Text("Filles : ${book.sheets[girl].name} · Garçons : ${book.sheets[boy].name}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { mapping = !mapping }) { Text(if (mapping) "Masquer les options d’import" else "Choisir les feuilles et colonnes") }
                if (mapping) {
                Choice("Table filles", book.sheets[girl].name, sheets) { girl = it.toInt(); invalidate() }
                OutlinedTextField(girlStart, { girlStart = it; invalidate() }, label = { Text("Première ligne filles") }, singleLine = true)
                Choice("Table garçons", book.sheets[boy].name, sheets) { boy = it.toInt(); invalidate() }
                OutlinedTextField(boyStart, { boyStart = it; invalidate() }, label = { Text("Première ligne garçons") }, singleLine = true)
                Choice("Colonne temps", "Colonne ${timeColumn + 1}", cols) { timeColumn = it.toInt(); invalidate() }
                Choice("Colonne points", "Colonne ${pointsColumn + 1}", cols) { pointsColumn = it.toInt(); invalidate() }
                Choice("Format des temps", when (unit) { ImportTimeUnit.EXCEL -> "Durée Excel"; ImportTimeUnit.CLOCK -> "Minutes:secondes"; else -> "Secondes" },
                    listOf("EXCEL" to "Durée Excel", "CLOCK" to "Minutes:secondes", "SECONDS" to "Secondes")) { unit = ImportTimeUnit.valueOf(it); invalidate() }
                }
                Text("Aperçu des premières valeurs", style = MaterialTheme.typography.titleSmall)
                listOf(girl, boy).distinct().forEach { index ->
                    Text(book.sheets[index].name)
                    book.sheets[index].rows.take(3).forEach { Text(it.joinToString(" · ").take(100), style = MaterialTheme.typography.bodySmall) }
                }
                var correcting by rememberSaveable { mutableStateOf(false) }
                TextButton(onClick = { correcting = !correcting }) { Text("Corriger un temps à l’import") }
                if (correcting) {
                    Choice("Profil à corriger", if (correctionProfile == "F") "Filles" else "Garçons", listOf("F" to "Filles", "G" to "Garçons")) { correctionProfile = it }
                    OutlinedTextField(correctionRow, { correctionRow = it }, label = { Text("Ligne à corriger") }, singleLine = true)
                    OutlinedTextField(correctionTime, { correctionTime = it }, label = { Text("Temps corrigé (min:sec)") }, singleLine = true)
                    TextButton(onClick = {
                        try { require(correctionRow.toInt() > 0); ImportValues.time(correctionTime, ImportTimeUnit.CLOCK)
                            corrections = corrections + ("$correctionProfile:${correctionRow.toInt() - 1}" to correctionTime); invalidate()
                        } catch (_: Exception) { localError = "Indiquez une ligne et un temps min:sec valides." }
                    }) { Text("Appliquer à l’aperçu") }
                }
                corrections.forEach { (key, value) -> Text("$key → $value", style = MaterialTheme.typography.bodySmall) }
            }
            OutlinedButton(onClick = {
                try {
                    localError = null
                    if (kind == "class") {
                        val parsed = ImportValues.pupils(book.sheets[classSheet], firstRow.toInt() - 1, last, first, sex)
                        val cls = ClassImport.reconcile(SchoolClass(newId(), name.trim(), level, parsed), null)
                        pupils = cls.pupils
                    } else {
                        fun rows(index: Int, start: String, profile: String) = ImportValues.performance(book.sheets[index], start.toInt() - 1, timeColumn, pointsColumn, unit,
                            corrections.filterKeys { it.startsWith("$profile:") }.mapKeys { it.key.substringAfter(':').toInt() })
                        table = PerformanceTable(name = name.trim(), distanceMeters = distance.toInt(), sourceMaxTenths = ImportValues.points(maximum),
                            tables = mapOf(Sex.GIRL to rows(girl, girlStart, "F"), Sex.BOY to rows(boy, boyStart, "G"))).also { it.validate() }
                    }
                } catch (e: Exception) { pupils = null; table = null; localError = e.message ?: "Vérifiez les valeurs." }
            }) { Text("Vérifier l’import") }
            pupils?.let { list -> Text("${list.size} élèves"); list.forEach { Text("${it.lastName} ${it.firstName} · ${if (it.sex == Sex.GIRL) "F" else "G"}") } }
            table?.let { t -> Text("${t.name} · ${t.distanceMeters} m · /${formatPoints(t.sourceMaxTenths)}")
                t.tables.forEach { (profile, rows) -> Text("${if (profile == Sex.GIRL) "Filles" else "Garçons"} : ${rows.size} seuils, de ${fr.rythmo.domain.TimeFormat.duration(rows.first().timeMs)} à ${fr.rythmo.domain.TimeFormat.duration(rows.last().timeMs)}") }
            }
            (localError ?: saveError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy && (pupils != null || table != null), onClick = {
        if (kind == "class") saveClass(SchoolClass(newId(), name.trim(), level, requireNotNull(pupils))) else saveTable(requireNotNull(table))
    }) { Text("Enregistrer") } }, dismissButton = { TextButton(onClick = close) { Text("Annuler") } })
}
