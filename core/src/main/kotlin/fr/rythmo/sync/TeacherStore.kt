package fr.rythmo.sync

import fr.rythmo.session.*
import java.io.File
import java.time.LocalDateTime
import java.util.Base64

class TeacherStore(directory: File) {
    private val file = JsonFile(File(directory, "teacher.json"), ServerArchive.serializer()) {
        val demo = demoSession()
        ServerArchive(sessions = listOf(demo), activeSessionId = demo.id)
    }
    @Volatile var state: ServerArchive = file.read()
        private set
    private val teacherAccess = TeacherAccess.fromCode(state.teacherCode)
    fun acceptsTeacher(code: String): Boolean = teacherAccess.accepts(code)
    init { file.write(state) }
    private fun save(next: ServerArchive) { file.write(next); state = next }

    @Synchronized fun publish(session: SessionConfig) {
        session.validate()
        require(state.sessions.none { it.id == session.id }) { "Publiez une nouvelle séance pour changer le contexte." }
        val existingClass = state.classes.find { it.name == session.schoolClass && it.level == session.level }
        val cls = SchoolClass(existingClass?.id ?: newId(), session.schoolClass, session.level, session.pupils)
        save(state.copy(sessions = state.sessions + session, activeSessionId = session.id,
            classes = state.classes.filterNot { it.id == cls.id } + cls))
    }

    @Synchronized fun download(deviceId: String, deviceName: String): SessionEnvelope {
        require(validId(deviceId) && deviceName.isNotBlank() && deviceName.length <= 100)
        val session = state.sessions.firstOrNull { it.id == state.activeSessionId } ?: error("Aucune séance active.")
        save(state.copy(devices = state.devices.filterNot { it.id == deviceId } + DeviceInfo(deviceId, deviceName, LocalDateTime.now().toString())))
        return SessionEnvelope(session = session, claims = state.claims.filter { it.sessionId == session.id }, teacherAccess = teacherAccess)
    }

    @Synchronized fun claim(claim: GroupClaim): GroupClaim {
        val session = state.sessions.firstOrNull { it.id == claim.sessionId } ?: error("Séance inconnue.")
        require(listOf(claim.groupId, claim.deviceId).all(::validId) && claim.deviceName.isNotBlank() && claim.deviceName.length <= 100)
        require(claim.pupilIds.size in 1..MAX_GROUP_SIZE && claim.pupilIds.distinct().size == claim.pupilIds.size)
        require(claim.pupilIds.all { id -> session.pupils.any { it.id == id } })
        state.claims.find { it.groupId == claim.groupId }?.let {
            require(it == claim) { "Ce groupe est déjà associé à un autre contexte." }
            return it
        }
        require(state.claims.none { it.sessionId == claim.sessionId && it.pupilIds.any(claim.pupilIds::contains) }) {
            "Un élève sélectionné est déjà affecté à un groupe. Synchronisez de nouveau."
        }
        save(state.copy(claims = state.claims + claim))
        return claim
    }

    @Synchronized fun receive(upload: ResultUpload): Receipt {
        val session = state.sessions.firstOrNull { it.id == upload.sessionId } ?: error("Séance inconnue.")
        require(state.claims.any { it.groupId == upload.groupId && it.deviceId == upload.deviceId &&
            it.sessionId == upload.sessionId && upload.runner.pupil.id in it.pupilIds }) { "Groupe non attribué à cet appareil." }
        upload.runner.validate(session)
        require(upload.runner.closed(session)) { "L’élève n’a pas terminé." }
        LocalDateTime.parse(upload.preparedAt)
        upload.startedAt?.let(LocalDateTime::parse)
        require(upload.startedAt != null || upload.runner.abandoned)
        if (upload.runner.finished(session)) {
            val pdf = Base64.getDecoder().decode(requireNotNull(upload.pdfBase64) { "PDF manquant." })
            require(pdf.size in 5..2_000_000 && pdf.take(5).toByteArray().toString(Charsets.US_ASCII) == "%PDF-") { "PDF invalide." }
        }
        val clean = upload.copy(runner = upload.runner.copy(pdfRevision = 0, syncedRevision = 0))
        val prior = state.results.find { it.upload.runner.id == clean.runner.id }
        if (prior != null) {
            require(prior.upload.deviceId == clean.deviceId && prior.upload.groupId == clean.groupId && prior.upload.sessionId == clean.sessionId)
            require(prior.upload.runner.rawCumulativeMs == clean.runner.rawCumulativeMs && prior.upload.runner.pupil == clean.runner.pupil)
            require(clean.runner.corrections.take(prior.upload.runner.corrections.size) == prior.upload.runner.corrections) { "Historique des corrections incohérent." }
            if (prior.upload.runner.revision == clean.runner.revision) {
                require(prior.upload.runner == clean.runner) { "Résultat différent pour une même version." }
                return Receipt(clean.runner.id, clean.runner.revision, prior.gradeTenths)
            }
            require(clean.runner.revision > prior.upload.runner.revision) { "Version de résultat périmée." }
        }
        val note = clean.runner.grade(session)
        save(state.copy(results = state.results.filterNot { it.upload.runner.id == clean.runner.id } +
            StoredResult(clean, note, LocalDateTime.now().toString())))
        return Receipt(clean.runner.id, clean.runner.revision, note)
    }
}
