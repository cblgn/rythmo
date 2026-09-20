package fr.rythmo.sync

import fi.iki.elonen.NanoHTTPD
import fr.rythmo.session.*
import kotlinx.serialization.encodeToString
import java.util.Base64
import kotlinx.serialization.json.*

class TeacherServer(val store: TeacherStore, val identity: TeacherTls, port: Int = 8765,
    host: String = "0.0.0.0", private val localAdmin: Boolean = false) : NanoHTTPD(host, port) {
    init {
        if (localAdmin) require(java.net.InetAddress.getByName(host).isLoopbackAddress)
        else makeSecure(identity.context.serverSocketFactory,
            identity.context.supportedSSLParameters.protocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray())
    }
    override fun serve(request: IHTTPSession): Response = try {
        route(request).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
            addHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'")
        }
    } catch (e: Exception) {
        newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain; charset=utf-8", e.message ?: "Requête invalide.")
    }

    private fun route(r: IHTTPSession): Response {
        val adminRoute = r.uri == "/" || r.uri in listOf("/admin.js", "/preparation.js") || r.uri.startsWith("/admin/")
        if (adminRoute != localAdmin && r.uri != "/health")
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Adresse inconnue.")
        if (r.method == Method.GET && r.uri in listOf("/", "/admin.js", "/preparation.js")) {
            val path = if (r.uri == "/") "/teacher/index.html" else "/teacher${r.uri}"
            val content = checkNotNull(javaClass.getResourceAsStream(path)).bufferedReader().use { it.readText() }
            return newFixedLengthResponse(Response.Status.OK, if (r.uri == "/") "text/html; charset=utf-8" else "application/javascript", content)
        }
        if (r.method == Method.GET && r.uri == "/health") return json("{\"protocol\":$PROTOCOL_VERSION,\"name\":\"Rythmo\"}")
        val admin = r.uri.startsWith("/admin/")
        val token = r.headers["authorization"]?.removePrefix("Bearer ")
        if (token != if (admin) store.state.adminKey else store.state.pairingCode) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Code d’association incorrect.")
        }
        if (r.uri == "/api/result" && !store.acceptsTeacher(r.headers["x-rythmo-teacher"] ?: "")) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain; charset=utf-8", "Code professeur incorrect.")
        }
        return when {
            r.method == Method.GET && r.uri == "/admin/state" -> json(JsonObject(sessionJson.encodeToJsonElement(store.state.let { state ->
                state.copy(results = state.results.map { it.copy(upload = it.upload.copy(pdfBase64 = it.upload.pdfBase64?.let { "available" })) })
            }).jsonObject + ("tlsVerificationCode" to JsonPrimitive(identity.verificationCode))).toString())
            r.method == Method.POST && r.uri == "/admin/import/preview" ->
                json(sessionJson.encodeToString(TabularImport.read(sessionJson.decodeFromString(body(r)))))
            r.method == Method.POST && r.uri == "/admin/classes" ->
                json(sessionJson.encodeToString(store.importClass(sessionJson.decodeFromString(body(r)))))
            r.method == Method.POST && r.uri == "/admin/preparation/validate" -> {
                val preparation = sessionJson.decodeFromString<PreparationPackage>(body(r))
                preparation.validate()
                json(sessionJson.encodeToString(preparation))
            }
            r.method == Method.POST && r.uri == "/admin/session" -> {
                val session = sessionJson.decodeFromString<SessionConfig>(body(r))
                store.publish(session)
                json("{\"ok\":true}")
            }
            r.method == Method.GET && r.uri.startsWith("/admin/pdf/") -> {
                val id = r.uri.removePrefix("/admin/pdf/")
                val report = store.state.results.find { it.upload.runner.id == id } ?: error("Bilan introuvable.")
                val bytes = Base64.getDecoder().decode(requireNotNull(report.upload.pdfBase64))
                newFixedLengthResponse(Response.Status.OK, "application/pdf", bytes.inputStream(), bytes.size.toLong())
            }
            r.method == Method.GET && r.uri == "/api/session" -> json(sessionJson.encodeToString(store.download(
                r.parameters["deviceId"]?.firstOrNull() ?: "", r.parameters["deviceName"]?.firstOrNull() ?: "")))
            r.method == Method.POST && r.uri == "/api/claim" -> json(sessionJson.encodeToString(store.claim(sessionJson.decodeFromString(body(r)))))
            r.method == Method.POST && r.uri == "/api/result" -> json(sessionJson.encodeToString(store.receive(sessionJson.decodeFromString(body(r)))))
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Adresse inconnue.")
        }
    }

    private fun body(r: IHTTPSession): String {
        require(r.headers["content-type"]?.startsWith("application/json") == true) { "JSON requis." }
        val size = r.headers["content-length"]?.toLongOrNull() ?: error("Taille du contenu requise.")
        require(size in 1..3_000_000L) { "Contenu trop volumineux." }
        val bytes = ByteArray(size.toInt())
        var read = 0
        while (read < bytes.size) {
            val count = r.inputStream.read(bytes, read, bytes.size - read)
            require(count > 0) { "Transfert interrompu." }
            read += count
        }
        return bytes.toString(Charsets.UTF_8)
    }
    private fun json(value: String) = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", value)
}
