package fr.rythmo.sync

import fr.rythmo.session.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

class SyncTest {
    @get:Rule val temp = TemporaryFolder()
    private fun upload(store: TeacherStore, device: String = "tablet-1"): ResultUpload {
        val session = store.download(device, device).session
        val claim = GroupClaim(session.id, newId(), device, device, listOf(session.pupils[0].id))
        store.claim(claim)
        return ResultUpload(session.id, claim.groupId, device, "2026-09-18T10:00:00", "2026-09-18T10:01:00",
            RunnerRecord(pupil = session.pupils[0], rawCumulativeMs = listOf(120_000L, 240_000L, 360_000L, 480_000L, 720_000L)),
            Base64.getEncoder().encodeToString("%PDF-1.4\n test".toByteArray()))
    }

    @Test fun `lost acknowledgements can be retried without duplicate groups or results`() {
        val directory = temp.newFolder()
        val store = TeacherStore(directory)
        val value = upload(store)
        val claim = store.state.claims.single()
        assertEquals(claim, store.claim(claim))
        val first = store.receive(value)
        val second = store.receive(value)
        assertEquals(first, second)
        assertEquals(167, first.gradeTenths)
        assertEquals(1, store.state.results.size)
        val restored = TeacherStore(directory)
        assertEquals(store.state, restored.state)
        assertEquals(first, restored.receive(value))
    }

    @Test fun `a pupil cannot be reserved by two tablets and device count is not fixed`() {
        val store = TeacherStore(temp.newFolder())
        val session = store.download("tablet-1", "Piste").session
        val claim = GroupClaim(session.id, newId(), "tablet-1", "Piste", session.pupils.take(8).map { it.id })
        store.claim(claim)
        assertThrows(IllegalArgumentException::class.java) { store.claim(claim.copy(groupId = newId(), deviceId = "tablet-2")) }
        assertThrows(IllegalArgumentException::class.java) { store.claim(claim.copy(groupId = newId(), pupilIds = session.pupils.take(9).map { it.id })) }
        repeat(20) { store.download("device-$it", "Appareil $it") }
        assertEquals(21, store.state.devices.size)
    }

    @Test fun `new active session never changes the rubric of an older result`() {
        val store = TeacherStore(temp.newFolder())
        val value = upload(store)
        val old = store.state.sessions.first()
        store.publish(old.copy(id = newId(), rubric = old.rubric.copy(version = newId(), reference2000Ms = 300_000)))
        assertEquals(167, store.receive(value).gradeTenths)
        assertEquals(600_000L, store.state.sessions.first().rubric.reference2000Ms)
    }

    @Test fun `corrected uploads replace the received revision and retain original times`() {
        val store = TeacherStore(temp.newFolder())
        val original = upload(store)
        store.receive(original)
        val session = store.state.sessions.first()
        val updated = original.copy(runner = original.runner.correct(session, 5, 120_000))
        val receipt = store.receive(updated)
        assertEquals(2, receipt.revision)
        assertEquals(200, receipt.gradeTenths)
        assertEquals(1, store.state.results.size)
        assertEquals(original.runner.rawCumulativeMs, store.state.results.single().upload.runner.rawCumulativeMs)
        assertEquals(240_000L, store.state.results.single().upload.runner.corrections.single().originalMs)
        assertThrows(IllegalArgumentException::class.java) { store.receive(original) }
    }

    @Test fun `incomplete or unassigned results and missing pdf are not acknowledged`() {
        val store = TeacherStore(temp.newFolder())
        val value = upload(store)
        assertThrows(IllegalArgumentException::class.java) { store.receive(value.copy(deviceId = "another-device")) }
        assertThrows(IllegalArgumentException::class.java) { store.receive(value.copy(pdfBase64 = null)) }
        assertThrows(IllegalArgumentException::class.java) { store.receive(value.copy(runner = value.runner.copy(rawCumulativeMs = listOf(10_000L)))) }
        assertTrue(store.state.results.isEmpty())
        val abandoned = value.copy(runner = value.runner.copy(rawCumulativeMs = listOf(10_000L), abandoned = true), pdfBase64 = null)
        assertNull(store.receive(abandoned).gradeTenths)
    }

