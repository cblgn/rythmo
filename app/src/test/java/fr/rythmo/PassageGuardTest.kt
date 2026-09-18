package fr.rythmo

import org.junit.Assert.*
import org.junit.Test

class PassageGuardTest {
    @Test fun `a burst cannot queue duplicate laps while other runners remain available`() {
        val guard = PassageGuard()
        assertTrue(guard.begin(setOf("alice"), 0))
        repeat(1000) { assertFalse(guard.begin(setOf("alice"), it.toLong())) }
        assertTrue(guard.begin(setOf("bob"), 500))
        // A slow persistence call does not start the cooldown or the undo window early.
        guard.saved(setOf("alice"), 5000); guard.finish(setOf("alice"))
        assertFalse(guard.begin(setOf("alice"), 5999))
        assertTrue(guard.canUndo("alice", 19_999))
        assertFalse(guard.canUndo("alice", 20_000))
        assertTrue(guard.begin(setOf("alice"), 6000))
    }
    @Test fun `failed batches release admission without granting undo or partially reserving runners`() {
        val guard = PassageGuard()
        assertTrue(guard.begin(setOf("a"), 0))
        assertFalse(guard.begin(setOf("a", "b"), 0))
        assertTrue(guard.begin(setOf("b"), 0))
        guard.finish(setOf("a", "b"))
        assertFalse(guard.canUndo("a", 1))
        assertTrue(guard.begin(setOf("a", "b"), 1))
        guard.saved(setOf("a", "b"), 10); guard.finish(setOf("a", "b"))
        guard.consumeUndo("a")
        assertFalse(guard.canUndo("a", 11)); assertTrue(guard.canUndo("b", 11))
        assertFalse(PassageGuard().canUndo("b", 11))
    }
}
