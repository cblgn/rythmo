package fr.rythmo.sync

import fr.rythmo.session.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

class SessionSyncTest {
    @get:Rule val temp = TemporaryFolder()
    private class FakeTransport(val server: SessionSyncServer, val device: String) : MessageTransport {
        var connected = true
        val requests = mutableListOf<String>()
        override fun exchange(request: SyncMessage): SyncMessage {
            check(connected) { "Disconnected" }
            requests += request.type
            return SyncCodec.decode(SyncCodec.encode(server.handle(SyncCodec.decode(SyncCodec.encode(request)), device)))
        }
    }
    @Test fun `joining and reconnecting gets a complete current snapshot without changing local timing`() {
        val store = TeacherStore(temp.newFolder())
        val transport = FakeTransport(SessionSyncServer(store), "tablet")
        val client = SessionSyncClient(transport)
        val snapshot = client.download("tablet", "Tablet")
        assertEquals(store.state.serverId, snapshot.serverId)
        assertNotNull(snapshot.teacherAccess)
        val group = RaceGroup(session = snapshot.session, runners = listOf(RunnerRecord(pupil = snapshot.session.pupils.first())),
            claimed = true, startElapsedMs = 100L, sourceServerId = snapshot.serverId)
        client.claim(GroupClaim(group.session.id, group.id, "tablet", "Tablet", group.runners.map { it.pupil.id }))
        val initial = ClientArchive(deviceId = "tablet", groups = listOf(group), activeGroupId = group.id).withSnapshot(snapshot)
        transport.connected = false
        val running = group.record(group.runners.single().id, 91_123)
        val archive = initial.replace(running)
        val correctedLocal = running.record(running.runners.single().id, 185_333).let {
            it.copy(runners = listOf(it.runners.single().cancelLastPassage()))
        }
        assertEquals(listOf("sync_request", "claim_group"), transport.requests)
        assertThrows(IllegalStateException::class.java) { client.download("tablet", "Tablet") }
        transport.connected = true
        val refresh = client.download("tablet", "Tablet")
        assertEquals(1, refresh.claims.size)
        assertTrue(refresh.sessionVersion > snapshot.sessionVersion)
        assertEquals(running, archive.withSnapshot(refresh).activeGroup)
        assertEquals(correctedLocal, archive.replace(correctedLocal).withSnapshot(refresh).activeGroup)
        val late = SessionSyncClient(FakeTransport(SessionSyncServer(store), "late")).download("late", "Late tablet")
        assertEquals(refresh.session, late.session)
        assertEquals(refresh.claims, late.claims)
    }
    @Test fun `server validates commands and persists only authorized final reports with idempotent receipts`() {
        val directory = temp.newFolder()
        val store = TeacherStore(directory)
        val server = SessionSyncServer(store)
        val client = SessionSyncClient(FakeTransport(server, "tablet"))
        val snapshot = client.download("tablet", "Tablet")
        val claim = GroupClaim(snapshot.session.id, newId(), "tablet", "Tablet", listOf(snapshot.session.pupils.first().id))
        assertEquals(claim, client.claim(claim))
        val second = SessionSyncClient(FakeTransport(server, "second"))
        assertThrows(IllegalStateException::class.java) { second.claim(claim.copy(deviceId = "second", groupId = newId())) }
        val report = ResultUpload(snapshot.session.id, claim.groupId, "tablet", "2026-09-19T10:00:00", "2026-09-19T10:01:00",
            RunnerRecord(pupil = snapshot.session.pupils.first(), rawCumulativeMs = listOf(90_000L,180_000L,270_000L,360_000L,450_000L)),
            Base64.getEncoder().encodeToString(("%PDF-" + "x".repeat(40_000)).toByteArray()))
        assertThrows(IllegalStateException::class.java) { client.upload(report, "wrong") }
        assertTrue(store.state.results.isEmpty())
        assertThrows(IllegalStateException::class.java) { second.upload(report, store.state.teacherCode) }
        val receipt = client.upload(report, store.state.teacherCode)
        assertEquals(receipt, client.upload(report, store.state.teacherCode))
        assertEquals(1, store.state.results.size)
        assertEquals(store.state, TeacherStore(directory).state)
    }
    @Test fun `unknown protocol types oversized payloads and inconsistent responses are rejected`() {
        val server = SessionSyncServer(TeacherStore(temp.newFolder()))
        assertEquals("error", server.handle(SyncMessage(type = "live_timing")).type)
        assertEquals("error", server.handle(SyncMessage(protocolVersion = 99, type = "sync_request")).type)
        assertEquals("error", server.handle(SyncMessage(type = "claim_group", payload = JsonPrimitive("bad"))).type)
        assertThrows(IllegalArgumentException::class.java) { SyncCodec.decode(ByteArray(SyncCodec.MAX_BYTES + 1)) }
        val wrong = SessionSyncClient(MessageTransport { SyncMessage(type = "session_snapshot") })
        assertThrows(IllegalArgumentException::class.java) { wrong.download("tablet", "Tablet") }
    }
    @Test fun `stream messages are bounded and truncated messages cannot be applied`() {
        val request = SyncMessage(type = "test", payload = JsonPrimitive("x".repeat(40_000)))
        val bytes = SyncCodec.encode(request)
        assertEquals(request, SyncCodec.decode(SyncCodec.readBounded(bytes.inputStream())))
        assertThrows(IllegalArgumentException::class.java) { SyncCodec.readBounded(ByteArray(SyncCodec.MAX_BYTES + 1).inputStream()) }
        assertThrows(IllegalArgumentException::class.java) { SyncCodec.decode(bytes.copyOf(bytes.size - 1)) }
    }
    @Test fun `new session cannot replace an unfinished group and completed archives are retained`() {
        val old = demoSession()
        val group = RaceGroup(session = old, runners = listOf(RunnerRecord(pupil = old.pupils.first())))
        val archive = ClientArchive(groups = listOf(group), activeGroupId = group.id)
        val snapshot = SessionEnvelope(session = old.copy(id = newId()), claims = emptyList())
        assertThrows(IllegalArgumentException::class.java) { archive.withSnapshot(snapshot) }
        val closed = group.copy(runners = group.runners.map { it.copy(abandoned = true) })
        val updated = archive.replace(closed).withSnapshot(snapshot)
        assertNull(updated.activeGroup)
        assertEquals(listOf(closed), updated.groups)
    }

