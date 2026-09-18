package fr.rythmo.session

import org.junit.Assert.*
import org.junit.Test

class TeacherAccessTest {
    @Test fun `teacher code is distinct from pairing code and survives offline restoration`() {
        val access = TeacherAccess.fromCode("654321")
        val encoded = sessionJson.encodeToString(TeacherAccess.serializer(), access)
        assertFalse(encoded.contains("654321"))
        val restored = sessionJson.decodeFromString<TeacherAccess>(encoded)
        assertTrue(restored.accepts("654321"))
        assertFalse(restored.accepts("654322"))
        assertFalse(restored.accepts("abc12345"))
        assertNotEquals(access.verifier, TeacherAccess.fromCode("654321").verifier)
    }

    @Test fun `five incorrect codes cause a persisted temporary lockout`() {
        var attempts = TeacherAttempts()
        repeat(5) { attempts = attempts.rejected(1000) }
        val restored = sessionJson.decodeFromString<TeacherAttempts>(sessionJson.encodeToString(TeacherAttempts.serializer(), attempts))
        assertTrue(restored.blocked(30_999))
        assertFalse(restored.blocked(31_000))
    }
}
