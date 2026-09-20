package fr.rythmo.sync

import fr.rythmo.session.*
import kotlinx.serialization.Serializable

const val PROTOCOL_VERSION = 3
const val ASSESSMENT_PROTOCOL_VERSION = 4
const val ROUNDED_ASSESSMENT_PROTOCOL_VERSION = 5
fun supportedProtocol(version: Int) = version in PROTOCOL_VERSION..ROUNDED_ASSESSMENT_PROTOCOL_VERSION

@Serializable data class SessionEnvelope(val protocol: Int = PROTOCOL_VERSION, val session: SessionConfig, val claims: List<GroupClaim>, val teacherAccess: TeacherAccess? = null, val serverId: String? = null, val sessionVersion: Long = 0)
@Serializable data class GroupClaim(val sessionId: String, val groupId: String, val deviceId: String, val deviceName: String, val pupilIds: List<String>)
@Serializable data class ResultUpload(val sessionId: String, val groupId: String, val deviceId: String,
    val preparedAt: String, val startedAt: String?, val runner: RunnerRecord, val pdfBase64: String? = null)
@Serializable data class Receipt(val resultId: String, val revision: Int, val gradeTenths: Int?)
@Serializable data class StoredResult(val upload: ResultUpload, val gradeTenths: Int?, val receivedAt: String, val assessmentScore: AssessmentScore? = null)
@Serializable data class DeviceInfo(val id: String, val name: String, val lastSync: String)
@Serializable data class ServerArchive(
    val serverId: String = newId(), val adminKey: String = newId(), val pairingCode: String = newId().take(8),
    val classes: List<SchoolClass> = demoClasses(), val sessions: List<SessionConfig> = emptyList(),
    val stateRevision: Long = 0,
    val activeSessionId: String? = null, val claims: List<GroupClaim> = emptyList(),
    val results: List<StoredResult> = emptyList(), val devices: List<DeviceInfo> = emptyList(),
    val teacherCode: String = TeacherAccess.newCode(),
)
