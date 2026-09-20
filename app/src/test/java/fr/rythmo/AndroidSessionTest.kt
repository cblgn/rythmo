package fr.rythmo

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import fr.rythmo.session.*
import fr.rythmo.sync.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [RecordingPdfDocument::class])
class AndroidSessionTest {
    @get:Rule val temp = TemporaryFolder()
    private val models = ViewModelStore()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private fun <T: ViewModel> keep(model: T): T = model.also { models.put(newId(), it) }
    @After fun close() { models.clear() }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000
        while (!condition() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertTrue("Asynchronous operation did not finish", condition())
    }
    private fun write(archive: ClientArchive) = JsonFile(File(app.filesDir, "sessions/client.json"), ClientArchive.serializer()) { ClientArchive() }.write(archive)
    private fun model() = keep(SessionViewModel(app)).also { waitFor { it.ready || it.loadError != null } }
    private fun archive(laps: Int = 6): ClientArchive {
        val session = demoSession().copy(distanceMeters = 1000, lapCount = laps)
        val group = RaceGroup(session = session, runners = session.pupils.take(2).map { RunnerRecord(pupil = it) }, claimed = true)
        return ClientArchive(session = session, groups = listOf(group), activeGroupId = group.id, transport = "https", teacherAccess = TeacherAccess.fromCode("654321"))
    }
    @Test fun `group capture persists one instant for every selected runner and undo is durable`() {
        write(archive()); val m = model(); m.startRace(); waitFor { m.raceRunning }
        SystemClock.sleep(1500)
        m.capturePassage(); val capture = requireNotNull(m.capturedPassage)
        m.confirmCapture(emptySet()); assertNotNull(m.capturedPassage)
        m.confirmCapture(setOf("unknown")); assertNotNull(m.capturedPassage)
        m.confirmCapture(capture.eligibleIds)
        waitFor { m.archive.activeGroup!!.runners.all { it.rawCumulativeMs.size == 1 } }
        assertNull(m.capturedPassage)
        assertTrue(m.archive.activeGroup!!.runners.all { it.rawCumulativeMs.single() == capture.elapsedMs })
        val id = m.archive.activeGroup!!.runners.first().id
        assertTrue(m.canUndo(id)); m.undoPassage(id)
        waitFor { m.archive.activeGroup!!.runners.first().rawCumulativeMs.isEmpty() }
        assertFalse(m.canUndo(id)); assertEquals(1, m.archive.activeGroup!!.runners.first().cancelledPassages.size)
        val restored = model(); assertEquals(m.archive.activeGroup, restored.archive.activeGroup)
        assertFalse(restored.canUndo(id))
        m.selectTransport(true); waitFor { m.message != null }; assertEquals("https", m.archive.transport)
        m.dismissMessage(); m.requestUpload(); assertNull(m.teacherRequest); assertNotNull(m.message)
    }
    @Test fun `teacher authorization protects abandonment and retains already recorded times`() {
        val a = archive(); write(a); val m = model(); m.startRace(); waitFor { m.raceRunning }
        val runner = m.archive.activeGroup!!.runners.first(); SystemClock.sleep(1500); m.record(runner.id)
        waitFor { m.archive.activeGroup!!.runners.first().rawCumulativeMs.isNotEmpty() }
        val before = m.archive.activeGroup!!.runners.first().rawCumulativeMs
        m.requestAbandon(runner.id); m.confirmTeacherAction("000000")
        waitFor { m.teacherError != null }; assertFalse(m.archive.activeGroup!!.runners.first().abandoned)
        assertEquals(1, m.archive.teacherAttempts.failures)
        m.confirmTeacherAction("654321"); waitFor { m.archive.activeGroup!!.runners.first().abandoned }
        assertEquals(before, m.archive.activeGroup!!.runners.first().rawCumulativeMs)
        m.requestCloseGroup(); m.confirmTeacherAction("654321"); waitFor { m.archive.activeGroup!!.complete && !m.checkingTeacher }
        assertFalse(m.raceRunning); assertNotNull(m.message)
        m.newGroup(); waitFor { m.archive.activeGroup == null }; assertEquals(1, m.archive.groups.size)
        m.selectGroup(a.activeGroupId!!); waitFor { m.archive.activeGroup != null }
        m.requestCloseGroup(); m.cancelTeacherAction(); assertNull(m.teacherRequest)
    }
    @Test fun `unreadable archive remains untouched and is surfaced to the user`() {
        val file = File(app.filesDir, "sessions/client.json").also { it.parentFile!!.mkdirs(); it.writeText("broken") }
        val m = model(); assertFalse(m.ready); assertNotNull(m.loadError); assertEquals("broken", file.readText())
    }
    @Test fun `session snapshot and assignment use the shared synchronization protocol`() {
        val store = TeacherStore(temp.newFolder())
        val server = SessionSyncServer(store)
        write(ClientArchive(transport = "https"))
        val m = keep(SessionViewModel(app, MessageTransport { server.handle(it) }))
        waitFor { m.ready }; m.download(); waitFor { m.archive.session != null || m.message != null }
        assertEquals(m.message, store.state.activeSessionId, m.archive.session?.id)
        val pupil = m.archive.session!!.pupils.first()
        m.prepare(setOf(pupil.id)); waitFor { m.archive.activeGroup?.claimed == true }
        assertEquals(listOf(pupil.id), store.state.claims.single().pupilIds)
        m.retryClaim(); waitFor { !m.networkBusy }; assertEquals(1, store.state.claims.size)
        m.prepare(emptySet()); waitFor { m.message != "Groupe prêt." }; assertEquals(1, m.archive.groups.size)
    }
    @Test fun `finish generates report and uploads acknowledged results without sending live passages`() {
        val store = TeacherStore(temp.newFolder())
        store.publish(demoSession().copy(distanceMeters=400,lapCount=1))
        val server=SessionSyncServer(store)
        write(ClientArchive(transport="https"))
        val m=keep(SessionViewModel(app,MessageTransport { server.handle(it) }));waitFor { m.ready }
        m.download();waitFor { m.archive.session != null };m.prepare(setOf(m.archive.session!!.pupils.first().id))
        waitFor { m.archive.activeGroup?.claimed==true };m.startRace();waitFor { m.raceRunning }
        assertTrue(store.state.results.isEmpty())
        SystemClock.sleep(1500);val id=m.archive.activeGroup!!.runners.single().id;m.record(id)
        waitFor { m.archive.activeGroup!!.complete && m.archive.activeGroup!!.runners.single().pdfRevision==1 }
        val runner=m.archive.activeGroup!!.runners.single();assertTrue(m.reportFile(runner).exists())
        // The recording backend tests layout; give the receiver a minimal PDF fixture for transport validation.
        m.reportFile(runner).writeText("%PDF-1.4\nFictional transport fixture")
        m.requestUpload();m.confirmTeacherAction(store.state.teacherCode)
        waitFor { m.archive.activeGroup!!.runners.single().syncedRevision==1 }
        assertEquals(1,store.state.results.size)
        m.requestUpload();m.confirmTeacherAction(store.state.teacherCode);waitFor { !m.checkingTeacher }
        assertEquals(1,store.state.results.size);assertFalse(m.canUndo(id))
        m.newGroup();waitFor { m.archive.activeGroup==null };m.selectGroup(m.archive.groups.single().id)
        waitFor { m.archive.activeGroup!=null };assertEquals(1,m.archive.groups.size)
    }
    @Test fun `teacher server commands reject saved role and running races and require the local PIN`() {
        val m=model();val lock=LocalTeacherLock(File(temp.newFolder(),"lock"));lock.initialize("123456","123456");lock.acknowledgeRecovery()
        val settings=keep(TeacherSettingsViewModel { lock });waitFor { settings.ready }
        val individual=keep(RythmoViewModel(androidx.lifecycle.SavedStateHandle()))
        assertThrows(IllegalStateException::class.java) { m.startTeacherServer(settings,individual) }
        assertThrows(IllegalStateException::class.java) { m.stopTeacherServer(settings) }
        settings.submit("123456");waitFor { settings.unlocked };m.startTeacherServer(settings,individual,true)
        val started=shadowOf(app).nextStartedService;assertEquals(TeacherService::class.java.name,started.component!!.className)
        assertTrue(started.getBooleanExtra("nearby",false));m.stopTeacherServer(settings)
        individual.setFirstName("Alice");individual.setLastName("Exemple");individual.setSchoolClass(SCHOOL_CLASSES.first());individual.startRace()
        assertThrows(IllegalStateException::class.java) { m.startTeacherServer(settings,individual) }
        settings.background();assertThrows(IllegalStateException::class.java) { m.stopTeacherServer(settings) }
        TeacherService.status=TeacherStatus(true)
        try { m.retrieveNearby();assertFalse(m.nearbyPanel);assertNotNull(m.message) } finally { TeacherService.status=TeacherStatus(false) }
        m.retrieveNearby();assertTrue(m.nearbyPanel);m.dismissNearbyPanel();assertFalse(m.nearbyPanel)
    }
    @Test fun `interrupted clock invalid inputs and switching active groups preserve the archive`() {
        val initial=archive();val g=initial.activeGroup!!.copy(startElapsedMs=1,bootCount=999)
        write(initial.replace(g));val m=model();assertTrue(m.clockInterrupted)
        m.capturePassage();assertNull(m.capturedPassage);m.record(g.runners.first().id);waitFor { m.message!=null }
        assertTrue(m.archive.activeGroup!!.runners.all { it.rawCumulativeMs.isEmpty() })
        m.dismissMessage();m.newGroup();waitFor { m.message!=null };assertEquals(g.id,m.archive.activeGroupId)
        m.connection("https://teacher/","code","Tablet");waitFor { m.archive.deviceName=="Tablet" }
        assertEquals("https://teacher",m.archive.serverUrl)
        m.dismissMessage();m.retrieveNearby("");waitFor { m.message!=null };assertEquals("Tablet",m.archive.deviceName)
    }

}
