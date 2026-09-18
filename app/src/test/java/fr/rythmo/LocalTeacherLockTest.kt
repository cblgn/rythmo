package fr.rythmo

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalTeacherLockTest {
    @get:Rule val directory = TemporaryFolder()
    private val file get() = File(directory.root, "teacher-lock.properties")

    @Test fun `initialization validates six digits and confirmation and stores only salted verifiers`() {
        val lock = LocalTeacherLock(file)
        assertThrows(IllegalArgumentException::class.java) { lock.initialize("12345", "12345") }
        assertThrows(IllegalArgumentException::class.java) { lock.initialize("123456", "654321") }
        assertFalse(file.exists())
        val code = lock.initialize("123456", "123456")
        assertTrue(code.matches(Regex("[A-F0-9]{4}(-[A-F0-9]{4}){7}")))
        assertFalse(file.readText().contains("123456"))
        assertFalse(file.readText().contains(code.replace("-", "")))
        assertFalse(lock.recoveryAcknowledged)
        lock.acknowledgeRecovery()
        val restored = LocalTeacherLock(file)
        assertTrue(restored.configured)
        assertTrue(restored.recoveryAcknowledged)
        assertTrue(restored.unlock("123456"))
        assertThrows(IllegalStateException::class.java) { restored.initialize("654321", "654321") }
    }

    @Test fun `pin and recovery share a durable five error cooldown`() {
        var time = 1_000L
        var lock = LocalTeacherLock(file) { time }
        val recovery = lock.initialize("123456", "123456")
        repeat(3) { assertFalse(lock.unlock("000000")) }
        repeat(2) { assertThrows(IllegalStateException::class.java) { lock.recover("invalid", "654321", "654321") } }
        lock = LocalTeacherLock(file) { time }
        assertThrows(IllegalStateException::class.java) { lock.unlock("123456") }
        time += 29_999
        assertThrows(IllegalStateException::class.java) { lock.recover(recovery, "654321", "654321") }
        time++
        assertTrue(lock.unlock("123456"))
        repeat(4) { assertFalse(lock.unlock("000000")) }
        assertTrue(lock.unlock("123456"))
    }

    @Test fun `recovery rotates both credentials without touching evaluations or PDFs`() {
        val archive = directory.newFile("client.json").apply { writeText("saved evaluation") }
        val pdf = directory.newFile("report.pdf").apply { writeText("saved PDF") }
        val lock = LocalTeacherLock(file)
        val old = lock.initialize("123456", "123456")
        lock.acknowledgeRecovery()
        val replacement = lock.recover(old.lowercase().replace('-', ' '), "654321", "654321")
        assertNotEquals(old, replacement)
        assertFalse(lock.recoveryAcknowledged)
        val restored = LocalTeacherLock(file)
        assertFalse(restored.unlock("123456"))
        assertTrue(restored.unlock("654321"))
        assertThrows(IllegalStateException::class.java) { restored.recover(old, "999999", "999999") }
        restored.recover(replacement, "999999", "999999")
        assertEquals("saved evaluation", archive.readText())
        assertEquals("saved PDF", pdf.readText())
    }

    @Test fun `interrupted setup can issue another recovery code after pin verification`() {
        val lock = LocalTeacherLock(file)
        val old = lock.initialize("123456", "123456")
        val restored = LocalTeacherLock(file)
        assertFalse(restored.recoveryAcknowledged)
        assertTrue(restored.unlock("123456"))
        val fresh = restored.renewUnconfirmedRecovery()
        assertNotEquals(old, fresh)
        assertThrows(IllegalStateException::class.java) { restored.recover(old, "654321", "654321") }
        restored.recover(fresh, "654321", "654321")
    }

    @Test fun `corrupt protection never becomes an unconfigured lock`() {
        file.writeText("broken")
        assertThrows(IllegalArgumentException::class.java) { LocalTeacherLock(file) }
    }
}
