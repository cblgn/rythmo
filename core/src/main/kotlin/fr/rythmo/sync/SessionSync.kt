package fr.rythmo.sync

import fr.rythmo.session.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Blocking exchanges are invoked on an IO dispatcher, never by the timing loop. */
fun interface MessageTransport {
    fun exchange(request: SyncMessage): SyncMessage
}

@Serializable
data class SyncMessage(
    val protocolVersion: Int = 1,
    val type: String,
    val requestId: String = newId(),
    val payload: JsonElement = JsonNull,
    val error: String? = null,
)

@Serializable data class SyncRequest(val deviceId: String, val deviceName: String)
@Serializable data class SubmitResult(val result: ResultUpload, val teacherCode: String)

object SyncCodec {
    const val MAX_BYTES = 3_000_000
    fun encode(value: SyncMessage): ByteArray = sessionJson.encodeToString(value).toByteArray(Charsets.UTF_8).also {
        require(it.size in 1..MAX_BYTES) { "Message trop volumineux." }
    }
    fun readBounded(input: java.io.InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES) { "Message trop volumineux." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    fun decode(bytes: ByteArray): SyncMessage {
        require(bytes.size in 1..MAX_BYTES) { "Message trop volumineux." }
        return sessionJson.decodeFromString(bytes.toString(Charsets.UTF_8))
    }
}

/** Domain authorization is shared by both network adapters. */
class SessionSyncServer(private val store: TeacherStore) {
    fun handle(request: SyncMessage, deviceId: String? = null): SyncMessage = try {
        require(request.protocolVersion == 1) { "Version incompatible. Mettez à jour Rythmo." }
        require(validId(request.requestId)) { "Requête invalide." }
        val payload = when (request.type) {
            "sync_request" -> {
                val value = sessionJson.decodeFromJsonElement<SyncRequest>(request.payload)
                require(deviceId == null || deviceId == value.deviceId) { "Appareil différent de celui associé." }
                sessionJson.encodeToJsonElement(store.download(value.deviceId, value.deviceName))
            }
            "claim_group" -> {
                val claim = sessionJson.decodeFromJsonElement<GroupClaim>(request.payload)
                require(deviceId == null || deviceId == claim.deviceId) { "Appareil différent de celui associé." }
                sessionJson.encodeToJsonElement(store.claim(claim))
            }
            "submit_result" -> {
                val value = sessionJson.decodeFromJsonElement<SubmitResult>(request.payload)
                require(deviceId == null || deviceId == value.result.deviceId) { "Appareil différent de celui associé." }
                require(store.acceptsTeacher(value.teacherCode)) { "Code professeur incorrect." }
                sessionJson.encodeToJsonElement(store.receive(value.result))
            }
            else -> error("Message inconnu : mettez à jour Rythmo.")
        }
        SyncMessage(type = if (request.type == "sync_request") "session_snapshot" else "accepted", requestId = request.requestId, payload = payload)
    } catch (e: IllegalArgumentException) { failure(request, e) }
      catch (e: IllegalStateException) { failure(request, e) }
    private fun failure(request: SyncMessage, e: Exception) = SyncMessage(type = "error", requestId = request.requestId,
        error = e.message ?: "Opération refusée.")
}

class SessionSyncClient(private val transport: MessageTransport) {
    private inline fun <reified T> request(type: String, payload: JsonElement): T {
        val request = SyncMessage(type = type, payload = payload)
        val response = transport.exchange(request)
        require(response.protocolVersion == 1 && response.requestId == request.requestId) { "Réponse incompatible." }
        check(response.type != "error") { response.error ?: "Opération refusée." }
        require(response.type == if (type == "sync_request") "session_snapshot" else "accepted") { "Réponse inattendue." }
        return sessionJson.decodeFromJsonElement(response.payload)
    }
    fun download(deviceId: String, name: String): SessionEnvelope = request<SessionEnvelope>("sync_request",
        sessionJson.encodeToJsonElement(SyncRequest(deviceId, name))).also {
        require(it.protocol == PROTOCOL_VERSION) { "Version de séance incompatible." }; it.session.validate()
    }
    fun claim(value: GroupClaim): GroupClaim = request("claim_group", sessionJson.encodeToJsonElement(value))
    fun upload(value: ResultUpload, teacherCode: String): Receipt = request("submit_result", sessionJson.encodeToJsonElement(SubmitResult(value, teacherCode)))
}

/** Preserve the version-3 HTTP endpoints for existing clients and PC servers. */
class HttpsMessageTransport(private val client: SyncClient) : MessageTransport {
    override fun exchange(request: SyncMessage): SyncMessage {
        val result = when (request.type) {
            "sync_request" -> sessionJson.decodeFromJsonElement<SyncRequest>(request.payload).let {
                sessionJson.encodeToJsonElement(client.download(it.deviceId, it.deviceName))
            }
            "claim_group" -> sessionJson.encodeToJsonElement(client.claim(sessionJson.decodeFromJsonElement(request.payload)))
            "submit_result" -> sessionJson.decodeFromJsonElement<SubmitResult>(request.payload).let {
                sessionJson.encodeToJsonElement(client.upload(it.result, it.teacherCode))
            }
            else -> error("Message inconnu.")
        }
        return SyncMessage(type = if (request.type == "sync_request") "session_snapshot" else "accepted", requestId = request.requestId, payload = result)
    }
}

/** A snapshot updates preparation, never replaces locally measured races. */
fun ClientArchive.withSnapshot(snapshot: SessionEnvelope): ClientArchive {
    snapshot.session.validate()
    val current = activeGroup
    require(current == null || current.complete || current.session.id == snapshot.session.id) {
        "Terminez ou clôturez la série en cours avant de changer de séance."
    }
    require(current == null || current.complete || current.sourceServerId == null || current.sourceServerId == snapshot.serverId) {
        "Reconnectez le serveur d’origine de la série."
    }
    val retained = groups.map { group ->
        val ownedClaim = snapshot.claims.any { it.groupId == group.id && it.sessionId == group.session.id &&
            it.deviceId == deviceId && it.pupilIds.toSet() == group.runners.map { r -> r.pupil.id }.toSet() }
        if (group.sourceServerId == null && ownedClaim) group.copy(sourceServerId = snapshot.serverId) else group
    }
    return copy(session = snapshot.session, serverId = snapshot.serverId, teacherAccess = snapshot.teacherAccess,
        groups = retained, activeGroupId = if (current != null && current.session.id != snapshot.session.id) null else activeGroupId)
}
