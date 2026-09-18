package fr.rythmo

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class TeacherSettingsViewModelTest {
    @get:Rule val directory = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { stores.forEach { it.clear() }; Dispatchers.resetMain() }
    private fun model(): TeacherSettingsViewModel = TeacherSettingsViewModel { LocalTeacherLock(File(directory.root, "lock")) }.also {
        stores += ViewModelStore().apply { put("settings", it) }
    }
    private fun awaitIdle(model: TeacherSettingsViewModel) {
        repeat(500) {
            dispatcher.scheduler.runCurrent()
            if (model.ready && !model.busy) return
            Thread.sleep(10)
        }
        fail("ViewModel did not become idle")
    }
    @Test fun `settings require credentials and recovery acknowledgement and relock on exit and background`() {
        val model = model(); awaitIdle(model)
        assertThrows(IllegalStateException::class.java) { model.requireUnlocked() }
        model.submit("123456", "123456"); awaitIdle(model)
        assertThrows(IllegalStateException::class.java) { model.requireUnlocked() }
        model.acknowledgeRecovery(); awaitIdle(model)
        assertTrue(model.setupComplete); assertFalse(model.unlocked)
        model.requestSettings(); assertFalse(model.unlocked)
        model.submit("000000"); awaitIdle(model); assertFalse(model.unlocked)
        model.submit("123456"); awaitIdle(model); model.requireUnlocked()
        // The retained ViewModel survives rotation; no lifecycle background event is sent.
        assertTrue(model.unlocked)
        model.leaveSettings(); assertThrows(IllegalStateException::class.java) { model.requireUnlocked() }
        model.submit("123456"); awaitIdle(model); model.background()
        assertFalse(model.requested); assertFalse(model.unlocked)
        val restored = model(); awaitIdle(restored)
        assertTrue(restored.configured); assertFalse(restored.unlocked)
    }
    @Test fun `restoring navigation once cannot reopen settings or override a return to the group`() {
        val model = model(); awaitIdle(model)
        model.restoreNavigation(true)
        assertTrue(model.individual)
        assertFalse(model.unlocked)
        model.returnToGroup()
        model.restoreNavigation(true)
        assertFalse(model.individual)
        assertFalse(model.requested)
    }
    @Test fun `verification finishing after background cannot unlock settings`() {
        val model = model(); awaitIdle(model)
        model.submit("123456", "123456"); awaitIdle(model)
        model.acknowledgeRecovery(); awaitIdle(model)
        model.submit("123456")
        model.background()
        awaitIdle(model)
        assertFalse(model.unlocked)
        assertThrows(IllegalStateException::class.java) { model.openIndividual() }
    }
}
