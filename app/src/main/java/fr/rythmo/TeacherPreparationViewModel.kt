package fr.rythmo

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.rythmo.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Base64

class TeacherPreparationViewModel(application: Application) : AndroidViewModel(application) {
    private lateinit var repository: AndroidTeacherRepository
    private val mutex = Mutex()
    var data by mutableStateOf(TeacherPreparation()); private set
    var classes by mutableStateOf<List<SchoolClass>>(emptyList()); private set
    var ready by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var workbook by mutableStateOf<ImportWorkbook?>(null); private set
    var importKind by mutableStateOf("class"); private set
    var importFilename by mutableStateOf(""); private set
    var preview by mutableStateOf<SessionConfig?>(null); private set
    var sessionCode by mutableStateOf<String?>(null); private set
    init { launch {
        repository = withContext(Dispatchers.IO) { AndroidTeacherRepository.get(application) }
        data = repository.preparation; classes = repository.store.state.classes; ready = true
    } }
    private fun launch(block: suspend () -> Unit) { viewModelScope.launch { mutex.withLock {
        busy = true; error = null
        try { block() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "L’opération a échoué. Les données précédentes sont conservées." }
        finally { busy = false }
    } } }
    private suspend fun save(next: TeacherPreparation) {
        withContext(Dispatchers.IO) { repository.save(next) }; data = next
    }
    fun edit(draft: TeacherDraft, settings: TeacherSettingsViewModel) {
        settings.requireUnlocked(); check(ready)
        data = data.copy(draft = draft); preview = null
        val next = data
        launch { withContext(Dispatchers.IO) { repository.save(next) } }
    }
    fun beginImport(kind: String, settings: TeacherSettingsViewModel) { settings.requireUnlocked(); importKind = kind }
    // Picking a document backgrounds and locks the app. Stage its contents only; saving still requires unlocking.
    fun readDocument(uri: Uri) = launch {
        val resolver = getApplication<Application>().contentResolver
        val parsed = withContext(Dispatchers.IO) {
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: error("Nom de fichier inaccessible.")
            val bytes = resolver.openInputStream(uri)?.use { val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) { val count = it.read(buffer); if (count < 0) break
                    require(out.size() + count <= 2_000_000) { "Choisissez un fichier de moins de 2 Mo." }; out.write(buffer, 0, count) }
                out.toByteArray() } ?: error("Fichier inaccessible.")
            require(bytes.size <= 2_000_000) { "Choisissez un fichier de moins de 2 Mo." }
            name to TabularImport.read(ImportFile(name, Base64.getEncoder().encodeToString(bytes)))
        }
        importFilename = parsed.first; workbook = parsed.second
    }
    fun closeImport() { workbook = null }
    fun importClass(cls: SchoolClass, settings: TeacherSettingsViewModel) = launch {
        settings.requireUnlocked()
        val imported = withContext(Dispatchers.IO) { repository.store.importClass(cls) }
        classes = repository.store.state.classes
        save(data.copy(draft = data.draft.copy(classId = imported.id)))
        workbook = null; notice = "Classe enregistrée · ${imported.pupils.size} élèves"
    }
    fun importTable(table: PerformanceTable, settings: TeacherSettingsViewModel) = launch {
        settings.requireUnlocked(); table.validate()
        save(data.copy(tables = data.tables + table, draft = data.draft.copy(tableId = table.id)))
        workbook = null; notice = "Barème enregistré"
    }
    fun review(settings: TeacherSettingsViewModel) = launch {
        settings.requireUnlocked(); preview = data.draft.session(classes, data.tables)
    }
    fun cancelReview() { preview = null }
    fun publish(settings: TeacherSettingsViewModel, blocked: () -> Boolean, startSharing: () -> Unit) = launch {
        settings.requireUnlocked(); check(!blocked()) { "Terminez la course locale avant de publier." }
        val session = requireNotNull(preview)
        withContext(Dispatchers.IO) { repository.store.publish(session) }
        sessionCode = repository.store.state.teacherCode; preview = null; notice = "Séance publiée"
        // Publication is durable even if the app was backgrounded while saving.
        if (settings.unlocked && !blocked()) startSharing()
    }
    fun showSessionCode(settings: TeacherSettingsViewModel) { settings.requireUnlocked(); sessionCode = repository.store.state.teacherCode }
    fun hideSessionCode() { sessionCode = null }
}
