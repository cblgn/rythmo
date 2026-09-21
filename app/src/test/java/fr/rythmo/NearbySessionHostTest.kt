package fr.rythmo

import fr.rythmo.session.*
import fr.rythmo.sync.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NearbySessionHostTest {
    @get:Rule val temp = TemporaryFolder()
    private class FakeTransport : LocalTransport {
        override val state = MutableStateFlow(TransportState())
        lateinit var receive: (String, SyncMessage) -> SyncMessage
        override fun advertise(name: String, receive: (String, SyncMessage) -> SyncMessage) { this.receive = receive }
        override fun discover(name: String) { state.value = TransportState(phase = ConnectionPhase.SEARCHING) }
        override fun connect(endpoint: Endpoint, name: String) { state.value = TransportState(phase = ConnectionPhase.ASSOCIATING) }
        override fun confirm(endpointId: String, accept: Boolean) { state.value = TransportState(phase = if (accept) ConnectionPhase.CONNECTED else ConnectionPhase.IDLE) }
        override fun disconnect() { state.value = TransportState(phase = ConnectionPhase.LOST) }
        override fun close() = disconnect()
        override fun exchange(request: SyncMessage) = receive("endpoint", request)
    }
    @Test fun `endpoints must synchronize first and cannot change their device identity`() {
        val store = TeacherStore(temp.newFolder())
        val transport = FakeTransport()
        val host = NearbySessionHost(transport, store)
        host.start("Teacher")
        assertTrue(host.displayName("Teacher").startsWith("Teacher · "))
        assertTrue(host.displayName("Teacher").contains(store.state.sessions.first().title))
        val session = store.state.sessions.first()
        val claim = GroupClaim(session.id, newId(), "one", "Tablet", listOf(session.pupils.first().id))
        val command = SyncMessage(type = "claim_group", payload = sessionJson.encodeToJsonElement(claim))
        assertEquals("error", transport.exchange(command).type)
        val client = SessionSyncClient(transport)
        client.download("one", "Tablet")
        assertEquals(claim, client.claim(claim))
        assertThrows(IllegalStateException::class.java) { client.download("two", "Other tablet") }
        assertEquals("error", transport.exchange(command.copy(payload = sessionJson.encodeToJsonElement(claim.copy(deviceId = "two")))).type)
        assertEquals(1, store.state.claims.size)
        host.close()
    }
}
