package fr.rythmo.sync

import fr.rythmo.session.*
import kotlinx.serialization.encodeToString
import java.net.URLEncoder

class SyncClient(private val endpoint: String, private val code: String, private val fingerprint: String) {
    fun download(deviceId: String, name: String): SessionEnvelope {
        val value = sessionJson.decodeFromString<SessionEnvelope>(request("/api/session?deviceId=${encode(deviceId)}&deviceName=${encode(name)}"))
        require(value.protocol == PROTOCOL_VERSION) { "Mettez à jour Rythmo : version de synchronisation incompatible." }
        value.session.validate()
        return value
    }
    fun claim(value: GroupClaim): GroupClaim = sessionJson.decodeFromString(request("/api/claim", sessionJson.encodeToString(value)))
    fun upload(value: ResultUpload, teacherCode: String): Receipt = sessionJson.decodeFromString(request("/api/result", sessionJson.encodeToString(value), teacherCode))
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun request(path: String, body: String? = null, teacherCode: String? = null): String {
        val connection = TeacherTls.connection(endpoint, path, fingerprint)
        try {
            connection.connectTimeout = 7000; connection.readTimeout = 15000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer ${code.trim()}")
            teacherCode?.let { connection.setRequestProperty("X-Rythmo-Teacher", it) }
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "Erreur réseau ($status)."
            check(status in 200..299) { text.take(300) }
            return text
        } finally { connection.disconnect() }
    }
}