    @Test fun `durable client snapshot survives reload and an unfinished replacement file`() {
        val path = File(temp.newFolder(), "client.json")
        val file = JsonFile(path, ClientArchive.serializer()) { ClientArchive() }
        val session = demoSession()
        var group = RaceGroup(session = session, runners = listOf(RunnerRecord(pupil = session.pupils[0])), claimed = true, startElapsedMs = 42L)
        group = group.record(group.runners[0].id, 89_123L)
        val value = ClientArchive(session = session, groups = listOf(group), activeGroupId = group.id)
        file.write(value)
        File(path.parentFile, path.name + ".pending").writeText("interrupted")
        assertEquals(value, file.read())
        assertEquals(89_123L, file.read().activeGroup!!.runners[0].rawCumulativeMs.single())
    }

    @Test fun `corrupt stored data is reported rather than silently overwritten`() {
        val path = File(temp.newFolder(), "client.json")
        path.writeText("damaged")
        val file = JsonFile(path, ClientArchive.serializer()) { ClientArchive() }
        assertThrows(Exception::class.java) { file.read() }
        assertEquals("damaged", path.readText())
    }

    @Test fun `https synchronization downloads claims uploads and rejects a wrong pairing code`() {
        val store = TeacherStore(temp.newFolder())
        val identity = JvmTeacherIdentity.load(temp.newFolder())
        assertEquals(identity.fingerprint, TeacherTls.fingerprint(identity.certificate))
        val server = TeacherServer(store, identity, 0, "127.0.0.1")
        val admin = TeacherServer(store, identity, 0, "127.0.0.1", localAdmin = true)
        admin.start(5000, true)
        server.start(5000, true)
        try {
            val endpoint = "https://127.0.0.1:${server.listeningPort}"
            assertEquals(identity.fingerprint, TeacherTls.inspect(endpoint))
            assertThrows(Exception::class.java) { SyncClient(endpoint, store.state.pairingCode, "0".repeat(64)).download("device", "Test") }
            assertThrows(IllegalArgumentException::class.java) { SyncClient(endpoint.replace("https:", "http:"), "code", identity.fingerprint).download("device", "Test") }
            val client = SyncClient(endpoint, store.state.pairingCode, identity.fingerprint)
            val session = client.download("device", "Tablette de test").session
            val claim = GroupClaim(session.id, newId(), "device", "Tablette de test", session.pupils.take(2).map { it.id })
            assertEquals(claim, client.claim(claim))
            val value = ResultUpload(session.id, claim.groupId, "device", "2026-09-18T10:00:00", "2026-09-18T10:01:00",
                RunnerRecord(pupil = session.pupils[0], rawCumulativeMs = listOf(89_000L, 178_000L, 265_000L, 354_000L, 441_000L)),
                Base64.getEncoder().encodeToString("%PDF-1.4\n test".toByteArray()))
            assertThrows(IllegalStateException::class.java) { client.upload(value, "000000") }
            assertEquals(client.upload(value, store.state.teacherCode), client.upload(value, store.state.teacherCode))
            assertEquals(1, store.state.results.size)
            assertThrows(IllegalStateException::class.java) { SyncClient(endpoint, "wrong", identity.fingerprint).download("device", "Test") }
            val adminEndpoint = "http://127.0.0.1:${admin.listeningPort}"
            val html = URL(adminEndpoint).readText()
            val denied = TeacherTls.connection(endpoint, "/admin/state", identity.fingerprint)
            assertEquals(404, denied.responseCode); denied.disconnect()
            assertTrue(html.contains("Rythmo"))
            val req = URL("$adminEndpoint/admin/state").openConnection() as HttpURLConnection
            assertEquals(401, req.responseCode); req.disconnect()
        } finally { server.stop(); admin.stop() }
    }
}
