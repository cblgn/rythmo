package fr.rythmo.sync

import kotlinx.coroutines.flow.StateFlow

/** Endpoint IDs are temporary transport addresses, never persistent device identities. */
data class Endpoint(val id: String, val name: String, val connectionId: String = "")
data class Association(val endpoint: Endpoint, val code: String)
enum class ConnectionPhase { IDLE, SEARCHING, CONNECTING, ASSOCIATING, CONNECTED, LOST, ERROR }
data class TransportState(
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val endpoints: List<Endpoint> = emptyList(),
    val associations: List<Association> = emptyList(),
    val connected: List<Endpoint> = emptyList(),
    val error: String? = null,
    val advertising: Boolean = false,
)
interface LocalTransport : MessageTransport, AutoCloseable {
    val state: StateFlow<TransportState>
    fun discover(name: String)
    fun connect(endpoint: Endpoint, name: String)
    fun confirm(endpointId: String, accept: Boolean)
    fun advertise(name: String, receive: (String, SyncMessage) -> SyncMessage)
    fun disconnect()
}
