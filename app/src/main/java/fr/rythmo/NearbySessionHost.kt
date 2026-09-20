package fr.rythmo

import fr.rythmo.session.sessionJson
import fr.rythmo.sync.*
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.coroutines.*

/** Each authenticated endpoint must introduce its stable device ID before other commands. */
class NearbySessionHost(private val transport: LocalTransport, private val store: TeacherStore) : AutoCloseable {
    private val sync = SessionSyncServer(store)
    private val devices = mutableMapOf<String, String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    init {
        scope.launch {
            var previous = emptySet<String>()
            transport.state.collect { state ->
                val connected = state.connected.map { it.connectionId }.toSet()
                forget(previous - connected)
                previous = connected
            }
        }
    }
    @Synchronized private fun forget(endpoints: Set<String>) { endpoints.forEach(devices::remove) }
    fun start() {
        val session = store.state.sessions.firstOrNull { it.id == store.state.activeSessionId }
        transport.advertise("Rythmo · ${session?.schoolClass ?: "Professeur"}", ::receive)
    }
    @Synchronized private fun receive(endpoint: String, request: SyncMessage): SyncMessage {
        if (request.type == "sync_request") {
            val response = sync.handle(request, devices[endpoint])
            if (response.type != "error") devices[endpoint] = sessionJson.decodeFromJsonElement<SyncRequest>(request.payload).deviceId
            return response
        }
        val device = devices[endpoint] ?: return SyncMessage(type = "error", requestId = request.requestId, error = "Récupérez d’abord la séance.")
        return sync.handle(request, device)
    }
    override fun close() { scope.cancel(); transport.close(); synchronized(this) { devices.clear() } }
}