    @Test fun `an unfinished race cannot be reassociated with a different server`() {
        val session = demoSession()
        val group = RaceGroup(session = session, sourceServerId = "original",
            runners = listOf(RunnerRecord(pupil = session.pupils.first(), rawCumulativeMs = listOf(91_123L))))
        val archive = ClientArchive(groups = listOf(group), activeGroupId = group.id, serverId = "original")
        val foreign = SessionEnvelope(session = session, claims = emptyList(), serverId = "other")
        assertThrows(IllegalArgumentException::class.java) { archive.withSnapshot(foreign) }
        assertEquals(group, archive.activeGroup)
        assertEquals(group, archive.withSnapshot(foreign.copy(serverId = "original")).activeGroup)
    }

    @Test fun `legacy groups acquire server identity only from their exact confirmed assignment`() {
        val session = demoSession()
        val group = RaceGroup(session = session, runners = listOf(RunnerRecord(pupil = session.pupils.first())))
        val archive = ClientArchive(deviceId = "tablet", groups = listOf(group), activeGroupId = group.id)
        val claim = GroupClaim(session.id, group.id, archive.deviceId, "Tablet", group.runners.map { it.pupil.id })
        val snapshot = SessionEnvelope(session = session, claims = listOf(claim), serverId = "teacher")
        assertEquals("teacher", archive.withSnapshot(snapshot).activeGroup?.sourceServerId)
        listOf(claim.copy(deviceId = "other"), claim.copy(groupId = newId()),
            claim.copy(sessionId = newId()), claim.copy(pupilIds = listOf(session.pupils[1].id))).forEach { mismatch ->
            assertNull(archive.withSnapshot(snapshot.copy(claims = listOf(mismatch))).activeGroup?.sourceServerId)
        }
        assertNull(archive.withSnapshot(snapshot.copy(claims = emptyList())).activeGroup?.sourceServerId)
    }

    @Test fun `client rejects incompatible versions and unexpected response types`() {
        val responses = listOf<(SyncMessage) -> SyncMessage>(
            { it.copy(protocolVersion = 99, type = "session_snapshot") },
            { it.copy(type = "accepted") },
            { it.copy(type = "session_snapshot", payload = sessionJson.encodeToJsonElement(
                SessionEnvelope(protocol = 99, session = demoSession(), claims = emptyList()))) },
        )
        responses.forEach { respond ->
            val client = SessionSyncClient(MessageTransport(respond))
            assertThrows(IllegalArgumentException::class.java) { client.download("tablet", "Tablet") }
        }
    }
}
