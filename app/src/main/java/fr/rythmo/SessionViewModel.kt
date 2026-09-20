package fr.rythmo

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.rythmo.domain.RaceInput
import fr.rythmo.domain.SplitValidation
import fr.rythmo.session.*
import fr.rythmo.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.LocalDateTime
import java.util.Base64

enum class TeacherAction { CLOSE_GROUP, ABANDON_RUNNER, UPLOAD }
data class CapturedPassage(val groupId: String, val elapsedMs: Long, val eligibleIds: Set<String>)

data class TeacherRequest(val action: TeacherAction, val runnerId: String? = null)

class SessionViewModel internal constructor(application: Application, private val exchangeOverride: MessageTransport?) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, null)
    private val directory = File(application.filesDir, "sessions")
    private val storage = JsonFile(File(directory, "client.json"), ClientArchive.serializer()) { ClientArchive() }
    private val mutex = Mutex()
    val nearby = NearbyTransport(application)
    private var nearbyConnected = false
    var nearbyPanel by mutableStateOf(false); private set
    val useNearby: Boolean get() = archive.transport == "nearby" || (archive.transport.isEmpty() && archive.trustedServers.isEmpty())
    fun selectTransport(nearbySelected: Boolean) = action {
        require(!raceRunning) { "Terminez la course avant de changer de connexion." }
        nearby.disconnect()
        save(archive.copy(transport = if (nearbySelected) "nearby" else "https"))
    }
    fun retrieveNearby() {
        if (TeacherService.status.running) { message = "Cet appareil héberge déjà le serveur."; return }
        nearbyPanel = true
        nearby.discover(archive.deviceName)
    }
    fun dismissNearbyPanel() {
        nearbyPanel = false
        if (nearby.state.value.connected.isEmpty()) nearby.disconnect()
    }
    fun confirmNearby(id: String, accept: Boolean, host: Boolean, settings: TeacherSettingsViewModel) {
        if (host) {
            if (accept) settings.requireUnlocked()
            TeacherService.nearby?.confirm(id, accept)
        } else nearby.confirm(id, accept)
    }
    fun retrieveNearby(name: String) = action {
        require(name.isNotBlank() && name.length <= 80)
        save(archive.copy(deviceName = name.trim()))
        retrieveNearby()
    }
    override fun onCleared() { nearby.close(); super.onCleared() }
    private val passageGuard = PassageGuard()
    private val pdfSlots = Semaphore(2)
    var capturedPassage by mutableStateOf<CapturedPassage?>(null); private set
    private val generating = mutableSetOf<String>()
    private val bootCount = Settings.Global.getInt(application.contentResolver, Settings.Global.BOOT_COUNT, -1)
    var archive by mutableStateOf(ClientArchive()); private set
    var candidateServer by mutableStateOf<Pair<String, String>?>(null); private set
    var ready by mutableStateOf(false); private set
    var loadError by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null); private set
    var networkBusy by mutableStateOf(false); private set
    var discovered by mutableStateOf<List<String>>(emptyList()); private set
    var claims by mutableStateOf<List<GroupClaim>>(emptyList()); private set
    var pdfErrors by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var elapsedMs by mutableStateOf(0L); private set
    var teacherRequest by mutableStateOf<TeacherRequest?>(null); private set
    var teacherError by mutableStateOf<String?>(null); private set
    var checkingTeacher by mutableStateOf(false); private set
    val clockInterrupted: Boolean get() = archive.activeGroup?.let { it.startElapsedMs != null && it.bootCount != bootCount } == true

    init {
        viewModelScope.launch {
            nearby.state.collect { state ->
                val connected = state.connected.isNotEmpty()
                if (connected && !nearbyConnected) download()
                nearbyConnected = connected
            }
        }
        viewModelScope.launch {
            try {
                archive = withContext(Dispatchers.IO) { storage.read().let { it.copy(serverUrl = it.serverUrl.replaceFirst("http://", "https://")) }.also(storage::write) }
                ready = true
                retryReports()
            } catch (e: Exception) { loadError = "Sauvegarde illisible. Les fichiers ont été conservés : ${e.message}" }
        }
        viewModelScope.launch {
            while (isActive) {
                val group = archive.activeGroup
                val start = group?.startElapsedMs
                elapsedMs = if (start != null && group.bootCount == bootCount)
                    (SystemClock.elapsedRealtime() - start).coerceAtLeast(0) else 0
                delay(100)
            }
        }
    }

    private suspend fun save(value: ClientArchive) {
        withContext(Dispatchers.IO) { storage.write(value) }
        archive = value
    }
    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            mutex.withLock {
                try { check(ready); block() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    val detail = e.message ?: "L’opération a échoué ; les données précédentes sont conservées."
                    if (teacherRequest != null) teacherError = detail else message = detail
                }
            }
        }
    }
    fun dismissMessage() { message = null }
    val raceRunning: Boolean get() = archive.activeGroup?.let { it.startElapsedMs != null && !it.complete } == true
    fun startTeacherServer(settings: TeacherSettingsViewModel, individual: RythmoViewModel, enableNearby: Boolean = false) {
        settings.requireUnlocked()
        check(!raceRunning && !individual.raceInProgress) { "Terminez la course locale avant de démarrer le serveur." }
        nearby.disconnect()
        getApplication<Application>().startForegroundService(Intent(getApplication(), TeacherService::class.java).putExtra("nearby", enableNearby))
    }
    fun stopTeacherServer(settings: TeacherSettingsViewModel) {
        settings.requireUnlocked()
        getApplication<Application>().stopService(Intent(getApplication(), TeacherService::class.java))
    }
    fun connection(url: String, code: String, name: String) = action {
        require(name.isNotBlank() && name.length <= 100)
        save(archive.copy(serverUrl = url.trim().trimEnd('/'), pairingCode = code.trim(), deviceName = name.trim()))
    }
    fun discover() {
        viewModelScope.launch {
            networkBusy = true
            try {
                discovered = withContext(Dispatchers.IO) { discoverTeachers() }
                message = if (discovered.isEmpty()) "Aucun serveur trouvé. Vérifiez le Wi-Fi, ou utilisez l’adresse du PC / le tunnel USB." else "Sélectionnez le serveur de votre professeur."
            } catch (e: Exception) { message = "Recherche impossible : ${e.message}" }
            finally { networkBusy = false }
        }
    }
    fun inspectServer(url: String, settings: TeacherSettingsViewModel) = action {
        settings.requireUnlocked()
        networkBusy = true
        try {
            val endpoint = TeacherTls.endpoint(url).toString()
            candidateServer = endpoint to withContext(Dispatchers.IO) { TeacherTls.inspect(endpoint) }
        } finally { networkBusy = false }
    }
    fun trustServer(settings: TeacherSettingsViewModel) = action {
        settings.requireUnlocked()
        val (url, pin) = requireNotNull(candidateServer)
        save(archive.copy(serverUrl = url, trustedServers = archive.trustedServers + (url to pin)))
        candidateServer = null
        message = "Serveur associé."
    }
    fun dismissServerCandidate() { candidateServer = null }
    private fun client() = SessionSyncClient(exchangeOverride ?: if (useNearby) nearby else HttpsMessageTransport(SyncClient(archive.serverUrl, archive.pairingCode,
        requireNotNull(archive.trustedServers[archive.serverUrl]) { "Associez ce serveur dans l’accès professeur." })))
    private fun requireGroupServer(group: RaceGroup) {
        require(group.sourceServerId == null || group.sourceServerId == archive.serverId) { "Reconnectez le serveur d’origine de cette série." }
        require(group.sourceServerId != null || group.sourceUrl == null || group.sourceUrl == archive.serverUrl) { "Reconnectez le serveur d’origine de cette série." }
    }
    fun download() = action {
        require(!raceRunning) { "La course continue hors connexion. Synchronisez après l’arrivée." }
        networkBusy = true
        try {
            val downloaded = withContext(Dispatchers.IO) { client().download(archive.deviceId, archive.deviceName) }
            save(archive.withSnapshot(downloaded))
            claims = downloaded.claims
            nearbyPanel = false
            message = "Séance reçue : ${downloaded.session.schoolClass}, ${downloaded.session.distanceMeters} m."
        } finally { networkBusy = false }
    }
    fun prepare(pupilIds: Set<String>) = action {
        val session = requireNotNull(archive.session) { "Synchronisez d’abord la séance." }
        require(archive.activeGroup == null || archive.activeGroup!!.complete) { "Une série est déjà préparée." }
        require(pupilIds.size in 1..MAX_GROUP_SIZE)
        val pupils = session.pupils.filter { it.id in pupilIds }
        require(pupils.size == pupilIds.size)
        val group = RaceGroup(session = session, runners = pupils.map { RunnerRecord(pupil = it) }, sourceServerId = archive.serverId, sourceUrl = if (useNearby) null else archive.serverUrl)
        // Persist the group ID before the network request so retries reuse the same group assignment.
        save(archive.copy(groups = archive.groups + group, activeGroupId = group.id))
        claimGroup(group)
    }
    fun retryClaim() = action { claimGroup(requireNotNull(archive.activeGroup)) }
    private suspend fun claimGroup(group: RaceGroup) {
        requireGroupServer(group)
        networkBusy = true
        try {
            val claim = GroupClaim(group.session.id, group.id, archive.deviceId, archive.deviceName, group.runners.map { it.pupil.id })
            val confirmed = withContext(Dispatchers.IO) { client().claim(claim) }
            check(confirmed == claim) { "Confirmation de groupe incohérente." }
            save(archive.replace(group.copy(claimed = true)))
            claims = claims.filterNot { it.groupId == claim.groupId } + claim
            message = "Groupe prêt."
        } finally { networkBusy = false }
    }
    fun startRace() = action {
        val group = requireNotNull(archive.activeGroup)
        require(group.claimed && group.startElapsedMs == null && !group.complete)
        save(archive.replace(group.copy(startElapsedMs = SystemClock.elapsedRealtime(), bootCount = bootCount,
            startedAt = LocalDateTime.now().toString())))
    }
    fun record(runnerId: String) {
        val group = archive.activeGroup ?: return
        val start = group.startElapsedMs ?: return
        if (capturedPassage != null) return
        recordPassages(group.id, setOf(runnerId), SystemClock.elapsedRealtime() - start)
    }
    fun capturePassage() {
        val group = archive.activeGroup ?: return
        val start = group.startElapsedMs ?: return
        if (group.complete || clockInterrupted || capturedPassage != null) return
        capturedPassage = CapturedPassage(group.id, SystemClock.elapsedRealtime() - start,
            group.runners.filterNot { it.closed(group.session) }.map { it.id }.toSet())
    }
    fun dismissCapture() { capturedPassage = null }
    fun confirmCapture(ids: Set<String>) {
        val capture = capturedPassage ?: return
        if (ids.isEmpty() || !capture.eligibleIds.containsAll(ids)) return
        if (recordPassages(capture.groupId, ids, capture.elapsedMs)) capturedPassage = null
    }
    private fun recordPassages(groupId: String, ids: Set<String>, elapsed: Long): Boolean {
        if (!passageGuard.begin(ids, SystemClock.elapsedRealtime())) return false
        action {
            try {
                require(!clockInterrupted) { "Le téléphone a redémarré. Clôturez la série ; les passages sont conservés." }
                require(archive.activeGroupId == groupId)
                val current = archive.groups.first { it.id == groupId }
                val next = current.recordBatch(ids, elapsed)
                save(archive.replace(next))
                passageGuard.saved(ids, SystemClock.elapsedRealtime())
                next.runners.filter { it.id in ids && it.finished(next.session) }.forEach { generateReport(next.id, it.id) }
            } finally { passageGuard.finish(ids) }
        }
        return true
    }
    fun canUndo(runnerId: String): Boolean {
        val runner = archive.activeGroup?.runners?.find { it.id == runnerId } ?: return false
        return passageGuard.canUndo(runnerId, SystemClock.elapsedRealtime()) && runner.rawCumulativeMs.isNotEmpty() &&
            !runner.abandoned && runner.corrections.isEmpty() && runner.syncedRevision == 0
    }
    fun undoPassage(runnerId: String) = action {
        require(canUndo(runnerId)) { "Ce passage ne peut plus être annulé (délai de 15 secondes)." }
        val group = requireNotNull(archive.activeGroup)
        val runner = group.runners.first { it.id == runnerId }.cancelLastPassage()
        save(archive.replace(group.copy(runners = group.runners.map { if (it.id == runnerId) runner else it })))
        passageGuard.consumeUndo(runnerId)
        pdfErrors = pdfErrors - runnerId
    }
    fun requestUpload() {
        if (raceRunning) { message = "Terminez la course avant d’envoyer les bilans."; return }
        if (useNearby && nearby.state.value.connected.isEmpty()) { retrieveNearby(); return }
        teacherError = null; teacherRequest = TeacherRequest(TeacherAction.UPLOAD) }
    fun requestCloseGroup() { teacherError = null; teacherRequest = TeacherRequest(TeacherAction.CLOSE_GROUP) }
    fun requestAbandon(runnerId: String) { teacherError = null; teacherRequest = TeacherRequest(TeacherAction.ABANDON_RUNNER, runnerId) }
    fun cancelTeacherAction() { if (!checkingTeacher) { teacherRequest = null; teacherError = null } }
    fun confirmTeacherAction(code: String) = action {
        val request = teacherRequest ?: return@action
        checkingTeacher = true
        try {
            val access = requireNotNull(archive.teacherAccess) { "Synchronisez le code professeur avant cette action." }
            val now = System.currentTimeMillis()
            require(!archive.teacherAttempts.blocked(now)) { "Trop de tentatives. Patientez 30 secondes." }
            if (!withContext(Dispatchers.Default) { access.accepts(code) }) {
                save(archive.copy(teacherAttempts = archive.teacherAttempts.rejected(now)))
                error("Code professeur incorrect. Ce code est différent du code de synchronisation.")
            }
            save(archive.copy(teacherAttempts = TeacherAttempts()))
            teacherRequest = null
            teacherError = null
            when (request.action) {
                TeacherAction.ABANDON_RUNNER -> {
                    val group = requireNotNull(archive.activeGroup)
                    val runner = group.runners.first { it.id == request.runnerId }
                    require(!runner.closed(group.session)) { "Cet élève a déjà terminé." }
                    save(archive.replace(group.copy(runners = group.runners.map {
                        if (it.id == runner.id) it.copy(abandoned = true) else it
                    })))
                    message = "Abandon enregistré pour ${runner.pupil.label}. Ses passages sont conservés, sans note."
                }
                TeacherAction.CLOSE_GROUP -> { closeGroup(); uploadAuthorized(code) }
                TeacherAction.UPLOAD -> uploadAuthorized(code)
            }
        } finally { checkingTeacher = false }
    }
    private suspend fun closeGroup() {
        val group = requireNotNull(archive.activeGroup)
        save(archive.replace(group.copy(runners = group.runners.map {
            if (it.closed(group.session)) it else it.copy(abandoned = true)
        })))
        message = "Série clôturée. Les élèves sans arrivée sont non notés ; les passages sont conservés."
    }
    fun newGroup() = action {
        require(archive.activeGroup == null || archive.activeGroup!!.complete)
        save(archive.copy(activeGroupId = null))
    }
    fun selectGroup(id: String) = action {
        val current = archive.activeGroup
        require(current == null || current.complete || current.id == id) { "Terminez la série active avant de changer de bilan." }
        require(archive.groups.any { it.id == id })
        save(archive.copy(activeGroupId = id))
    }
    fun reportFile(runner: RunnerRecord): File = File(directory, "reports/${runner.id}-v${runner.revision}.pdf")
    fun retryReports() {
        archive.groups.forEach { group -> group.runners.filter { it.finished(group.session) }.forEach {
            if (it.pdfRevision != it.revision || !reportFile(it).isFile) generateReport(group.id, it.id)
        } }
    }
    private fun generateReport(groupId: String, runnerId: String) {
        val group = archive.groups.first { it.id == groupId }
        val runner = group.runners.first { it.id == runnerId }
        val key = "$runnerId-${runner.revision}"
        if (!generating.add(key)) return
        viewModelScope.launch {
            try {
                pdfSlots.withPermit { withContext(Dispatchers.IO) { PdfExporter.export(getApplication(), group.report(runner), reportFile(runner)) } }
                mutex.withLock {
                    val current = archive.groups.first { it.id == groupId }
                    save(archive.replace(current.copy(runners = current.runners.map {
                        if (it.id == runnerId && it.revision == runner.revision) it.copy(pdfRevision = it.revision) else it
                    })))
                }
                pdfErrors = pdfErrors - runnerId
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { pdfErrors = pdfErrors + (runnerId to "PDF à réessayer : ${e.message}") }
            finally { generating.remove(key) }
        }
    }
    private suspend fun uploadAuthorized(teacherCode: String) {
        require(!raceRunning) { "Terminez la course avant d’envoyer les bilans." }
        networkBusy = true
        var count = 0
        try {
            for (groupSnapshot in archive.groups.filter { it.claimed }) {
                if (groupSnapshot.runners.none { it.closed(groupSnapshot.session) && it.syncedRevision != it.revision }) continue
                if (groupSnapshot.sourceServerId != null && groupSnapshot.sourceServerId != archive.serverId) continue
                requireGroupServer(groupSnapshot)
                for (snapshot in groupSnapshot.runners.filter { it.closed(groupSnapshot.session) && it.syncedRevision != it.revision }) {
                    if (snapshot.finished(groupSnapshot.session) && (snapshot.pdfRevision != snapshot.revision || !reportFile(snapshot).isFile)) {
                        generateReport(groupSnapshot.id, snapshot.id)
                        continue
                    }
                    val response = withContext(Dispatchers.IO) {
                        val pdf = if (snapshot.finished(groupSnapshot.session)) Base64.getEncoder().encodeToString(reportFile(snapshot).readBytes()) else null
                        client().upload(ResultUpload(groupSnapshot.session.id, groupSnapshot.id, archive.deviceId,
                            groupSnapshot.preparedAt, groupSnapshot.startedAt, snapshot, pdf), teacherCode)
                    }
                    check(response.resultId == snapshot.id && response.revision == snapshot.revision && response.gradeTenths == snapshot.grade(groupSnapshot.session)) { "Accusé de réception incohérent." }
                    val current = archive.groups.first { it.id == groupSnapshot.id }
                    save(archive.replace(current.copy(runners = current.runners.map {
                        if (it.id == snapshot.id) it.copy(syncedRevision = snapshot.revision) else it
                    })))
                    count++
                }
            }
            val pending = archive.groups.sumOf { g -> g.runners.count { it.closed(g.session) && it.syncedRevision != it.revision } }
            message = "$count bilan(s) envoyé(s). " + if (pending == 0) "Tous les résultats terminés sont reçus par le professeur." else "$pending bilan(s) conservé(s) : vérifiez les PDF ou reconnectez leur professeur d’origine."
        } finally { networkBusy = false }
    }
}
